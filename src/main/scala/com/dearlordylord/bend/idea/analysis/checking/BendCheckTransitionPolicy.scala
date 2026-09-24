package com.dearlordylord.bend.idea.analysis.checking

import com.dearlordylord.bend.idea.analysis.model.*
import com.dearlordylord.bend.idea.model.FileId

enum BendCheckOrigin:
  case Explicit, Background

enum BendCheckWorkerPhase:
  case Reserved, Running

final case class BendCheckWorker(root: FileId, generation: Long, origin: BendCheckOrigin,
    phase: BendCheckWorkerPhase)

final case class BendPendingCheckSnapshot(generation: Long, snapshot: BendCheckSnapshot)

/** State owned by one project session; the adapter owns every platform handle. */
final case class BendRootCheckState(generation: Long = 0L,
    result: Option[BendCheckResult] = None,
    snapshot: Option[BendCheckSnapshot] = None,
    pending: Option[BendPendingCheckSnapshot] = None)

final case class BendCheckState(roots: Map[FileId, BendRootCheckState] = Map.empty,
    active: Option[BendCheckWorker] = None,
    rootOrder: Vector[FileId] = Vector.empty,
    nextGeneration: Long = 0L,
    explicitWaiting: Boolean = false,
    disposed: Boolean = false)

enum BendCheckInvalidationReason:
  case SourceChanged, DependencyChanged, ConfigurationChanged, ExternalInputChanged

/** Facts observed at the adapter boundary immediately before the finish transition. */
final case class BendCheckFinishFacts(sourceCurrent: Boolean, graphCurrent: Boolean,
    toolchainCurrent: Boolean, externalInputsCurrent: Boolean, canceled: Boolean)

sealed trait BendCheckEvent
object BendCheckEvent:
  final case class BeginRequested(snapshot: BendCheckSnapshot, origin: BendCheckOrigin)
      extends BendCheckEvent
  final case class WorkerStarted(root: FileId, generation: Long, snapshot: BendCheckSnapshot)
      extends BendCheckEvent
  final case class InputsInvalidated(roots: Set[FileId], reason: BendCheckInvalidationReason)
      extends BendCheckEvent
  final case class SnapshotInvalidated(root: FileId, generation: Long,
      snapshot: BendCheckSnapshot, reason: BendCheckInvalidationReason) extends BendCheckEvent
  final case class CancelRequested(root: FileId) extends BendCheckEvent
  case object ConfigurationInvalidated extends BendCheckEvent
  final case class WorkerFinished(root: FileId, generation: Long, snapshot: BendCheckSnapshot,
      result: BendCheckResult, facts: BendCheckFinishFacts) extends BendCheckEvent
  final case class WorkerExited(root: FileId, generation: Long) extends BendCheckEvent
  case object ExplicitWaitAbandoned extends BendCheckEvent
  case object Disposed extends BendCheckEvent

sealed trait BendCheckAction
object BendCheckAction:
  final case class ReplaceRootSubscription(root: FileId) extends BendCheckAction
  final case class UnsubscribeRoot(root: FileId) extends BendCheckAction
  final case class CancelWorker(generation: Long) extends BendCheckAction
  final case class SlotReleased(generation: Long) extends BendCheckAction
  case object RefreshUi extends BendCheckAction

enum BendCheckDecision:
  case Started(generation: Long)
  case WaitingForWorker(generation: Long)
  case RejectedBusy, RejectedDisposed, Continue, Discarded
  case Published(result: BendCheckResult)

final case class BendCheckTransition(state: BendCheckState,
    actions: Vector[BendCheckAction], decision: BendCheckDecision)

