package com.dearlordylord.bend.idea.analysis.checking

import com.dearlordylord.bend.idea.analysis.model.*
import com.dearlordylord.bend.idea.model.FileId

enum BendCheckWorkerPhase:
  case Reserved, Running

final case class BendCheckWorker(
    root: FileId,
    generation: Long,
    origin: BendCheckOrigin,
    phase: BendCheckWorkerPhase,
    backgroundToken: Option[Long] = None
):
  def reservation: BendCheckReservation =
    BendCheckReservation(root, generation, origin, backgroundToken)

enum BendBackgroundPhase:
  case Scheduled, Preparing, Ready, InFlight

final case class BendBackgroundIntent(
    token: Long,
    attempt: Int,
    phase: BendBackgroundPhase
)

final case class BendPendingCheckSnapshot(
    generation: Long,
    snapshot: BendCheckSnapshot
)

/** State owned by one project session; the adapter owns every platform handle.
  */
final case class BendRootCheckState(
    generation: Long = 0L,
    result: Option[BendCheckResult] = None,
    snapshot: Option[BendCheckSnapshot] = None,
    pending: Option[BendPendingCheckSnapshot] = None,
    background: Option[BendBackgroundIntent] = None
)

final case class BendCheckState(
    roots: Map[FileId, BendRootCheckState] = Map.empty,
    active: Option[BendCheckWorker] = None,
    rootOrder: Vector[FileId] = Vector.empty,
    nextGeneration: Long = 0L,
    nextBackgroundToken: Long = 0L,
    backgroundEnabled: Boolean = true,
    explicitWaiting: Boolean = false,
    disposed: Boolean = false
)

enum BendCheckInvalidationReason:
  case SourceChanged, DependencyChanged, ConfigurationChanged,
    ExternalInputChanged

/** Facts observed at the adapter boundary immediately before the finish
  * transition.
  */
final case class BendCheckFinishFacts(
    sourceCurrent: Boolean,
    graphCurrent: Boolean,
    toolchainCurrent: Boolean,
    externalInputsCurrent: Boolean,
    canceled: Boolean
)

sealed trait BendCheckEvent
object BendCheckEvent:
  final case class BeginRequested(
      snapshot: BendCheckSnapshot,
      origin: BendCheckOrigin,
      backgroundToken: Option[Long] = None
  ) extends BendCheckEvent
  final case class BackgroundRequested(root: FileId) extends BendCheckEvent
  final case class BackgroundTimerFired(root: FileId, token: Long)
      extends BendCheckEvent
  final case class BackgroundAttemptFailed(root: FileId, token: Long)
      extends BendCheckEvent
  final case class BackgroundScheduleFailed(root: FileId, token: Long)
      extends BendCheckEvent
  final case class BackgroundStaleObserved(root: FileId) extends BendCheckEvent
  final case class BackgroundConfigurationChanged(enabled: Boolean)
      extends BendCheckEvent
  case object BackgroundSchedulerDisposed extends BendCheckEvent
  final case class WorkerStarted(
      reservation: BendCheckReservation,
      snapshot: BendCheckSnapshot
  ) extends BendCheckEvent
  object WorkerStarted:
    def apply(
        root: FileId,
        generation: Long,
        snapshot: BendCheckSnapshot
    ): WorkerStarted =
      WorkerStarted(
        BendCheckReservation(root, generation, BendCheckOrigin.Explicit, None),
        snapshot
      )
  final case class InputsInvalidated(
      roots: Set[FileId],
      reason: BendCheckInvalidationReason
  ) extends BendCheckEvent
  final case class SnapshotInvalidated(
      root: FileId,
      generation: Long,
      snapshot: BendCheckSnapshot,
      reason: BendCheckInvalidationReason
  ) extends BendCheckEvent
  final case class CancelRequested(root: FileId) extends BendCheckEvent
  case object ConfigurationInvalidated extends BendCheckEvent
  final case class WorkerFinished(
      reservation: BendCheckReservation,
      snapshot: BendCheckSnapshot,
      result: BendCheckResult,
      facts: BendCheckFinishFacts
  ) extends BendCheckEvent
  object WorkerFinished:
    def apply(
        root: FileId,
        generation: Long,
        snapshot: BendCheckSnapshot,
        result: BendCheckResult,
        facts: BendCheckFinishFacts
    ): WorkerFinished =
      WorkerFinished(
        BendCheckReservation(root, generation, BendCheckOrigin.Explicit, None),
        snapshot,
        result,
        facts
      )
  final case class WorkerExited(reservation: BendCheckReservation)
      extends BendCheckEvent
  object WorkerExited:
    def apply(root: FileId, generation: Long): WorkerExited =
      WorkerExited(
        BendCheckReservation(root, generation, BendCheckOrigin.Explicit, None)
      )
  case object ExplicitWaitAbandoned extends BendCheckEvent
  case object Disposed extends BendCheckEvent

