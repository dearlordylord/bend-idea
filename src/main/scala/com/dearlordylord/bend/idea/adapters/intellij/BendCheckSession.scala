package com.dearlordylord.bend.idea.adapters.intellij

import com.dearlordylord.bend.idea.adapters.cli.{
  BendCliCheckBackend,
  BendExternalInputs
}
import com.dearlordylord.bend.idea.analysis.api.{
  BendBackgroundCheckControl,
  BendBackgroundCheckTicket,
  BendCheckService
}
import com.dearlordylord.bend.idea.analysis.checking.*
import com.dearlordylord.bend.idea.analysis.checking.BendCheckAction.*
import com.dearlordylord.bend.idea.analysis.checking.BendCheckDecision.*
import com.dearlordylord.bend.idea.analysis.checking.BendCheckEvent.*
import com.dearlordylord.bend.idea.analysis.model.*
import com.dearlordylord.bend.idea.model.FileId
import com.dearlordylord.bend.idea.toolchain.api.BendToolchainSettings
import com.dearlordylord.bend.idea.workspace.model.BendSourceRecord
import com.dearlordylord.bend.idea.workspace.ports.BendSourceCatalog
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.{DocumentEvent, DocumentListener}
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.util.Alarm
import scala.collection.mutable
import scala.jdk.CollectionConverters.*
import java.nio.file.Path

/** One project worker; the transition policy owns root acceptance and scheduler
  * state.
  */