/** Pure owner for root generations, result acceptance, and explicit worker-slot transitions. */
object BendCheckTransitionPolicy:
  val MaxTrackedRoots = 128

  import BendCheckAction.*
  import BendCheckDecision.*
  import BendCheckEvent.*

  private def done(state: BendCheckState, actions: Vector[BendCheckAction] = Vector.empty,
      decision: BendCheckDecision = Continue): BendCheckTransition =
    BendCheckTransition(state, actions, decision)

  private def nextGeneration(state: BendCheckState): (BendCheckState, Long) =
    val next = state.nextGeneration + 1L
    (state.copy(nextGeneration = next), next)

  private def stale(root: BendRootCheckState): BendRootCheckState =
    root.copy(result = root.result.map(_.copy(fresh = false)))

  private def invalidate(state: BendCheckState, root: FileId,
      actions: Vector[BendCheckAction]): (BendCheckState, Vector[BendCheckAction]) =
    state.roots.get(root) match
      case None => (state, actions)
      case Some(current) =>
        val (advanced, generation) = nextGeneration(state)
        val updated = stale(current).copy(generation = generation)
        val active = advanced.active.filter(_.root == root)
        active match
          case Some(worker) if worker.phase == BendCheckWorkerPhase.Reserved =>
            val cleared = updated.copy(pending = None)
            (advanced.copy(roots = advanced.roots.updated(root, cleared), active = None),
              actions :+ SlotReleased(worker.generation))
          case Some(worker) =>
            (advanced.copy(roots = advanced.roots.updated(root, updated)),
              actions :+ CancelWorker(worker.generation))
          case None =>
            (advanced.copy(roots = advanced.roots.updated(root, updated)),
              actions)

  private def keyMatches(snapshot: BendCheckSnapshot, result: BendCheckResult): Boolean =
    val key = result.key
    key.root == snapshot.root && key.sourceRevision == snapshot.sourceRevision &&
      key.sourceFingerprint == BendAnalysisKey.sourceDigest(snapshot.text) &&
      key.configurationRevision == snapshot.toolchain.configurationRevision &&
      key.executable == snapshot.toolchain.executable

  private def reserve(state: BendCheckState, snapshot: BendCheckSnapshot,
      origin: BendCheckOrigin): BendCheckTransition =
    val root = snapshot.root
    var retained = state
    var order = state.rootOrder
    var actions = Vector.empty[BendCheckAction]
    if !retained.roots.contains(root) then
      while order.size >= MaxTrackedRoots do
        val oldest = order.head
        order = order.tail
        retained = retained.copy(roots = retained.roots - oldest)
        actions :+= UnsubscribeRoot(oldest)
      order :+= root
      retained = retained.copy(rootOrder = order)
    val (advanced, generation) = nextGeneration(retained)
    val previous = advanced.roots.getOrElse(root, BendRootCheckState())
    val updated = stale(previous).copy(generation = generation,
      pending = Some(BendPendingCheckSnapshot(generation, snapshot)))
    val active = BendCheckWorker(root, generation, origin, BendCheckWorkerPhase.Reserved)
    done(advanced.copy(roots = advanced.roots.updated(root, updated), active = Some(active),
      explicitWaiting = false), actions ++ Vector(ReplaceRootSubscription(root), RefreshUi),
      Started(generation))

  def transition(state: BendCheckState, event: BendCheckEvent): BendCheckTransition =
    import event.*
    if state.disposed then
      event match
        case Disposed => done(state)
        case _ => done(state, decision = RejectedDisposed)
    else event match
      case BeginRequested(snapshot, origin) =>
        state.active match
          case Some(worker) if origin == BendCheckOrigin.Explicit &&
              worker.origin == BendCheckOrigin.Background =>
            val (invalidated, actions) =
              if state.explicitWaiting then (state, Vector.empty)
              else invalidate(state, worker.root, Vector.empty)
            val preempted = invalidated.copy(explicitWaiting = true)
            if worker.phase == BendCheckWorkerPhase.Reserved then
              val started = reserve(preempted.copy(active = None), snapshot, origin)
              val priorEffects = if state.explicitWaiting then Vector.empty else
                Vector(CancelWorker(worker.generation)) ++ actions
              started.copy(actions = priorEffects ++ started.actions)
            else done(preempted, actions, WaitingForWorker(worker.generation))
          case Some(_) => done(state, decision = RejectedBusy)
          case None if origin == BendCheckOrigin.Background && state.explicitWaiting =>
            done(state, decision = RejectedBusy)
          case None => reserve(state, snapshot, origin)

      case WorkerStarted(root, generation, snapshot) =>
        val accepted = state.active.exists(worker => worker.root == root &&
          worker.generation == generation && worker.phase == BendCheckWorkerPhase.Reserved) &&
          state.roots.get(root).exists(_.generation == generation) && snapshot.root == root
        if !accepted then done(state, decision = Discarded)
        else
          val current = state.roots(root)
          val started = current.copy(pending = Some(BendPendingCheckSnapshot(generation, snapshot)))
          done(state.copy(active = state.active.map(_.copy(phase = BendCheckWorkerPhase.Running)),
            roots = state.roots.updated(root, started)))

      case InputsInvalidated(roots, _) =>
        val (next, actions) = roots.toList.sortBy(_.value).foldLeft((state,
            Vector.empty[BendCheckAction])) { case ((current, requested), root) =>
          invalidate(current, root, requested)
        }
        done(next, actions)

      case SnapshotInvalidated(root, generation, snapshot, reason) =>
        val matches = state.roots.get(root).exists(rootState =>
          rootState.generation == generation &&
            (rootState.snapshot.contains(snapshot) || rootState.pending.exists(_.snapshot == snapshot)))
        if !matches then done(state)
        else
          val invalidated = transition(state, InputsInvalidated(Set(root), reason))
          invalidated.copy(actions = invalidated.actions :+ RefreshUi)

      case CancelRequested(root) =>
        if !state.active.exists(_.root == root) then done(state)
        else
          val (next, actions) = invalidate(state, root, Vector.empty)
          done(next, actions :+ RefreshUi)

      case ConfigurationInvalidated =>
        val (next, actions) = state.roots.keys.toList.sortBy(_.value).foldLeft((state,
            Vector.empty[BendCheckAction])) { case ((current, requested), root) =>
          invalidate(current, root, requested)
        }
        done(next, actions :+ RefreshUi)

      case WorkerFinished(root, generation, snapshot, result, facts) =>
        val current = state.roots.get(root)
        val pending = current.flatMap(_.pending).filter(_.generation == generation)
        val workerMatches = state.active.exists(worker => worker.root == root &&
          worker.generation == generation && worker.phase == BendCheckWorkerPhase.Running)
        val accepted = !state.disposed && workerMatches &&
          current.exists(_.generation == generation) && pending.exists(p =>
            p.snapshot == snapshot && snapshot.root == root && keyMatches(snapshot, result)) &&
          facts.sourceCurrent && facts.graphCurrent && facts.toolchainCurrent &&
          facts.externalInputsCurrent && !facts.canceled
        if !accepted then done(state, decision = Discarded)
        else
          val snapshot = pending.get.snapshot
          val published = current.get.copy(result = Some(result), snapshot = Some(snapshot),
            pending = None)
          done(state.copy(roots = state.roots.updated(root, published)),
            Vector(RefreshUi), Published(result))

      case WorkerExited(root, generation) =>
        val workerMatches = state.active.exists(worker => worker.root == root &&
          worker.generation == generation)
        val roots = state.roots.get(root) match
          case Some(current) if current.pending.exists(_.generation == generation) =>
            state.roots.updated(root, current.copy(pending = None))
          case _ => state.roots
        if workerMatches then done(state.copy(active = None, roots = roots),
          Vector(SlotReleased(generation)))
        else if roots != state.roots then done(state.copy(roots = roots))
        else done(state)

      case ExplicitWaitAbandoned =>
        done(state.copy(explicitWaiting = false))

      case Disposed =>
        val activeActions = state.active.toVector.flatMap(worker =>
          Vector(CancelWorker(worker.generation), SlotReleased(worker.generation)))
        val subscriptions = state.rootOrder.map(UnsubscribeRoot.apply)
        done(state.copy(roots = Map.empty, active = None, rootOrder = Vector.empty,
          explicitWaiting = false, disposed = true), activeActions ++ subscriptions)