sealed trait BendCheckAction
object BendCheckAction:
  final case class ReplaceRootSubscription(root: FileId) extends BendCheckAction
  final case class UnsubscribeRoot(root: FileId) extends BendCheckAction
  final case class CancelWorker(generation: Long) extends BendCheckAction
  final case class SlotReleased(generation: Long) extends BendCheckAction
  final case class ScheduleBackground(
      root: FileId,
      token: Long,
      delayMillis: Long
  ) extends BendCheckAction
  final case class CancelBackgroundTimer(root: FileId, token: Long)
      extends BendCheckAction
  final case class DropBackgroundRoot(root: FileId) extends BendCheckAction
  case object RefreshUi extends BendCheckAction

enum BendCheckDecision:
  case Started(reservation: BendCheckReservation)
  case WaitingForWorker(generation: Long)
  case RejectedBusy, RejectedDisposed, Continue, Discarded
  case Published(result: BendCheckResult)
  case BackgroundReady(token: Long, attempt: Int)

final case class BendCheckTransition(
    state: BendCheckState,
    actions: Vector[BendCheckAction],
    decision: BendCheckDecision
)

/** Pure owner for generations, publication, worker slots, and background
  * scheduling decisions.
  */
object BendCheckTransitionPolicy:
  val MaxTrackedRoots = 128
  val BackgroundDebounceMillis = 500L
  val MaxBackgroundRetries = 2

  import BendCheckAction.*
  import BendCheckDecision.*
  import BendCheckEvent.*

  private def done(
      state: BendCheckState,
      actions: Vector[BendCheckAction] = Vector.empty,
      decision: BendCheckDecision = Continue
  ): BendCheckTransition =
    BendCheckTransition(state, actions, decision)

  private def nextGeneration(state: BendCheckState): (BendCheckState, Long) =
    val next = state.nextGeneration + 1L
    (state.copy(nextGeneration = next), next)

  private def stale(root: BendRootCheckState): BendRootCheckState =
    root.copy(result = root.result.map(_.copy(fresh = false)))

  private def keyMatches(
      snapshot: BendCheckSnapshot,
      result: BendCheckResult
  ): Boolean =
    val key = result.key
    key.root == snapshot.root && key.sourceRevision == snapshot.sourceRevision &&
    key.sourceFingerprint == BendAnalysisKey.sourceDigest(snapshot.text) &&
    key.configurationRevision == snapshot.toolchain.configurationRevision &&
    key.executable == snapshot.toolchain.executable &&
    key.snapshotProvenance == BendCheckSnapshotProvenance.from(snapshot) &&
    key.basePath == snapshot.selectedBasePath

  private def dropRoot(
      state: BendCheckState,
      root: FileId,
      actions: Vector[BendCheckAction]
  ): (BendCheckState, Vector[BendCheckAction]) =
    val background = state.roots.get(root).flatMap(_.background)
    val active = state.active.filter(_.root == root)
    val (withoutActive, workerActions) = active match
      case Some(worker) if worker.phase == BendCheckWorkerPhase.Reserved =>
        (
          state.copy(active = None),
          Vector(
            CancelWorker(worker.generation),
            SlotReleased(worker.generation)
          )
        )
      case Some(worker) => (state, Vector(CancelWorker(worker.generation)))
      case None         => (state, Vector.empty)
    val withoutRoot = withoutActive.copy(
      roots = withoutActive.roots - root,
      rootOrder = withoutActive.rootOrder.filterNot(_ == root)
    )
    val timerAction = background.toVector.map(intent =>
      CancelBackgroundTimer(root, intent.token)
    )
    (
      withoutRoot,
      actions ++ workerActions ++ timerAction ++
        Vector(UnsubscribeRoot(root), DropBackgroundRoot(root))
    )

  private def ensureTracked(
      state: BendCheckState,
      root: FileId,
      actions: Vector[BendCheckAction]
  ): (BendCheckState, Vector[BendCheckAction]) =
    if state.roots.contains(root) then (state, actions)
    else
      @scala.annotation.tailrec
      def makeRoom(
          current: BendCheckState,
          effects: Vector[BendCheckAction]
      ): (BendCheckState, Vector[BendCheckAction]) =
        if current.rootOrder.size < MaxTrackedRoots then (current, effects)
        else
          val oldest = current.rootOrder
            .find(id => !current.active.exists(_.root == id))
            .getOrElse(current.rootOrder.head)
          val removed = dropRoot(current, oldest, effects)
          makeRoom(removed._1, removed._2)

      val (current, effects) = makeRoom(state, actions)
      (
        current.copy(
          roots = current.roots.updated(root, BendRootCheckState()),
          rootOrder = current.rootOrder :+ root
        ),
        effects
      )

  private def scheduleReady(
      state: BendCheckState,
      actions: Vector[BendCheckAction]
  ): (BendCheckState, Vector[BendCheckAction]) =
    if state.active.nonEmpty || state.explicitWaiting || !state.backgroundEnabled
    then (state, actions)
    else
      state.rootOrder.iterator
        .flatMap(root =>
          state.roots
            .get(root)
            .flatMap(_.background)
            .filter(_.phase == BendBackgroundPhase.Ready)
            .map(root -> _)
        )
        .take(1)
        .toList
        .headOption match
        case None                 => (state, actions)
        case Some((root, intent)) =>
          val scheduled = intent.copy(phase = BendBackgroundPhase.Scheduled)
          (
            state.copy(roots =
              state.roots.updated(
                root,
                state.roots(root).copy(background = Some(scheduled))
              )
            ),
            actions :+ ScheduleBackground(
              root,
              intent.token,
              BackgroundDebounceMillis
            )
          )

  private def invalidate(
      state: BendCheckState,
      root: FileId,
      actions: Vector[BendCheckAction]
  ): (BendCheckState, Vector[BendCheckAction]) =
    state.roots.get(root) match
      case None          => (state, actions)
      case Some(current) =>
        val (advanced, generation) = nextGeneration(state)
        val updated = stale(current).copy(
          generation = generation,
          background = current.background.map(intent =>
            intent.copy(
              phase =
                if intent.phase == BendBackgroundPhase.InFlight ||
                  intent.phase == BendBackgroundPhase.Preparing
                then BendBackgroundPhase.Ready
                else intent.phase
            )
          )
        )
        val active = advanced.active.filter(_.root == root)
        active match
          case Some(worker) if worker.phase == BendCheckWorkerPhase.Reserved =>
            val cleared = updated.copy(pending = None)
            (
              advanced.copy(
                roots = advanced.roots.updated(root, cleared),
                active = None
              ),
              actions ++ Vector(
                CancelWorker(worker.generation),
                SlotReleased(worker.generation)
              )
            )
          case Some(worker) =>
            (
              advanced.copy(roots = advanced.roots.updated(root, updated)),
              actions :+ CancelWorker(worker.generation)
            )
          case None =>
            (
              advanced.copy(roots = advanced.roots.updated(root, updated)),
              actions
            )

  private def reserve(
      state: BendCheckState,
      snapshot: BendCheckSnapshot,
      origin: BendCheckOrigin,
      backgroundToken: Option[Long] = None
  ): BendCheckTransition =
    val root = snapshot.root
    val (retained, actions) = ensureTracked(state, root, Vector.empty)
    val (advanced, generation) = nextGeneration(retained)
    val previous = advanced.roots.getOrElse(root, BendRootCheckState())
    val nextBackground = if origin == BendCheckOrigin.Background then
      previous.background
        .filter(intent => backgroundToken.contains(intent.token))
        .map(_.copy(phase = BendBackgroundPhase.InFlight))
    else None
    val updated = stale(previous).copy(
      generation = generation,
      pending = Some(BendPendingCheckSnapshot(generation, snapshot)),
      background = nextBackground
    )
    val active = BendCheckWorker(
      root,
      generation,
      origin,
      BendCheckWorkerPhase.Reserved,
      backgroundToken
    )
    val cancelCurrent = if origin == BendCheckOrigin.Explicit then
      previous.background.toVector
        .map(intent => CancelBackgroundTimer(root, intent.token))
    else Vector.empty
    done(
      advanced.copy(
        roots = advanced.roots.updated(root, updated),
        active = Some(active),
        explicitWaiting = false
      ),
      actions ++ cancelCurrent ++
        Vector(ReplaceRootSubscription(root), RefreshUi),
      Started(active.reservation)
    )

  def transition(
      state: BendCheckState,
      event: BendCheckEvent
  ): BendCheckTransition =
    if state.disposed then
      event match
        case Disposed => done(state)
        case _        => done(state, decision = RejectedDisposed)
    else
      event match
        case BackgroundRequested(root) =>
          if !state.backgroundEnabled then done(state)
          else
            val (tracked, effects) = ensureTracked(state, root, Vector.empty)
            val old = tracked.roots(root).background
            val token = tracked.nextBackgroundToken + 1L
            val canceled = effects ++ old.toVector.map(intent =>
              CancelBackgroundTimer(root, intent.token)
            )
            val (invalidated, invalidationActions) =
              if tracked.active.exists(worker =>
                  worker.root == root &&
                    worker.origin == BendCheckOrigin.Background
                )
              then invalidate(tracked, root, canceled)
              else (tracked, canceled)
            val intent =
              BendBackgroundIntent(token, 0, BendBackgroundPhase.Scheduled)
            val updated =
              invalidated.roots(root).copy(background = Some(intent))
            done(
              invalidated.copy(
                roots = invalidated.roots.updated(root, updated),
                nextBackgroundToken = token
              ),
              invalidationActions :+
                ScheduleBackground(root, token, BackgroundDebounceMillis)
            )

        case BackgroundTimerFired(root, token) =>
          state.roots.get(root).flatMap(_.background) match
            case Some(intent)
                if intent.token == token &&
                  intent.phase == BendBackgroundPhase.Scheduled && state.backgroundEnabled =>
              if state.active.isEmpty && !state.explicitWaiting then
                val preparing =
                  intent.copy(phase = BendBackgroundPhase.Preparing)
                done(
                  state.copy(roots =
                    state.roots.updated(
                      root,
                      state.roots(root).copy(background = Some(preparing))
                    )
                  ),
                  decision = BackgroundReady(token, intent.attempt)
                )
              else
                val ready = intent.copy(phase = BendBackgroundPhase.Ready)
                done(
                  state.copy(roots =
                    state.roots.updated(
                      root,
                      state.roots(root).copy(background = Some(ready))
                    )
                  )
                )
            case _ => done(state)

        case BackgroundAttemptFailed(root, token) =>
          val currentWorker = state.active.filter(worker =>
            worker.root == root &&
              worker.origin == BendCheckOrigin.Background && worker.backgroundToken
                .contains(token)
          )
          state.roots.get(root).flatMap(_.background) match
            case Some(intent)
                if intent.token == token &&
                  (intent.phase == BendBackgroundPhase.Preparing ||
                    (intent.phase == BendBackgroundPhase.InFlight && currentWorker.nonEmpty)) =>
              val canRetry = intent.attempt < MaxBackgroundRetries
              val nextIntent = if canRetry then
                Some(
                  intent.copy(
                    attempt = intent.attempt + 1,
                    phase = BendBackgroundPhase.Ready
                  )
                )
              else None
              val current = state.roots(root)
              val clearReserved =
                currentWorker.exists(_.phase == BendCheckWorkerPhase.Reserved)
              val rootState = current.copy(
                pending = if clearReserved then None else current.pending,
                background = nextIntent
              )
              val released = if clearReserved then
                Vector(
                  CancelWorker(currentWorker.get.generation),
                  SlotReleased(currentWorker.get.generation)
                )
              else Vector.empty
              val next = state.copy(
                active = if clearReserved then None else state.active,
                roots = state.roots.updated(root, rootState)
              )
              val (scheduled, actions) = scheduleReady(next, released)
              done(scheduled, actions)
            case _ => done(state)

        case BackgroundScheduleFailed(root, token) =>
          state.roots.get(root).flatMap(_.background) match
            case Some(intent)
                if state.backgroundEnabled && intent.token == token &&
                  intent.phase == BendBackgroundPhase.Scheduled =>
              val canRetry = intent.attempt < MaxBackgroundRetries
              val nextIntent = if canRetry then
                Some(
                  intent.copy(
                    attempt = intent.attempt + 1,
                    phase = BendBackgroundPhase.Ready
                  )
                )
              else None
              val updated = state.roots(root).copy(background = nextIntent)
              val next = state.copy(roots = state.roots.updated(root, updated))
              val (scheduled, actions) = scheduleReady(
                next,
                Vector(CancelBackgroundTimer(root, token))
              )
              done(scheduled, actions)
            case _ => done(state)

        case BackgroundStaleObserved(root) =>
          if !state.backgroundEnabled || !state.roots
              .get(root)
              .exists(rootState =>
                rootState.result
                  .exists(!_.fresh) && rootState.background.isEmpty
              )
          then done(state)
          else transition(state, BackgroundRequested(root))

        case BackgroundConfigurationChanged(enabled) =>
          val (next, actions) = state.roots.toList.foldLeft(
            (
              state.copy(backgroundEnabled = enabled),
              Vector.empty[BendCheckAction]
            )
          ) { case ((currentState, accumulated), (root, rootState)) =>
            val withTimerCancellation = rootState.background.fold(accumulated)(
              intent => accumulated :+ CancelBackgroundTimer(root, intent.token)
            )
            val invalidated =
              if state.active.exists(worker =>
                  worker.root == root &&
                    worker.origin == BendCheckOrigin.Background
                )
              then invalidate(currentState, root, withTimerCancellation)
              else (currentState, withTimerCancellation)
            val withoutBackground = invalidated._1.roots
              .get(root)
              .fold(invalidated._1)(current =>
                invalidated._1.copy(roots =
                  invalidated._1.roots
                    .updated(root, current.copy(background = None))
                )
              )
            val nextActions =
              if !enabled then invalidated._2 :+ DropBackgroundRoot(root)
              else invalidated._2
            (withoutBackground, nextActions)
          }
          done(next, actions :+ RefreshUi)

        case BackgroundSchedulerDisposed =>
          val (next, actions) = state.roots.toList.foldLeft(
            (state, Vector.empty[BendCheckAction])
          ) { case ((currentState, accumulated), (root, rootState)) =>
            val withTimerCancellation = rootState.background.fold(accumulated)(
              intent => accumulated :+ CancelBackgroundTimer(root, intent.token)
            )
            val invalidated =
              if state.active.exists(worker =>
                  worker.root == root &&
                    worker.origin == BendCheckOrigin.Background
                )
              then invalidate(currentState, root, withTimerCancellation)
              else (currentState, withTimerCancellation)
            val withoutBackground = invalidated._1.roots
              .get(root)
              .fold(invalidated._1)(current =>
                invalidated._1.copy(roots =
                  invalidated._1.roots
                    .updated(root, current.copy(background = None))
                )
              )
            (withoutBackground, invalidated._2 :+ DropBackgroundRoot(root))
          }
          done(next, actions)

        case BeginRequested(
              snapshot,
              BendCheckOrigin.Background,
              backgroundToken
            ) =>
          val intent = state.roots.get(snapshot.root).flatMap(_.background)
          val matching = backgroundToken.exists(token =>
            intent.exists(current =>
              current.token == token && current.phase == BendBackgroundPhase.Preparing
            )
          )
          if !state.backgroundEnabled || !matching then
            done(state, decision = RejectedBusy)
          else
            state.active match
              case Some(_) =>
                val ready = intent.get.copy(phase = BendBackgroundPhase.Ready)
                done(
                  state.copy(roots =
                    state.roots.updated(
                      snapshot.root,
                      state.roots(snapshot.root).copy(background = Some(ready))
                    )
                  ),
                  decision = RejectedBusy
                )
              case None if state.explicitWaiting =>
                val ready = intent.get.copy(phase = BendBackgroundPhase.Ready)
                done(
                  state.copy(roots =
                    state.roots.updated(
                      snapshot.root,
                      state.roots(snapshot.root).copy(background = Some(ready))
                    )
                  ),
                  decision = RejectedBusy
                )
              case None =>
                reserve(
                  state,
                  snapshot,
                  BendCheckOrigin.Background,
                  backgroundToken
                )

        case BeginRequested(snapshot, BendCheckOrigin.Explicit, _) =>
          state.active match
            case Some(worker) if worker.origin == BendCheckOrigin.Background =>
              val (invalidated, actions) =
                if state.explicitWaiting then (state, Vector.empty)
                else invalidate(state, worker.root, Vector.empty)
              val preempted = invalidated.copy(explicitWaiting = true)
              if worker.phase == BendCheckWorkerPhase.Reserved then
                val started = reserve(
                  preempted.copy(active = None),
                  snapshot,
                  BendCheckOrigin.Explicit
                )
                val priorEffects =
                  if state.explicitWaiting then Vector.empty else actions
                started.copy(actions = priorEffects ++ started.actions)
              else done(preempted, actions, WaitingForWorker(worker.generation))
            case Some(_) => done(state, decision = RejectedBusy)
            case None    => reserve(state, snapshot, BendCheckOrigin.Explicit)

        case WorkerStarted(reservation, snapshot) =>
          val root = reservation.root
          val generation = reservation.generation
          val accepted = state.active.exists(worker =>
            worker.reservation == reservation &&
              worker.phase == BendCheckWorkerPhase.Reserved
          ) &&
            state.roots
              .get(root)
              .exists(_.generation == generation) && snapshot.root == root
          if !accepted then done(state, decision = Discarded)
          else
            val current = state.roots(root)
            val started = current.copy(pending =
              Some(BendPendingCheckSnapshot(generation, snapshot))
            )
            done(
              state.copy(
                active = state.active.map(
                  _.copy(phase = BendCheckWorkerPhase.Running)
                ),
                roots = state.roots.updated(root, started)
              )
            )

        case InputsInvalidated(roots, _) =>
          val (next, actions) = roots.toList
            .sortBy(_.value)
            .foldLeft((state, Vector.empty[BendCheckAction])) {
              case ((current, requested), root) =>
                invalidate(current, root, requested)
            }
          val (scheduled, effects) = scheduleReady(next, actions)
          done(scheduled, effects)

        case SnapshotInvalidated(root, generation, snapshot, reason) =>
          val matches = state.roots
            .get(root)
            .exists(rootState =>
              rootState.generation == generation &&
                (rootState.snapshot.contains(snapshot) || rootState.pending
                  .exists(_.snapshot == snapshot))
            )
          if !matches then done(state)
          else
            val invalidated =
              transition(state, InputsInvalidated(Set(root), reason))
            invalidated.copy(actions = invalidated.actions :+ RefreshUi)

        case CancelRequested(root) =>
          if !state.active.exists(_.root == root) &&
            !state.roots.get(root).exists(_.background.nonEmpty)
          then done(state)
          else
            val current = state.roots
              .get(root)
              .flatMap(_.background)
              .toVector
              .map(intent => CancelBackgroundTimer(root, intent.token))
            val (next, actions) = invalidate(state, root, current)
            val withoutBackground = next.roots
              .get(root)
              .fold(next)(value =>
                next.copy(roots =
                  next.roots.updated(root, value.copy(background = None))
                )
              )
            done(withoutBackground, actions :+ RefreshUi)

        case ConfigurationInvalidated =>
          val (next, actions) = state.roots.keys.toList
            .sortBy(_.value)
            .foldLeft((state, Vector.empty[BendCheckAction])) {
              case ((current, requested), root) =>
                invalidate(current, root, requested)
            }
          done(next, actions :+ RefreshUi)

        case WorkerFinished(reservation, snapshot, result, facts) =>
          val root = reservation.root
          val generation = reservation.generation
          val current = state.roots.get(root)
          val pending =
            current.flatMap(_.pending).filter(_.generation == generation)
          val workerMatches = state.active.exists(worker =>
            worker.reservation == reservation &&
              worker.phase == BendCheckWorkerPhase.Running
          )
          val accepted = !state.disposed && workerMatches &&
            current.exists(_.generation == generation) && pending.exists(p =>
              p.snapshot == snapshot && snapshot.root == root && keyMatches(
                snapshot,
                result
              )
            ) &&
            facts.sourceCurrent && facts.graphCurrent && facts.toolchainCurrent &&
            facts.externalInputsCurrent && !facts.canceled
          if !accepted then done(state, decision = Discarded)
          else
            val captured = pending.get.snapshot
            val background = state.active
              .flatMap(_.backgroundToken)
              .filter(token =>
                current.flatMap(_.background).exists(_.token == token)
              )
            val published = current.get.copy(
              result = Some(result),
              snapshot = Some(captured),
              pending = None,
              background =
                if background.nonEmpty then None else current.get.background
            )
            done(
              state.copy(roots = state.roots.updated(root, published)),
              Vector(RefreshUi),
              Published(result)
            )

        case WorkerExited(reservation) =>
          state.active.filter(current =>
            current.reservation == reservation
          ) match
            case None         => done(state)
            case Some(exited) =>
              val root = reservation.root
              val generation = reservation.generation
              val roots = state.roots.get(root) match
                case Some(current)
                    if current.pending.exists(_.generation == generation) =>
                  state.roots.updated(root, current.copy(pending = None))
                case _ => state.roots
              val withoutAbandonedIntent = roots
                .get(root)
                .map { current =>
                  if exited.backgroundToken.exists(token =>
                      current.background.exists(intent =>
                        intent.token == token && intent.phase == BendBackgroundPhase.InFlight
                      )
                    )
                  then roots.updated(root, current.copy(background = None))
                  else roots
                }
                .getOrElse(roots)
              val (scheduled, actions) = scheduleReady(
                state.copy(active = None, roots = withoutAbandonedIntent),
                Vector(SlotReleased(generation))
              )
              done(scheduled, actions)

        case ExplicitWaitAbandoned =>
          val (scheduled, actions) =
            scheduleReady(state.copy(explicitWaiting = false), Vector.empty)
          done(scheduled, actions)

        case Disposed =>
          val activeActions = state.active.toVector.flatMap(worker =>
            Vector(
              CancelWorker(worker.generation),
              SlotReleased(worker.generation)
            )
          )
          val roots = state.rootOrder.flatMap(root =>
            Vector(UnsubscribeRoot(root), DropBackgroundRoot(root)) ++
              state.roots
                .get(root)
                .flatMap(_.background)
                .map(intent => CancelBackgroundTimer(root, intent.token))
          )
          done(
            state.copy(
              roots = Map.empty,
              active = None,
              rootOrder = Vector.empty,
              explicitWaiting = false,
              disposed = true
            ),
            activeActions ++ roots
          )
