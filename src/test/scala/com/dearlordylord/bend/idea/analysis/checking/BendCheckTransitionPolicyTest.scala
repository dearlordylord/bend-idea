package com.dearlordylord.bend.idea.analysis.checking

import com.dearlordylord.bend.idea.analysis.checking.BendCheckAction.*
import com.dearlordylord.bend.idea.analysis.checking.BendCheckDecision.*
import com.dearlordylord.bend.idea.analysis.checking.BendCheckEvent.*
import com.dearlordylord.bend.idea.analysis.model.*
import com.dearlordylord.bend.idea.model.FileId
import com.dearlordylord.bend.idea.toolchain.api.BendToolchainSelection
import org.junit.Assert.*
import org.junit.Test

final class BendCheckTransitionPolicyTest:
  private val toolchain = BendToolchainSelection(
    "/bin/bend",
    "/lib/base.bend",
    "/cache",
    diagnosticsEnabled = true,
    configurationRevision = 7L
  )

  private def snapshot(path: String, revision: Long = 1L): BendCheckSnapshot =
    val root = new FileId(path, canonical = true)
    BendCheckSnapshot(
      root,
      "/" + path,
      "def main() -> Type:\n  Type\n",
      revision,
      toolchain
    )

  private def result(
      snapshot: BendCheckSnapshot,
      details: String,
      completeness: BendCompleteness = BendCompleteness.Complete,
      reliance: BendReliance = BendReliance.None
  ): BendCheckResult =
    BendCheckResult(
      BendAnalysisKey
        .from(snapshot)
        .copy(
          inputFingerprint = "captured-graph",
          externalStamp = "external-inputs",
          basePath = snapshot.selectedBasePath
        ),
      BendCheckOutcome.Success,
      completeness,
      reliance,
      List(BendDiagnostic(details, BendLocation.RootOnly)),
      details
    )

  private val currentFacts = BendCheckFinishFacts(
    sourceCurrent = true,
    graphCurrent = true,
    toolchainCurrent = true,
    externalInputsCurrent = true,
    canceled = false
  )

  private def transition(
      state: BendCheckState,
      event: BendCheckEvent
  ): BendCheckTransition =
    BendCheckTransitionPolicy.transition(state, event)

  private def begin(
      state: BendCheckState,
      snapshot: BendCheckSnapshot,
      origin: BendCheckOrigin = BendCheckOrigin.Explicit
  ): (BendCheckState, Long) =
    val requested = transition(state, BeginRequested(snapshot, origin))
    val generation = requested.decision match
      case Started(reservation) => reservation.generation
      case other                =>
        throw new AssertionError(
          "Expected worker reservation, received " + other
        )
    (requested.state, generation)

  private def beginBackground(
      state: BendCheckState,
      snapshot: BendCheckSnapshot
  ): (BendCheckState, Long, Long) =
    val requested = transition(state, BackgroundRequested(snapshot.root))
    val token = requested.state.roots(snapshot.root).background.get.token
    val due =
      transition(requested.state, BackgroundTimerFired(snapshot.root, token))
    assertEquals(BackgroundReady(token, 0), due.decision)
    val reserved = transition(
      due.state,
      BeginRequested(snapshot, BendCheckOrigin.Background, Some(token))
    )
    val generation = reserved.decision match
      case Started(reservation) => reservation.generation
      case other                =>
        throw new AssertionError(
          "Expected background reservation, received " + other
        )
    (reserved.state, generation, token)

  private def running(
      state: BendCheckState,
      snapshot: BendCheckSnapshot,
      generation: Long
  ): BendCheckState =
    val active = state.active
      .filter(worker =>
        worker.root == snapshot.root &&
          worker.generation == generation
      )
      .getOrElse(
        throw new AssertionError("Expected reserved worker " + generation)
      )
    val started = transition(state, WorkerStarted(active.reservation, snapshot))
    assertEquals(Continue, started.decision)
    started.state

  @Test def invalidationAdvancesGenerationAndRejectsTheOldFinish(): Unit =
    val captured = snapshot("root-a.bend")
    val (reserved, firstGeneration) = begin(BendCheckState(), captured)
    val started = running(reserved, captured, firstGeneration)
    val firstResult = result(
      captured,
      "kept diagnostic",
      BendCompleteness.Incomplete,
      BendReliance.UnsafeOrForeign
    )
    val firstFinish = transition(
      started,
      WorkerFinished(
        captured.root,
        firstGeneration,
        captured,
        firstResult,
        currentFacts
      )
    )
    assertEquals(Published(firstResult), firstFinish.decision)
    val firstExit = transition(
      firstFinish.state,
      WorkerExited(captured.root, firstGeneration)
    )
    val (secondReservation, secondGeneration) =
      begin(firstExit.state, captured.copy(sourceRevision = 2L))
    val secondSnapshot = captured.copy(sourceRevision = 2L)
    val secondStarted =
      running(secondReservation, secondSnapshot, secondGeneration)
    val otherGraph = secondSnapshot.copy(inputFingerprint = "different graph")
    val mismatched = transition(
      secondStarted,
      WorkerFinished(
        captured.root,
        secondGeneration,
        otherGraph,
        result(secondSnapshot, "wrong graph"),
        currentFacts
      )
    )
    assertEquals(
      "A result must retain the exact captured graph snapshot",
      Discarded,
      mismatched.decision
    )

    val invalidated = transition(
      mismatched.state,
      InputsInvalidated(
        Set(captured.root),
        BendCheckInvalidationReason.SourceChanged
      )
    )
    assertEquals(Vector(CancelWorker(secondGeneration)), invalidated.actions)
    val stale = invalidated.state.roots(captured.root).result.get
    assertFalse(stale.fresh)
    assertEquals("kept diagnostic", stale.diagnostics.head.message)
    assertEquals(BendCheckOutcome.Success, stale.outcome)
    assertEquals(BendCompleteness.Incomplete, stale.completeness)
    assertEquals(BendReliance.UnsafeOrForeign, stale.reliance)

    val obsolete = result(captured.copy(sourceRevision = 2L), "obsolete")
    val rejected = transition(
      invalidated.state,
      WorkerFinished(
        captured.root,
        secondGeneration,
        captured.copy(sourceRevision = 2L),
        obsolete,
        currentFacts
      )
    )
    assertEquals(Discarded, rejected.decision)
    assertEquals(Some(stale), rejected.state.roots(captured.root).result)
    assertEquals(
      BendCheckOutcome.Success,
      rejected.state.roots(captured.root).result.get.outcome
    )
    assertEquals(
      BendCompleteness.Incomplete,
      rejected.state.roots(captured.root).result.get.completeness
    )
    assertEquals(
      BendReliance.UnsafeOrForeign,
      rejected.state.roots(captured.root).result.get.reliance
    )

  @Test def finishRejectsResultFromAnotherGraphOrBase(): Unit =
    val captured = snapshot("root-a.bend").copy(
      text = "import Base\ndef main() -> Type:\n  Type\n",
      inputFingerprint = "current-graph"
    )
    val (reserved, generation) = begin(BendCheckState(), captured)
    val started = running(reserved, captured, generation)

    val otherGraph = captured.copy(inputFingerprint = "different-graph")
    val graphResult = result(otherGraph, "wrong graph")
    val graphMismatch = transition(
      started,
      WorkerFinished(
        captured.root,
        generation,
        captured,
        graphResult,
        currentFacts
      )
    )
    assertEquals(
      "The result key must retain its captured graph provenance",
      Discarded,
      graphMismatch.decision
    )

    val otherBase = captured.copy(toolchain =
      captured.toolchain.copy(baseSource = "/other/base.bend")
    )
    val baseResult = result(otherBase, "wrong Base")
    val baseMismatch = transition(
      started,
      WorkerFinished(
        captured.root,
        generation,
        captured,
        baseResult,
        currentFacts
      )
    )
    assertEquals(
      "The result key must retain its selected Base identity",
      Discarded,
      baseMismatch.decision
    )

    val currentBaseResult = result(captured, "foreign Base path")
    val foreignBaseKey = currentBaseResult.copy(key =
      currentBaseResult.key.copy(basePath = Some("/other/base.bend"))
    )
    val basePathMismatch = transition(
      started,
      WorkerFinished(
        captured.root,
        generation,
        captured,
        foreignBaseKey,
        currentFacts
      )
    )
    assertEquals(
      "The backend Base path must match the selected Base",
      Discarded,
      basePathMismatch.decision
    )

  @Test def lateFinishAndExitCannotReplaceANewerGeneration(): Unit =
    val captured = snapshot("root-a.bend")
    val (firstReservation, firstGeneration) = begin(BendCheckState(), captured)
    val firstStarted = running(firstReservation, captured, firstGeneration)
    val canceled = transition(firstStarted, CancelRequested(captured.root))
    assertEquals(
      Vector(CancelWorker(firstGeneration), RefreshUi),
      canceled.actions
    )
    val released =
      transition(canceled.state, WorkerExited(captured.root, firstGeneration))
    assertTrue(released.actions.contains(SlotReleased(firstGeneration)))

    val (secondReservation, secondGeneration) = begin(released.state, captured)
    val secondStarted = running(secondReservation, captured, secondGeneration)
    val lateFinish = transition(
      secondStarted,
      WorkerFinished(
        captured.root,
        firstGeneration,
        captured,
        result(captured, "late"),
        currentFacts
      )
    )
    assertEquals(Discarded, lateFinish.decision)
    val lateExit =
      transition(lateFinish.state, WorkerExited(captured.root, firstGeneration))
    assertEquals(
      Some(secondGeneration),
      lateExit.state.active.map(_.generation)
    )
    assertEquals(
      Some(secondGeneration),
      lateExit.state.roots.get(captured.root).map(_.generation)
    )

  @Test def lateFreshnessObservationCannotInvalidateANewerRequest(): Unit =
    val captured = snapshot("root-a.bend")
    val (firstReservation, firstGeneration) = begin(BendCheckState(), captured)
    val firstFinish = transition(
      running(firstReservation, captured, firstGeneration),
      WorkerFinished(
        captured.root,
        firstGeneration,
        captured,
        result(captured, "first"),
        currentFacts
      )
    )
    val firstState = transition(
      firstFinish.state,
      WorkerExited(captured.root, firstGeneration)
    ).state
    val (newer, newerGeneration) =
      begin(firstState, captured.copy(sourceRevision = 2L))

    val lateObservation = transition(
      newer,
      SnapshotInvalidated(
        captured.root,
        firstGeneration,
        captured,
        BendCheckInvalidationReason.ExternalInputChanged
      )
    )
    assertEquals(Continue, lateObservation.decision)
    assertEquals(
      Some(newerGeneration),
      lateObservation.state.active.map(_.generation)
    )
    assertEquals(
      Some(newerGeneration),
      lateObservation.state.roots.get(captured.root).map(_.generation)
    )

  @Test def backgroundTokensFenceStaleCaptureAndBoundRetries(): Unit =
    val captured = snapshot("background-retry.bend")
    val requested =
      transition(BendCheckState(), BackgroundRequested(captured.root))
    val oldToken = requested.state.roots(captured.root).background.get.token
    val replaced =
      transition(requested.state, BackgroundRequested(captured.root))
    val token = replaced.state.roots(captured.root).background.get.token
    assertTrue(token > oldToken)
    assertTrue(
      replaced.actions.contains(CancelBackgroundTimer(captured.root, oldToken))
    )

    val staleTimer =
      transition(replaced.state, BackgroundTimerFired(captured.root, oldToken))
    assertEquals(replaced.state, staleTimer.state)
    assertEquals(Continue, staleTimer.decision)
    val staleCapture = transition(
      staleTimer.state,
      BeginRequested(captured, BendCheckOrigin.Background, Some(oldToken))
    )
    assertEquals(RejectedBusy, staleCapture.decision)

    val firstReady =
      transition(replaced.state, BackgroundTimerFired(captured.root, token))
    assertEquals(BackgroundReady(token, 0), firstReady.decision)
    val firstReservation = transition(
      firstReady.state,
      BeginRequested(captured, BendCheckOrigin.Background, Some(token))
    )
    val firstGeneration =
      firstReservation.decision.asInstanceOf[Started].reservation.generation
    val firstFailed = transition(
      running(firstReservation.state, captured, firstGeneration),
      BackgroundAttemptFailed(captured.root, token)
    )
    assertEquals(
      1,
      firstFailed.state.roots(captured.root).background.get.attempt
    )
    val firstExit = transition(
      firstFailed.state,
      WorkerExited(
        BendCheckReservation(
          captured.root,
          firstGeneration,
          BendCheckOrigin.Background,
          Some(token)
        )
      )
    )
    assertTrue(
      firstExit.actions.contains(
        ScheduleBackground(
          captured.root,
          token,
          BendCheckTransitionPolicy.BackgroundDebounceMillis
        )
      )
    )

    val secondReady =
      transition(firstExit.state, BackgroundTimerFired(captured.root, token))
    assertEquals(BackgroundReady(token, 1), secondReady.decision)
    val secondReservation = transition(
      secondReady.state,
      BeginRequested(captured, BendCheckOrigin.Background, Some(token))
    )
    val secondGeneration =
      secondReservation.decision.asInstanceOf[Started].reservation.generation
    val secondFailed = transition(
      running(secondReservation.state, captured, secondGeneration),
      BackgroundAttemptFailed(captured.root, token)
    )
    assertEquals(
      2,
      secondFailed.state.roots(captured.root).background.get.attempt
    )
    val secondExit = transition(
      secondFailed.state,
      WorkerExited(
        BendCheckReservation(
          captured.root,
          secondGeneration,
          BendCheckOrigin.Background,
          Some(token)
        )
      )
    )

    val thirdReady =
      transition(secondExit.state, BackgroundTimerFired(captured.root, token))
    assertEquals(BackgroundReady(token, 2), thirdReady.decision)
    val thirdReservation = transition(
      thirdReady.state,
      BeginRequested(captured, BendCheckOrigin.Background, Some(token))
    )
    val thirdGeneration =
      thirdReservation.decision.asInstanceOf[Started].reservation.generation
    val thirdFailed = transition(
      running(thirdReservation.state, captured, thirdGeneration),
      BackgroundAttemptFailed(captured.root, token)
    )
    assertTrue(thirdFailed.state.roots(captured.root).background.isEmpty)
    val thirdExit = transition(
      thirdFailed.state,
      WorkerExited(
        BendCheckReservation(
          captured.root,
          thirdGeneration,
          BendCheckOrigin.Background,
          Some(token)
        )
      )
    )
    assertFalse(thirdExit.actions.exists(_.isInstanceOf[ScheduleBackground]))

  @Test def backgroundScheduleInstallationFailureUsesBoundedPolicyRetry()
      : Unit =
    val captured = snapshot("background-schedule-failure.bend")
    val first = transition(BendCheckState(), BackgroundRequested(captured.root))
    val token = first.state.roots(captured.root).background.get.token

    val retried =
      transition(first.state, BackgroundScheduleFailed(captured.root, token))
    assertEquals(1, retried.state.roots(captured.root).background.get.attempt)
    assertTrue(
      retried.actions.contains(CancelBackgroundTimer(captured.root, token))
    )
    assertTrue(
      retried.actions.contains(
        ScheduleBackground(
          captured.root,
          token,
          BendCheckTransitionPolicy.BackgroundDebounceMillis
        )
      )
    )

    val secondFailure =
      transition(retried.state, BackgroundScheduleFailed(captured.root, token))
    assertEquals(
      2,
      secondFailure.state.roots(captured.root).background.get.attempt
    )
    assertTrue(
      secondFailure.actions.contains(
        ScheduleBackground(
          captured.root,
          token,
          BendCheckTransitionPolicy.BackgroundDebounceMillis
        )
      )
    )

    val exhausted = transition(
      secondFailure.state,
      BackgroundScheduleFailed(captured.root, token)
    )
    assertTrue(exhausted.state.roots(captured.root).background.isEmpty)
    assertTrue(
      exhausted.actions.contains(CancelBackgroundTimer(captured.root, token))
    )
    assertFalse(exhausted.actions.exists(_.isInstanceOf[ScheduleBackground]))

  @Test def rootEvictionCancelsTimerAndDropsAdapterHandle(): Unit =
    var state = BendCheckState()
    var oldest = Option.empty[(FileId, Long)]
    (0 until BendCheckTransitionPolicy.MaxTrackedRoots).foreach { index =>
      val root = snapshot(s"root-$index.bend").root
      val requested = transition(state, BackgroundRequested(root))
      if index == 0 then
        oldest = Some(root -> requested.state.roots(root).background.get.token)
      state = requested.state
    }
    val newcomer = snapshot("root-new.bend").root
    val inserted = transition(state, BackgroundRequested(newcomer))
    val (evicted, token) = oldest.get
    assertFalse(inserted.state.roots.contains(evicted))
    assertTrue(inserted.actions.contains(CancelBackgroundTimer(evicted, token)))
    assertTrue(inserted.actions.contains(UnsubscribeRoot(evicted)))
    assertTrue(inserted.actions.contains(DropBackgroundRoot(evicted)))
    assertTrue(inserted.state.roots.contains(newcomer))

  @Test def explicitRequestPreemptsBackgroundBeforeCaptureAndKeepsItPending()
      : Unit =
    val background = snapshot("reserved-background.bend")
    val manual = snapshot("reserved-manual.bend")
    val request =
      transition(BendCheckState(), BackgroundRequested(background.root))
    val token = request.state.roots(background.root).background.get.token
    val due =
      transition(request.state, BackgroundTimerFired(background.root, token))
    val reserved = transition(
      due.state,
      BeginRequested(background, BendCheckOrigin.Background, Some(token))
    )
    val backgroundGeneration = reserved.state.active.get.generation
    assertEquals(BendCheckWorkerPhase.Reserved, reserved.state.active.get.phase)

    val preempted = transition(
      reserved.state,
      BeginRequested(manual, BendCheckOrigin.Explicit)
    )
    val manualGeneration =
      preempted.decision.asInstanceOf[Started].reservation.generation
    assertEquals(BendCheckOrigin.Explicit, preempted.state.active.get.origin)
    assertTrue(preempted.actions.contains(CancelWorker(backgroundGeneration)))
    assertTrue(preempted.actions.contains(SlotReleased(backgroundGeneration)))
    assertEquals(
      BendBackgroundPhase.Ready,
      preempted.state.roots(background.root).background.get.phase
    )
    assertEquals(
      Discarded,
      transition(
        preempted.state,
        WorkerStarted(
          BendCheckReservation(
            background.root,
            backgroundGeneration,
            BendCheckOrigin.Background,
            Some(token)
          ),
          background
        )
      ).decision
    )
    assertEquals(
      Some(manualGeneration),
      preempted.state.active.map(_.generation)
    )

  @Test def sameRootPreemptionRejectsCapturedBackgroundReservationAndKeepsManualSlot()
      : Unit =
    val backgroundCapture = snapshot("same-root-preemption.bend")
    val manualCapture = backgroundCapture.copy(
      text = "def main() -> Type:\n  Type\n",
      sourceRevision = 2L
    )
    val request =
      transition(BendCheckState(), BackgroundRequested(backgroundCapture.root))
    val token = request.state.roots(backgroundCapture.root).background.get.token
    val due = transition(
      request.state,
      BackgroundTimerFired(backgroundCapture.root, token)
    )
    val background = transition(
      due.state,
      BeginRequested(backgroundCapture, BendCheckOrigin.Background, Some(token))
    )
    val backgroundReservation =
      background.decision.asInstanceOf[Started].reservation

    // The background adapter has captured its snapshot but has not dispatched the check.
    val manual = transition(
      background.state,
      BeginRequested(manualCapture, BendCheckOrigin.Explicit)
    )
    val manualReservation = manual.decision.asInstanceOf[Started].reservation
    assertEquals(BendCheckOrigin.Explicit, manualReservation.origin)
    assertNotEquals(backgroundReservation, manualReservation)

    val staleDispatch = transition(
      manual.state,
      WorkerStarted(backgroundReservation, backgroundCapture)
    )
    assertEquals(Discarded, staleDispatch.decision)
    assertEquals(
      Some(manualReservation),
      staleDispatch.state.active.map(_.reservation)
    )
    assertEquals(
      Some(manualCapture),
      staleDispatch.state.roots(backgroundCapture.root).pending.map(_.snapshot)
    )
    val staleExit =
      transition(staleDispatch.state, WorkerExited(backgroundReservation))
    assertEquals(
      Some(manualReservation),
      staleExit.state.active.map(_.reservation)
    )
    assertFalse(
      staleExit.actions.contains(SlotReleased(manualReservation.generation))
    )
    val wrongIdentity = manualReservation.copy(
      origin = BendCheckOrigin.Background,
      backgroundToken = Some(token)
    )
    val mismatchedExit =
      transition(staleExit.state, WorkerExited(wrongIdentity))
    assertEquals(staleExit.state, mismatchedExit.state)
    assertTrue(mismatchedExit.actions.isEmpty)

    val manualStart = transition(
      staleExit.state,
      WorkerStarted(manualReservation, manualCapture)
    )
    assertEquals(Continue, manualStart.decision)
    val manualResult = result(manualCapture, "manual snapshot completed")
    val manualFinish = transition(
      manualStart.state,
      WorkerFinished(
        manualReservation,
        manualCapture,
        manualResult,
        currentFacts
      )
    )
    assertEquals(Published(manualResult), manualFinish.decision)
    val manualExit =
      transition(manualFinish.state, WorkerExited(manualReservation))
    assertEquals(None, manualExit.state.active)
    assertEquals(
      Some(manualResult),
      manualExit.state.roots(backgroundCapture.root).result
    )

  @Test def explicitRequestPreemptsBackgroundAndDisposalRejectsLatePublication()
      : Unit =
    val backgroundRoot = snapshot("background.bend")
    val manualRoot = snapshot("manual.bend")
    val (backgroundReservation, backgroundGeneration, backgroundToken) =
      beginBackground(BendCheckState(), backgroundRoot)
    val backgroundStarted =
      running(backgroundReservation, backgroundRoot, backgroundGeneration)
    val waiting = transition(
      backgroundStarted,
      BeginRequested(manualRoot, BendCheckOrigin.Explicit)
    )
    assertEquals(WaitingForWorker(backgroundGeneration), waiting.decision)
    assertEquals(Vector(CancelWorker(backgroundGeneration)), waiting.actions)
    val released = transition(
      waiting.state,
      WorkerExited(
        BendCheckReservation(
          backgroundRoot.root,
          backgroundGeneration,
          BendCheckOrigin.Background,
          Some(backgroundToken)
        )
      )
    )
    val (manualReservation, manualGeneration) =
      begin(released.state, manualRoot)
    val manualStarted = running(manualReservation, manualRoot, manualGeneration)

    val disposed = transition(manualStarted, Disposed)
    assertTrue(disposed.state.disposed)
    assertTrue(disposed.state.roots.isEmpty)
    assertTrue(disposed.actions.contains(CancelWorker(manualGeneration)))
    assertTrue(disposed.actions.contains(SlotReleased(manualGeneration)))
    assertTrue(disposed.actions.contains(UnsubscribeRoot(backgroundRoot.root)))
    assertTrue(disposed.actions.contains(UnsubscribeRoot(manualRoot.root)))
    assertTrue(
      disposed.actions.contains(DropBackgroundRoot(backgroundRoot.root))
    )
    assertTrue(disposed.actions.contains(DropBackgroundRoot(manualRoot.root)))
    assertTrue(
      disposed.actions.contains(CancelBackgroundTimer(backgroundRoot.root, 1L))
    )
    val lateFinish = transition(
      disposed.state,
      WorkerFinished(
        manualRoot.root,
        manualGeneration,
        manualRoot,
        result(manualRoot, "too late"),
        currentFacts
      )
    )
    assertEquals(RejectedDisposed, lateFinish.decision)
    val lateExit = transition(
      lateFinish.state,
      WorkerExited(manualRoot.root, manualGeneration)
    )
    assertTrue(lateExit.state.disposed)
    assertTrue(lateExit.state.roots.isEmpty)

  @Test def invalidatingOneRootKeepsAnotherRootsResultAndDiagnostics(): Unit =
    val first = snapshot("first.bend")
    val second = snapshot("second.bend")
    val (state1, generation1) = begin(BendCheckState(), first)
    val firstFinished = transition(
      running(state1, first, generation1),
      WorkerFinished(
        first.root,
        generation1,
        first,
        result(first, "first diagnostic"),
        currentFacts
      )
    )
    val afterFirst = transition(
      firstFinished.state,
      WorkerExited(first.root, generation1)
    ).state
    val (state2, generation2) = begin(afterFirst, second)
    val secondFinished = transition(
      running(state2, second, generation2),
      WorkerFinished(
        second.root,
        generation2,
        second,
        result(second, "second diagnostic"),
        currentFacts
      )
    )
    val bothStored = transition(
      secondFinished.state,
      WorkerExited(second.root, generation2)
    ).state

    val invalidated = transition(
      bothStored,
      InputsInvalidated(
        Set(first.root),
        BendCheckInvalidationReason.SourceChanged
      )
    )
    assertFalse(invalidated.state.roots(first.root).result.get.fresh)
    assertTrue(invalidated.state.roots(second.root).result.get.fresh)
    assertEquals(
      "second diagnostic",
      invalidated.state.roots(second.root).result.get.diagnostics.head.message
    )
