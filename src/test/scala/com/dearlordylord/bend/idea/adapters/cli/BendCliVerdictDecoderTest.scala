package com.dearlordylord.bend.idea.adapters.cli

import com.dearlordylord.bend.idea.adapters.process.BendProcessOutcome
import com.dearlordylord.bend.idea.analysis.model.*
import com.dearlordylord.bend.idea.toolchain.api.{
  BendCompilerInfo,
  BendCompilerInfoResult
}
import org.junit.Assert.*
import org.junit.Test

final class BendCliVerdictDecoderTest:
  private val checkOnlyLine =
    "  bend <file.bend> --check-only check the file and its imports; run nothing"
  private val compilerError =
    "Error:\n- expected : a defined name\n- observed : missing_name\n" +
      "Location: main\n2 | def main() -> U32:\n3>|   missing_name\n4 | "

  @Test def checkOnlyCapabilityRequiresItsOwnHelpLine(): Unit =
    val supported = BendCliVerdictDecoder.capability(
      BendProcessOutcome.Exited(0, s"Bend 2.0: help\r\n$checkOnlyLine\r\n")
    )
    assertTrue(
      supported.isInstanceOf[BendCliVerdictDecoder.Capability.Supported]
    )
    val quoted = BendCliVerdictDecoder.capability(
      BendProcessOutcome.Exited(0, s"Vendor note: $checkOnlyLine\n")
    )
    assertTrue(
      quoted.isInstanceOf[BendCliVerdictDecoder.Capability.Unavailable]
    )
    val unsupported = BendCliVerdictDecoder.capability(
      BendProcessOutcome.Exited(0, "bend <file.bend> runs source only\n")
    )
    assertTrue(
      unsupported.isInstanceOf[BendCliVerdictDecoder.Capability.Unavailable]
    )

  @Test def compilerInfoExtractsVersionOnly(): Unit =
    val oldCompiler = BendCliVerdictDecoder.compilerInfo(
      BendProcessOutcome.Exited(
        0,
        "Bend 2.0.16: check, run, build and publish Bend programs.\n"
      )
    )
    assertEquals(
      BendCompilerInfoResult.Detected(
        BendCompilerInfo(Some("2.0.16"))
      ),
      oldCompiler
    )

    val currentCompiler = BendCliVerdictDecoder.compilerInfo(
      BendProcessOutcome.Exited(
        0,
        s"Bend 2.0.27: help\n$checkOnlyLine\n"
      )
    )
    assertEquals(
      BendCompilerInfoResult.Detected(
        BendCompilerInfo(Some("2.0.27"))
      ),
      currentCompiler
    )

  @Test def onlyWholeDocumentedReportsEstablishSuccessOrReliance(): Unit =
    val complete = BendCliVerdictDecoder.check(
      BendProcessOutcome.Exited(0, "All terms check.\n")
    )
    assertEquals(BendCheckOutcome.Success, complete.outcome)
    assertEquals(BendCompleteness.Complete, complete.completeness)
    assertEquals(BendReliance.None, complete.reliance)

    val relied = BendCliVerdictDecoder.check(
      BendProcessOutcome.Exited(
        0,
        "All terms check, but 1 def relies on unsafe or foreign code:\n- main\n"
      )
    )
    assertEquals(BendCheckOutcome.Success, relied.outcome)
    assertEquals(BendCompleteness.Complete, relied.completeness)
    assertEquals(BendReliance.UnsafeOrForeign, relied.reliance)

    val pluralReliance = BendCliVerdictDecoder.check(
      BendProcessOutcome.Exited(
        0,
        "All terms check, but 2 defs rely on unsafe or foreign code:\n- first\n- second\n"
      )
    )
    assertEquals(BendReliance.UnsafeOrForeign, pluralReliance.reliance)

    val inconsistentCount = BendCliVerdictDecoder.check(
      BendProcessOutcome.Exited(
        0,
        "All terms check, but 2 defs rely on unsafe or foreign code:\n- main\n"
      )
    )
    assertUnknown(inconsistentCount)
    val extraOutput = BendCliVerdictDecoder.check(
      BendProcessOutcome.Exited(0, "Warning: partial log\nAll terms check.\n")
    )
    assertUnknown(extraOutput)

  @Test def onlyCompleteTodoAndCompilerErrorReportsEstablishFailures(): Unit =
    val incomplete = BendCliVerdictDecoder.check(
      BendProcessOutcome.Exited(
        1,
        "Error: 1 TODO found.\nThe code is incomplete, and not a valid proof yet.\n"
      )
    )
    assertEquals(BendCheckOutcome.Failed, incomplete.outcome)
    assertEquals(BendCompleteness.Incomplete, incomplete.completeness)
    assertEquals(BendReliance.Unknown, incomplete.reliance)
    assertFalse(incomplete.sourceLocationAllowed)

    val pluralIncomplete = BendCliVerdictDecoder.check(
      BendProcessOutcome.Exited(
        1,
        "Error: 2 TODOs found.\nThe code is incomplete, and not a valid proof yet.\n"
      )
    )
    assertEquals(BendCheckOutcome.Failed, pluralIncomplete.outcome)
    assertEquals(BendCompleteness.Incomplete, pluralIncomplete.completeness)

    val error =
      BendCliVerdictDecoder.check(BendProcessOutcome.Exited(1, compilerError))
    assertEquals(BendCheckOutcome.Failed, error.outcome)
    assertEquals(BendCompleteness.Unknown, error.completeness)
    assertEquals(BendReliance.Unknown, error.reliance)
    assertTrue(error.sourceLocationAllowed)

    val underlinedError = BendCliVerdictDecoder.check(
      BendProcessOutcome.Exited(
        1,
        compilerError.replace(
          "3>|   missing_name\n4 | ",
          "3>|   missing_name\n  |   ^^^^^^^^^^^^\n4 | "
        )
      )
    )
    assertEquals(BendCheckOutcome.Failed, underlinedError.outcome)
    assertTrue(underlinedError.sourceLocationAllowed)

    val misplacedUnderline = BendCliVerdictDecoder.check(
      BendProcessOutcome.Exited(
        1,
        compilerError.replace(
          "3>|   missing_name\n4 | ",
          "3>|   missing_name\n4 | \n  |   ^^^^^^^^^^^^"
        )
      )
    )
    assertFalse(misplacedUnderline.sourceLocationAllowed)

    val changedErrorTail = BendCliVerdictDecoder.check(
      BendProcessOutcome.Exited(
        1,
        "Error:\n- expected : a defined name\n- observed : missing_name\n" +
          "Location: main\n3>|   missing_name\nUnexpected wrapper footer\n"
      )
    )
    assertEquals(BendCheckOutcome.Failed, changedErrorTail.outcome)
    assertFalse(changedErrorTail.sourceLocationAllowed)

    val changedTodo = BendCliVerdictDecoder.check(
      BendProcessOutcome.Exited(
        1,
        "Error: 1 TODO found.\nTODOs may be okay in this compiler\n"
      )
    )
    assertUnknown(changedTodo)
    val unfamiliarError = BendCliVerdictDecoder.check(
      BendProcessOutcome.Exited(
        1,
        "Error: generic wrapper failure\n3>|   missing_name\n"
      )
    )
    assertUnknown(unfamiliarError)

  @Test def processLimitsAndCancellationCannotCarryVerdictFacts(): Unit =
    val timeout = BendCliVerdictDecoder.check(
      BendProcessOutcome.TimedOut("All terms check.")
    )
    assertEquals(BendCheckOutcome.TimedOut, timeout.outcome)
    assertEquals(BendCompleteness.Unknown, timeout.completeness)
    assertEquals(BendReliance.Unknown, timeout.reliance)

    List(
      BendProcessOutcome.Canceled("All terms check."),
      BendProcessOutcome.OutputLimit("All terms check."),
      BendProcessOutcome.StartFailed("could not start")
    )
      .foreach { process =>
        val decoded = BendCliVerdictDecoder.check(process)
        assertUnknown(decoded)
      }

    assertTrue(
      BendCliVerdictDecoder
        .capability(BendProcessOutcome.TimedOut(""))
        .isInstanceOf[BendCliVerdictDecoder.Capability.TimedOut]
    )
    assertTrue(
      BendCliVerdictDecoder
        .capability(BendProcessOutcome.Canceled(""))
        .isInstanceOf[BendCliVerdictDecoder.Capability.Canceled]
    )
    assertTrue(
      BendCliVerdictDecoder
        .capability(BendProcessOutcome.OutputLimit(""))
        .isInstanceOf[BendCliVerdictDecoder.Capability.OutputLimit]
    )

  private def assertUnknown(decoded: BendCliVerdictDecoder.Check): Unit =
    assertEquals(BendCheckOutcome.Unavailable, decoded.outcome)
    assertEquals(BendCompleteness.Unknown, decoded.completeness)
    assertEquals(BendReliance.Unknown, decoded.reliance)
    assertFalse(decoded.sourceLocationAllowed)
