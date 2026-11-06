package com.dearlordylord.bend.idea.features.checking

import com.dearlordylord.bend.idea.analysis.model.*
import org.junit.Assert.*
import org.junit.Test

final class BendLintPresentationTest:
  @Test def skippedCoverageAndUnavailableSetupAreVisibleSeparately(): Unit =
    val partial = BendLintResult(
      BendLintOutcome.Partial,
      Nil,
      "One rule did not run.",
      skipped = List(
        BendLintSkipped(
          None,
          "unentered",
          "Select a proof entry.",
          List("unused"),
          Some("PROOF.bend")
        )
      )
    )
    val shown = BendCheckPresentation.lintDetails(partial)
    assertTrue(shown, shown.contains("Lint: Partial"))
    assertTrue(shown, shown.contains("unentered: Select a proof entry."))
    val missing = BendCheckPresentation.lintDetails(
      BendLintResult(
        BendLintOutcome.Unavailable,
        Nil,
        "Install tools in Bend settings."
      )
    )
    assertTrue(missing, missing.contains("Lint: Unavailable"))
    assertTrue(missing, missing.contains("Install tools in Bend settings."))
