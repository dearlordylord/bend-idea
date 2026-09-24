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
  private val toolchain = BendToolchainSelection("/bin/bend", "/lib/base.bend", "/cache",
    diagnosticsEnabled = true, configurationRevision = 7L)

  private def snapshot(path: String, revision: Long = 1L): BendCheckSnapshot =
    val root = new FileId(path, canonical = true)
    BendCheckSnapshot(root, "/" + path, "def main() -> Type:\n  Type\n", revision, toolchain)

  private def result(snapshot: BendCheckSnapshot, details: String,
      completeness: BendCompleteness = BendCompleteness.Complete,
      reliance: BendReliance = BendReliance.None): BendCheckResult =
    BendCheckResult(BendAnalysisKey.from(snapshot).copy(inputFingerprint = "captured-graph",
      externalStamp = "external-inputs"), BendCheckOutcome.Success, completeness, reliance,
      List(BendDiagnostic(details, BendLocation.RootOnly)), details)

  private val currentFacts = BendCheckFinishFacts(sourceCurrent = true, graphCurrent = true,
    toolchainCurrent = true, externalInputsCurrent = true, canceled = false)

  private def transition(state: BendCheckState, event: BendCheckEvent): BendCheckTransition =
    BendCheckTransitionPolicy.transition(state, event)

  private def begin(state: BendCheckState, snapshot: BendCheckSnapshot,
      origin: BendCheckOrigin = BendCheckOrigin.Explicit): (BendCheckState, Long) =
    val requested = transition(state, BeginRequested(snapshot, origin))
    val generation = requested.decision match
      case Started(value) => value
      case other => throw new AssertionError("Expected worker reservation, received " + other)
    (requested.state, generation)

  private def running(state: BendCheckState, snapshot: BendCheckSnapshot,
      generation: Long): BendCheckState =
    val started = transition(state, WorkerStarted(snapshot.root, generation, snapshot))
    assertEquals(Continue, started.decision)
    started.state

  @Test def invalidationAdvancesGenerationAndRejectsTheOldFinish(): Unit =
    val captured = snapshot("root-a.bend")
    val (reserved, firstGeneration) = begin(BendCheckState(), captured)
    val started = running(reserved, captured, firstGeneration)
    val firstResult = result(captured, "kept diagnostic", BendCompleteness.Incomplete,
      BendReliance.UnsafeOrForeign)
    val firstFinish = transition(started,
      WorkerFinished(captured.root, firstGeneration, captured, firstResult, currentFacts))
    assertEquals(Published(firstResult), firstFinish.decision)
    val firstExit = transition(firstFinish.state, WorkerExited(captured.root, firstGeneration))
    val (secondReservation, secondGeneration) = begin(firstExit.state, captured.copy(sourceRevision = 2L))
    val secondSnapshot = captured.copy(sourceRevision = 2L)
    val secondStarted = running(secondReservation, secondSnapshot, secondGeneration)
    val otherGraph = secondSnapshot.copy(inputFingerprint = "different graph")
    val mismatched = transition(secondStarted, WorkerFinished(captured.root, secondGeneration,
      otherGraph, result(secondSnapshot, "wrong graph"), currentFacts))
    assertEquals("A result must retain the exact captured graph snapshot", Discarded,
      mismatched.decision)

    val invalidated = transition(mismatched.state, InputsInvalidated(Set(captured.root),
      BendCheckInvalidationReason.SourceChanged))
    assertEquals(Vector(CancelWorker(secondGeneration)), invalidated.actions)
    val stale = invalidated.state.roots(captured.root).result.get
    assertFalse(stale.fresh)
    assertEquals("kept diagnostic", stale.diagnostics.head.message)
    assertEquals(BendCheckOutcome.Success, stale.outcome)
    assertEquals(BendCompleteness.Incomplete, stale.completeness)
    assertEquals(BendReliance.UnsafeOrForeign, stale.reliance)

    val obsolete = result(captured.copy(sourceRevision = 2L), "obsolete")
    val rejected = transition(invalidated.state,
      WorkerFinished(captured.root, secondGeneration, captured.copy(sourceRevision = 2L),
        obsolete, currentFacts))
    assertEquals(Discarded, rejected.decision)
    assertEquals(Some(stale), rejected.state.roots(captured.root).result)
    assertEquals(BendCheckOutcome.Success, rejected.state.roots(captured.root).result.get.outcome)
    assertEquals(BendCompleteness.Incomplete,
      rejected.state.roots(captured.root).result.get.completeness)
    assertEquals(BendReliance.UnsafeOrForeign,
      rejected.state.roots(captured.root).result.get.reliance)

  @Test def lateFinishAndExitCannotReplaceANewerGeneration(): Unit =
    val captured = snapshot("root-a.bend")
    val (firstReservation, firstGeneration) = begin(BendCheckState(), captured)
    val firstStarted = running(firstReservation, captured, firstGeneration)
    val canceled = transition(firstStarted, CancelRequested(captured.root))
    assertEquals(Vector(CancelWorker(firstGeneration), RefreshUi), canceled.actions)
    val released = transition(canceled.state, WorkerExited(captured.root, firstGeneration))
    assertTrue(released.actions.contains(SlotReleased(firstGeneration)))

    val (secondReservation, secondGeneration) = begin(released.state, captured)
    val secondStarted = running(secondReservation, captured, secondGeneration)
    val lateFinish = transition(secondStarted, WorkerFinished(captured.root, firstGeneration,
      captured, result(captured, "late"), currentFacts))
    assertEquals(Discarded, lateFinish.decision)
    val lateExit = transition(lateFinish.state, WorkerExited(captured.root, firstGeneration))
    assertEquals(Some(secondGeneration), lateExit.state.active.map(_.generation))
    assertEquals(Some(secondGeneration), lateExit.state.roots.get(captured.root).map(_.generation))

  @Test def lateFreshnessObservationCannotInvalidateANewerRequest(): Unit =
    val captured = snapshot("root-a.bend")
    val (firstReservation, firstGeneration) = begin(BendCheckState(), captured)
    val firstFinish = transition(running(firstReservation, captured, firstGeneration),
      WorkerFinished(captured.root, firstGeneration, captured, result(captured, "first"),
        currentFacts))
    val firstState = transition(firstFinish.state,
      WorkerExited(captured.root, firstGeneration)).state
    val (newer, newerGeneration) = begin(firstState, captured.copy(sourceRevision = 2L))

    val lateObservation = transition(newer,
      SnapshotInvalidated(captured.root, firstGeneration, captured,
        BendCheckInvalidationReason.ExternalInputChanged))
    assertEquals(Continue, lateObservation.decision)
    assertEquals(Some(newerGeneration), lateObservation.state.active.map(_.generation))
    assertEquals(Some(newerGeneration),
      lateObservation.state.roots.get(captured.root).map(_.generation))

  @Test def explicitRequestPreemptsBackgroundAndDisposalRejectsLatePublication(): Unit =
    val backgroundRoot = snapshot("background.bend")
    val manualRoot = snapshot("manual.bend")
    val (backgroundReservation, backgroundGeneration) = begin(BendCheckState(), backgroundRoot,
      BendCheckOrigin.Background)
    val backgroundStarted = running(backgroundReservation, backgroundRoot, backgroundGeneration)
    val waiting = transition(backgroundStarted, BeginRequested(manualRoot,
      BendCheckOrigin.Explicit))
    assertEquals(WaitingForWorker(backgroundGeneration), waiting.decision)
    assertEquals(Vector(CancelWorker(backgroundGeneration)), waiting.actions)
    val released = transition(waiting.state,
      WorkerExited(backgroundRoot.root, backgroundGeneration))
    val (manualReservation, manualGeneration) = begin(released.state, manualRoot)
    val manualStarted = running(manualReservation, manualRoot, manualGeneration)

    val disposed = transition(manualStarted, Disposed)
    assertTrue(disposed.state.disposed)
    assertTrue(disposed.state.roots.isEmpty)
    assertEquals(Vector(CancelWorker(manualGeneration), SlotReleased(manualGeneration),
      UnsubscribeRoot(backgroundRoot.root), UnsubscribeRoot(manualRoot.root)), disposed.actions)
    val lateFinish = transition(disposed.state, WorkerFinished(manualRoot.root, manualGeneration,
      manualRoot, result(manualRoot, "too late"), currentFacts))
    assertEquals(RejectedDisposed, lateFinish.decision)
    val lateExit = transition(lateFinish.state, WorkerExited(manualRoot.root, manualGeneration))
    assertTrue(lateExit.state.disposed)
    assertTrue(lateExit.state.roots.isEmpty)

  @Test def invalidatingOneRootKeepsAnotherRootsResultAndDiagnostics(): Unit =
    val first = snapshot("first.bend")
    val second = snapshot("second.bend")
    val (state1, generation1) = begin(BendCheckState(), first)
    val firstFinished = transition(running(state1, first, generation1),
      WorkerFinished(first.root, generation1, first, result(first, "first diagnostic"),
        currentFacts))
    val afterFirst = transition(firstFinished.state, WorkerExited(first.root, generation1)).state
    val (state2, generation2) = begin(afterFirst, second)
    val secondFinished = transition(running(state2, second, generation2),
      WorkerFinished(second.root, generation2, second, result(second, "second diagnostic"),
        currentFacts))
    val bothStored = transition(secondFinished.state, WorkerExited(second.root, generation2)).state

    val invalidated = transition(bothStored, InputsInvalidated(Set(first.root),
      BendCheckInvalidationReason.SourceChanged))
    assertFalse(invalidated.state.roots(first.root).result.get.fresh)
    assertTrue(invalidated.state.roots(second.root).result.get.fresh)
    assertEquals("second diagnostic",
      invalidated.state.roots(second.root).result.get.diagnostics.head.message)
