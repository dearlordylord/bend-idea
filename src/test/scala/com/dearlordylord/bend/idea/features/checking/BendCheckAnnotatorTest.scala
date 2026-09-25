package com.dearlordylord.bend.idea.features.checking

import com.dearlordylord.bend.idea.analysis.model.{
  BendCheckOutcome,
  BendLocation
}
import com.dearlordylord.bend.idea.model.FileId
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.*

final class BendCheckAnnotatorTest extends BasePlatformTestCase:
  def testOnlyFailedChecksBecomeSourceHighlights(): Unit =
    assertTrue(BendCheckAnnotator.shouldHighlight(BendCheckOutcome.Failed))
    assertFalse(BendCheckAnnotator.shouldHighlight(BendCheckOutcome.Success))
    assertFalse(
      BendCheckAnnotator.shouldHighlight(BendCheckOutcome.Unavailable)
    )
    assertFalse(BendCheckAnnotator.shouldHighlight(BendCheckOutcome.TimedOut))

  def testOnlySourceLocatedDiagnosticsBecomeEditorHighlights(): Unit =
    val root = new FileId("/project/PROOF.bend", canonical = true)
    val imported = new FileId("/project/laws.bend", canonical = true)

    assertEquals(
      None,
      BendCheckAnnotator.sourceLine(BendLocation.RootOnly, root, root)
    )
    assertEquals(
      None,
      BendCheckAnnotator.sourceLine(BendLocation.Line(2), root, imported)
    )
    assertEquals(
      Some(2),
      BendCheckAnnotator.sourceLine(
        BendLocation.Line(2),
        root,
        root
      )
    )
    assertEquals(
      Some(4),
      BendCheckAnnotator.sourceLine(
        BendLocation.SourceLine(imported, 4),
        root,
        imported
      )
    )
