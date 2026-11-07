package com.dearlordylord.bend.idea.features.checking

import com.dearlordylord.bend.idea.analysis.model.*
import com.dearlordylord.bend.idea.model.FileId
import org.junit.Assert.*
import org.junit.Test

final class BendLintPresentationTest:
  @Test def completedFindingsShowTheirMessageAndSource(): Unit =
    val lint = BendLintResult(
      BendLintOutcome.Completed,
      List(
        BendLintFinding(
          "layout/case",
          BendLintSeverity.Warning,
          "Rename BadMain to bad_main.",
          FileId("/project/example.bend", true),
          BendTextRange(25, 32),
          None
        )
      ),
      "1 finding(s)."
    )
    val shown = BendCheckPresentation.lintDetails(lint)
    assertTrue(shown, shown.contains("Warning [layout/case]"))
    assertTrue(shown, shown.contains("Rename BadMain to bad_main."))
    assertTrue(shown, shown.contains("/project/example.bend"))
    assertTrue(shown, shown.contains("UTF-16 offsets [25, 32)"))

  @Test def findingLocationsUseCapturedSourceWithCrLfAndUnicode(): Unit =
    val id = FileId("/project/dependency.bend", true)
    val text = "import Base\r\n# 😀\r\ndef BadMain() -> U32:\r\n  1\r\n"
    val start = text.indexOf("BadMain")
    val lint = BendLintResult(
      BendLintOutcome.Completed,
      List(
        BendLintFinding(
          "layout/case",
          BendLintSeverity.Warning,
          "Rename <BadMain> & keep its type.",
          id,
          BendTextRange(start, start + "BadMain".length),
          None
        )
      ),
      "1 finding(s)."
    )
    val source = BendCheckedSource(id, id.value, text, 12L, "dependency")
    val shown = BendCheckPresentation.lintDetails(lint, List(source))
    assertTrue(shown, shown.contains("/project/dependency.bend:3:5"))
    assertTrue(shown, shown.contains("Rename <BadMain> & keep its type."))

  @Test def longSourcePathsCannotHideTheFindingExplanation(): Unit =
    val lint = BendLintResult(
      BendLintOutcome.Completed,
      List(
        BendLintFinding(
          "layout/case",
          BendLintSeverity.Warning,
          "Rename BadMain to bad_main.",
          FileId("/project/" + "nested/" * 150 + "example.bend", true),
          BendTextRange(25, 32),
          None
        )
      ),
      "1 finding(s)."
    )
    val shown = BendCheckPresentation.lintDetails(lint)
    assertTrue(shown, shown.contains("Rename BadMain to bad_main."))
    assertTrue(shown, shown.contains("example.bend"))
    assertTrue(shown, shown.contains("UTF-16 offsets [25, 32)"))

  @Test def findingSummaryIsBoundedAndDisclosesOmittedFindings(): Unit =
    val lint = BendLintResult(
      BendLintOutcome.Completed,
      (1 to 9).map { index =>
        BendLintFinding(
          "layout/case",
          BendLintSeverity.Warning,
          s"Finding $index.",
          FileId("/project/example.bend", true),
          BendTextRange(0, 1),
          None
        )
      }.toList,
      "Large tool output. " * 1000
    )
    val shown = BendCheckPresentation.lintDetails(lint)
    assertTrue(shown, shown.contains("Finding 1."))
    assertTrue(shown, shown.contains("Finding 8."))
    assertFalse(shown, shown.contains("Finding 9."))
    assertTrue(shown, shown.contains("1 more finding(s)."))
    assertTrue(shown.length.toString, shown.length <= 8192)

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