final class BendCheckSession(project: Project)
    extends BendCheckService,
      BendBackgroundCheckControl,
      Disposable:
  private val uiRefreshDelayMillis = 2000
  private val backend = new BendCliCheckBackend
  private var policyState = BendCheckState()
  private val subscriptions = mutable.Map.empty[FileId, () => Unit]
  private val currentChecks = mutable.Map.empty[Long, () => Boolean]
  private val canceledWorkers = mutable.Set.empty[Long]
  private val uiRefreshAlarm = new Alarm(Alarm.ThreadToUse.SWING_THREAD, this)

  private def applyEvent(event: BendCheckEvent): BendCheckTransition =
    synchronized {
      val next = BendCheckTransitionPolicy.transition(policyState, event)
      policyState = next.state
      next
    }

  private def perform(
      actions: Seq[BendCheckAction],
      replacement: Option[(FileId, (() => Unit) => (() => Unit))] = None
  ): Unit =
    actions.foreach {
      case ReplaceRootSubscription(root) =>
        replacement.filter(_._1 == root).foreach { case (_, subscribe) =>
          val unsubscription = subscribe(() => sourceChanged(root))
          val previous = synchronized {
            if policyState.disposed || !policyState.roots.contains(root) then
              None
            else Some(subscriptions.put(root, unsubscription))
          }
          previous match
            case Some(old) => old.foreach(_())
            case None      => unsubscription()
        }
      case UnsubscribeRoot(root) =>
        val previous = synchronized { subscriptions.remove(root) }
        previous.foreach(_())
      case CancelWorker(generation) =>
        synchronized { canceledWorkers += generation }
      case SlotReleased(generation) =>
        synchronized {
          canceledWorkers -= generation
          val _ = currentChecks.remove(generation)
          notifyAll()
        }
      case action: ScheduleBackground =>
        backgroundAdapter.foreach(_.applyPolicyAction(action))
      case action: CancelBackgroundTimer =>
        backgroundAdapter.foreach(_.applyPolicyAction(action))
      case action: DropBackgroundRoot =>
        backgroundAdapter.foreach(_.applyPolicyAction(action))
      case RefreshUi => scheduleUiRefresh()
    }

  private def backgroundAdapter: Option[BendBackgroundChecking] =
    if project.isDisposed then None
    else Option(project.getService(classOf[BendBackgroundChecking]))

  private def scheduleUiRefresh(): Unit =
    val restartRequest = new Runnable:
      override def run(): Unit =
        val active = synchronized { policyState.disposed }
        if !active && !project.isDisposed then
          val analyzer = DaemonCodeAnalyzer.getInstance(project)
          analyzer.restart()
    uiRefreshAlarm.cancelAllRequests()
    uiRefreshAlarm.addRequest(
      restartRequest,
      uiRefreshDelayMillis,
      ModalityState.any()
    )

  private def refreshUiBeforeReturning(): Unit =
    uiRefreshAlarm.cancelAllRequests()
    val application = ApplicationManager.getApplication
    val disposed = synchronized { policyState.disposed }
    if !disposed && !project.isDisposed then
      val analyzer = DaemonCodeAnalyzer.getInstance(project)
      val restart = new Runnable:
        override def run(): Unit =
          if !project.isDisposed && !synchronized { policyState.disposed } then
            analyzer.restart()
      if application.isDispatchThread then restart.run()
      else application.invokeAndWait(restart, ModalityState.any())

  private def sourceChanged(root: FileId): Unit =
    val result = applyEvent(
      InputsInvalidated(Set(root), BendCheckInvalidationReason.SourceChanged)
    )
    perform(result.actions)

  EditorFactory
    .getInstance()
    .getEventMulticaster
    .addDocumentListener(
      new DocumentListener:
        override def documentChanged(event: DocumentEvent): Unit =
          val file =
            FileDocumentManager.getInstance().getFile(event.getDocument)
          if file != null then
            val canonical = Option(file.getCanonicalPath)
            val identity =
              new FileId(canonical.getOrElse(file.getPath), canonical.isDefined)
            val roots = synchronized {
              policyState.roots.toList.collect {
                case (root, rootState)
                    if root != identity &&
                      rootDependsOn(rootState, identity) =>
                  root
              }.toSet
            }
            if roots.nonEmpty then
              val result = applyEvent(
                InputsInvalidated(
                  roots,
                  BendCheckInvalidationReason.DependencyChanged
                )
              )
              perform(result.actions)
      ,
      this
    )

  project.getMessageBus
    .connect(this)
    .subscribe(
      VirtualFileManager.VFS_CHANGES,
      new BulkFileListener:
        override def after(events: java.util.List[? <: VFileEvent]): Unit =
          val changed = events.asScala.map(_.getPath).toSet
          val affected = synchronized {
            policyState.roots.toList.collect {
              case (root, rootState)
                  if rootState.result.exists(result =>
                    changed(result.key.executable) ||
                      result.key.basePath.exists(changed)
                  ) ||
                    (rootState.snapshot.toList ++ rootState.pending.toList.map(
                      _.snapshot
                    )).exists(snapshotAffectedBy(_, changed)) =>
                root
            }.toSet
          }
          if affected.nonEmpty then
            val result = applyEvent(
              InputsInvalidated(
                affected,
                BendCheckInvalidationReason.DependencyChanged
              )
            )
            perform(result.actions :+ RefreshUi)
    )

  private def rootDependsOn(root: BendRootCheckState, source: FileId): Boolean =
    (root.snapshot.toList ++ root.pending.toList.map(_.snapshot)).exists {
      snapshot =>
        snapshot.root != source && (snapshot.graph.exists(
          _.files.exists(_.source.id == source)
        ) ||
          snapshot.siblingLaws.exists(_.id == source))
    }

  private def snapshotAffectedBy(
      snapshot: BendCheckSnapshot,
      changed: Set[String]
  ): Boolean =
    changed(snapshot.path) || snapshot.graph.exists(graph =>
      graph.files.exists(file =>
        changed(file.source.path) || changed(file.source.id.value)
      ) ||
        graph.edges.exists(edge => changed(edge.requestedPath))
    ) ||
      (Path.of(snapshot.path).getFileName.toString == "PROOF.bend" &&
        changed(Path.of(snapshot.path).resolveSibling("LAWS.bend").toString))

  override def begin(
      snapshot: BendCheckSnapshot,
      subscribe: (() => Unit) => (() => Unit),
      isCurrent: () => Boolean
  ): Option[BendCheckReservation] =
    beginWithOrigin(
      snapshot,
      subscribe,
      isCurrent,
      BendCheckOrigin.Explicit,
      None
    )

  private def beginWithOrigin(
      snapshot: BendCheckSnapshot,
      subscribe: (() => Unit) => (() => Unit),
      isCurrent: () => Boolean,
      origin: BendCheckOrigin,
      backgroundToken: Option[Long]
  ): Option[BendCheckReservation] =
    val deadline = System.nanoTime() + 5_000_000_000L
    var finished = false
    while !finished do
      val transition = applyEvent(
        BeginRequested(snapshot, origin, backgroundToken)
      )
      transition.decision match
        case Started(reservation) =>
          val reserved = synchronized {
            val stillReserved =
              !policyState.disposed && policyState.active.exists(worker =>
                worker.reservation == reservation &&
                  worker.phase == BendCheckWorkerPhase.Reserved
              )
            if stillReserved then
              currentChecks(reservation.generation) = isCurrent
            stillReserved
          }
          perform(
            transition.actions,
            if reserved then Some((snapshot.root, subscribe)) else None
          )
          return Option.when(reserved)(reservation)
        case WaitingForWorker(generation) =>
          perform(transition.actions)
          val released = synchronized {
            while policyState.active.exists(_.generation == generation) &&
              !policyState.disposed && System.nanoTime() < deadline
            do wait(50L)
            !policyState.disposed && !policyState.active.exists(
              _.generation == generation
            )
          }
          if !released then
            val abandoned = applyEvent(ExplicitWaitAbandoned)
            perform(abandoned.actions)
            finished = true
          // Once the obsolete worker exits, repeat the request so the policy
          // grants the manual action the sole slot before background work.
        case _ => finished = true
    None

  override def requestBackground(
      root: FileId
  ): Option[BendBackgroundCheckTicket] =
    val transition = applyEvent(BackgroundRequested(root))
    perform(transition.actions)
    transition.state.roots
      .get(root)
      .flatMap(_.background)
      .map(intent =>
        BendBackgroundCheckTicket(root, intent.token, intent.attempt)
      )

  override def backgroundScheduleCurrent(root: FileId, token: Long): Boolean =
    synchronized {
      !policyState.disposed && policyState.backgroundEnabled &&
      policyState.roots
        .get(root)
        .flatMap(_.background)
        .exists(intent =>
          intent.token == token && intent.phase == BendBackgroundPhase.Scheduled
        )
    }

  override def backgroundScheduleFailed(root: FileId, token: Long): Unit =
    val transition = applyEvent(BackgroundScheduleFailed(root, token))
    perform(transition.actions)

  override def backgroundTimerFired(
      root: FileId,
      token: Long
  ): Option[BendBackgroundCheckTicket] =
    val transition = applyEvent(BackgroundTimerFired(root, token))
    perform(transition.actions)
    transition.decision match
      case BackgroundReady(current, attempt) =>
        Some(BendBackgroundCheckTicket(root, current, attempt))
      case _ => None

  override def backgroundCurrent(ticket: BendBackgroundCheckTicket): Boolean =
    synchronized {
      policyState.backgroundEnabled && !policyState.disposed &&
      policyState.roots
        .get(ticket.root)
        .flatMap(_.background)
        .exists(intent =>
          intent.token == ticket.token && (intent.phase == BendBackgroundPhase.Preparing ||
            intent.phase == BendBackgroundPhase.InFlight)
        )
    }

  override def beginBackground(
      ticket: BendBackgroundCheckTicket,
      snapshot: BendCheckSnapshot,
      subscribe: (() => Unit) => (() => Unit),
      isCurrent: () => Boolean
  ): Option[BendCheckReservation] =
    beginWithOrigin(
      snapshot,
      subscribe,
      () => backgroundCurrent(ticket) && isCurrent(),
      BendCheckOrigin.Background,
      Some(ticket.token)
    )

  override def backgroundAttemptFailed(
      ticket: BendBackgroundCheckTicket
  ): Unit =
    val transition = applyEvent(
      BackgroundAttemptFailed(ticket.root, ticket.token)
    )
    perform(transition.actions)

  override def backgroundStaleObserved(root: FileId): Unit =
    val transition = applyEvent(BackgroundStaleObserved(root))
    perform(transition.actions)

  override def backgroundConfigurationChanged(enabled: Boolean): Unit =
    val transition = applyEvent(BackgroundConfigurationChanged(enabled))
    perform(transition.actions)

  override def backgroundSchedulerDisposed(): Unit =
    val transition = applyEvent(BackgroundSchedulerDisposed)
    perform(transition.actions)

  override def backgroundAffectedRoots(source: FileId): Set[FileId] =
    synchronized {
      policyState.roots.toList.collect {
        case (root, rootState)
            if root == source ||
              rootDependsOn(rootState, source) =>
          root
      }.toSet ++ policyState.active.filter(_.root == source).map(_.root)
    }

  override def cancel(root: FileId): Unit =
    val result = applyEvent(CancelRequested(root))
    perform(result.actions)

  override def configurationChanged(): Unit =
    val result = applyEvent(ConfigurationInvalidated)
    perform(result.actions)

  private def workerCurrent(reservation: BendCheckReservation): Boolean =
    synchronized {
      !policyState.disposed && !canceledWorkers.contains(
        reservation.generation
      ) &&
      policyState.active.exists(worker =>
        worker.reservation == reservation &&
          worker.phase == BendCheckWorkerPhase.Running
      ) &&
      policyState.roots
        .get(reservation.root)
        .exists(_.generation == reservation.generation)
    }

  override def check(
      snapshot: BendCheckSnapshot,
      reservation: BendCheckReservation,
      canceled: () => Boolean
  ): Option[BendCheckResult] =
    val root = reservation.root
    val started = applyEvent(WorkerStarted(reservation, snapshot))
    if started.decision != Continue then None
    else
      val generation = reservation.generation
      val isCurrent = synchronized { currentChecks.get(generation) }
      def noLongerCurrent: Boolean =
        canceled() || !workerCurrent(reservation) ||
          isCurrent.forall(check => !check())
      try
        if noLongerCurrent then None
        else
          val checked = backend.check(snapshot, () => noLongerCurrent)
          val inputsCurrent = graphCurrent(snapshot)
          val sourceCurrent = isCurrent.exists(_())
          val workerCanceled = canceled() || !workerCurrent(reservation)
          val selection = ApplicationManager.getApplication
            .getService(classOf[BendToolchainSettings])
            .selection
          val externalCurrent = checked.key.externalStamp == BendExternalInputs
            .stamp(checked.key.executable, checked.key.basePath)
          val facts = BendCheckFinishFacts(
            sourceCurrent,
            inputsCurrent,
            selection.configurationRevision == snapshot.toolchain.configurationRevision &&
              selection.executable == snapshot.toolchain.executable,
            externalCurrent,
            workerCanceled
          )
          val result = applyEvent(
            WorkerFinished(reservation, snapshot, checked, facts)
          )
          val retry =
            if result.decision == Discarded && sourceCurrent && !workerCanceled
            then
              synchronized {
                policyState.active
                  .filter(_.reservation == reservation)
                  .flatMap(_.backgroundToken)
              }
                .map(token => applyEvent(BackgroundAttemptFailed(root, token)))
            else None
          perform(result.actions ++ retry.toVector.flatMap(_.actions))
          result.decision match
            case Published(value) => Some(value)
            case _                => None
      finally
        val exited = applyEvent(WorkerExited(reservation))
        perform(exited.actions)

  override def result(root: FileId): Option[BendCheckResult] =
    val (stored, captured, generation) = synchronized {
      policyState.roots
        .get(root)
        .map(rootState =>
          (rootState.result, rootState.snapshot, rootState.generation)
        )
        .getOrElse((None, None, 0L))
    }
    val graphChanged = stored.exists(_.fresh) && captured.exists(snapshot =>
      !graphCurrent(snapshot)
    )
    val externalCurrent = stored.forall(result =>
      result.key.externalStamp ==
        BendExternalInputs.stamp(result.key.executable, result.key.basePath)
    )
    if stored.exists(_.fresh) && (graphChanged || !externalCurrent) then
      val reason = if graphChanged then
        BendCheckInvalidationReason.DependencyChanged
      else BendCheckInvalidationReason.ExternalInputChanged
      val invalidated = captured
        .map(snapshot =>
          SnapshotInvalidated(root, generation, snapshot, reason)
        )
        .getOrElse(InputsInvalidated(Set(root), reason))
      val transition = applyEvent(invalidated)
      perform(transition.actions.filterNot(_ == RefreshUi))
      if transition.actions.contains(RefreshUi) then refreshUiBeforeReturning()
    synchronized { policyState.roots.get(root).flatMap(_.result) }

  override def resultsFor(source: FileId): List[BendCheckResult] =
    // External annotators run under an IDE read action. Do not traverse disk here;
    // document and VFS events stale root-owned results.
    synchronized { policyState.roots.values.flatMap(_.result).toList }
      .filter(checked =>
        checked.key.root == source || checked.sources.exists(_.id == source)
      )

  private def graphCurrent(snapshot: BendCheckSnapshot): Boolean =
    snapshot.graph match
      case None        => true
      case Some(graph) =>
        val catalog = project.getService(classOf[BendSourceCatalog])
        def read(path: String): Option[BendSourceRecord] = catalog.source(path)
        val dependencies =
          graph.files.filterNot(_.source.id == snapshot.root).forall { file =>
            read(file.source.path).exists(current =>
              current.id == file.source.id && current.revision == file.source.revision &&
                current.text == file.source.text
            )
          }
        val observed = graph.edges
          .filter(_.target.isEmpty)
          .forall(edge => read(edge.requestedPath).isEmpty)
        val laws =
          if Path.of(snapshot.path).getFileName.toString == "PROOF.bend" then
            val current = read(
              Path.of(snapshot.path).resolveSibling("LAWS.bend").toString
            )
            (snapshot.siblingLaws, current) match
              case (None, None)                => true
              case (Some(before), Some(after)) =>
                before.id == after.id && before.revision == after.revision && before.text == after.text
              case _ => false
          else true
        dependencies && observed && laws

  override def busy: Boolean = synchronized { policyState.active.nonEmpty }

  override def status(root: FileId): BendCheckingStatus =
    val enabled = ApplicationManager.getApplication
      .getService(classOf[BendToolchainSettings])
      .selection
      .diagnosticsEnabled
    val (running, checked) = synchronized {
      (
        policyState.active.exists(_.root == root),
        policyState.roots.get(root).flatMap(_.result)
      )
    }
    BendCheckingStatus.of(enabled, running, checked)

  override def dispose(): Unit =
    val result = applyEvent(Disposed)
    perform(result.actions)
