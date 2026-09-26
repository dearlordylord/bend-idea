package com.dearlordylord.bend.idea.features.proofs

import com.dearlordylord.bend.idea.analysis.model.*
import com.dearlordylord.bend.idea.model.FileId
import org.junit.Assert.*
import org.junit.Test

final class BendProofProgressModelTest:
  private val key = BendAnalysisKey(
    new FileId("/proof/PROOF.bend", canonical = true),
    1L,
    "source",
    1L,
    "/bend",
    "graph",
    BendCheckSnapshotProvenance("graph", "/base")
  )

  @Test def inventorySearchFiltersVisibleRows(): Unit =
    val entries = List(
      BendProofInventoryEntry(
        BendProofInventoryKind.Law,
        "claim",
        "/laws/LAWS.bend",
        4,
        new FileId("/laws/LAWS.bend", canonical = true),
        1L,
        1L
      ),
      BendProofInventoryEntry(
        BendProofInventoryKind.Hole,
        "?TODO",
        "/proof/PROOF.bend",
        40,
        new FileId("/proof/PROOF.bend", canonical = true),
        2L,
        1L
      )
    )
    assertEquals(
      List(entries.head),
      BendProofProgressModel.filtered(entries, "law")
    )
    assertEquals(
      List(entries(1)),
      BendProofProgressModel.filtered(entries, "todo")
    )

  @Test def resultPresentationKeepsFreshnessCompletenessAndRelianceDistinct()
      : Unit =
    assertEquals(
      "Manual checks remain visible when background diagnostics are disabled",
      BendCheckingStatus.Checking,
      BendCheckingStatus.of(enabled = false, running = true, result = None)
    )
    val incomplete = BendCheckResult(
      key,
      BendCheckOutcome.Success,
      BendCompleteness.Incomplete,
      BendReliance.None,
      Nil,
      ""
    )
    assertEquals(
      "Compiler result: incomplete",
      BendProofProgressModel.checkedStatus(
        BendCheckingStatus.Incomplete,
        Some(incomplete)
      )
    )
    assertEquals(
      "TODO rejection is reported as incomplete, not a complete failed proof",
      "Compiler result: incomplete",
      BendProofProgressModel.checkedStatus(
        BendCheckingStatus.Incomplete,
        Some(incomplete.copy(outcome = BendCheckOutcome.Failed))
      )
    )
    val foreign = incomplete.copy(
      outcome = BendCheckOutcome.Success,
      completeness = BendCompleteness.Complete,
      reliance = BendReliance.UnsafeOrForeign
    )
    assertEquals(
      "Compiler success: complete; unsafe or foreign reliance",
      BendProofProgressModel.checkedStatus(
        BendCheckingStatus.Current,
        Some(foreign)
      )
    )
    assertEquals(
      "A running request cannot display its prior success as current",
      "Checking",
      BendProofProgressModel.checkedStatus(
        BendCheckingStatus.Checking,
        Some(foreign)
      )
    )
    assertEquals(
      "Check stale; unsafe or foreign reliance",
      BendProofProgressModel.checkedStatus(
        BendCheckingStatus.Stale,
        Some(foreign.copy(fresh = false))
      )
    )
    assertEquals(
      "Checking",
      BendProofProgressModel.checkedStatus(BendCheckingStatus.Checking, None)
    )
