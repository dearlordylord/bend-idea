package com.dearlordylord.bend.idea.features.editing

import com.intellij.lang.folding.{FoldingBuilderEx, LanguageFolding}
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.codeInsight.folding.CodeFoldingManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.*

final class BendFoldingTest extends BasePlatformTestCase:
  private def foldRanges(source: String): (String, Set[(Int, Int)]) =
    val file = myFixture.configureByText("folds.bend", source)
    val text = myFixture.getEditor.getDocument.getText
    val builder = LanguageFolding.INSTANCE
      .forLanguage(file.getLanguage)
      .asInstanceOf[FoldingBuilderEx]
    val ranges = builder
      .buildFoldRegions(file, myFixture.getEditor.getDocument, false)
      .map(descriptor =>
        (descriptor.getRange.getStartOffset, descriptor.getRange.getEndOffset)
      )
      .toSet
    (text, ranges)

  def testDeclarationCaseDoAndMultilineExpressionBoundaries(): Unit =
    val (text, ranges) = foldRanges(
      "def f(x: Nat) -> Nat:\n  match x:\n    case Zero{}:\n      0\n    case Succ{prev}:\n      do IO<Unit>:\n        IO.print(\n          \"value\"\n        )\ndef next(): 1\n"
    )
    val firstBody =
      text.indexOf("def f(x: Nat) -> Nat:") + "def f(x: Nat) -> Nat:".length
    val matchBlock = text.indexOf("match x:") + "match x:".length
    val zeroCase = text.indexOf("case Zero{}:") + "case Zero{}:".length
    val succCase = text.indexOf("case Succ{prev}:") + "case Succ{prev}:".length
    val doBlock = text.indexOf("do IO<Unit>:") + "do IO<Unit>:".length
    val call = text.indexOf("IO.print(") + "IO.print(".length
    val close = text.indexOf("\n        )") + "\n        ".length
    assertTrue(ranges.contains((firstBody, close + 1)))
    assertTrue(ranges.contains((matchBlock, close + 1)))
    assertTrue(ranges.contains((zeroCase, text.indexOf("\n    case Succ"))))
    assertTrue(ranges.contains((succCase, close + 1)))
    assertTrue(ranges.contains((doBlock, close + 1)))
    assertTrue(ranges.contains((call, close)))

  def testIncompleteDeclarationKeepsNeighboringFoldAndIgnoresLiteralKeywords()
      : Unit =
    val (text, ranges) = foldRanges(
      "def broken():\n  IO.print(\n    1\ndef good():\n  \"case Fake:\"\n  # do IO<Unit>:\n  2\n"
    )
    val goodBody = text.indexOf("def good():") + "def good():".length
    assertTrue(ranges.exists(_._1 == goodBody))
    assertFalse(ranges.exists(range => range._1 == text.indexOf("case Fake:")))
    assertFalse(ranges.exists(range => range._1 == text.indexOf("do IO<Unit>")))
    assertFalse(
      ranges.exists(range =>
        range._1 == text.indexOf("IO.print(") + "IO.print(".length
      )
    )

  def testCollapseActionUsesRegisteredDeclarationFold(): Unit =
    myFixture.configureByText("action.bend", "def f():\n  1\ndef g(): 2\n")
    CodeFoldingManager
      .getInstance(getProject)
      .updateFoldRegions(myFixture.getEditor)
    myFixture.performEditorAction(IdeActions.ACTION_COLLAPSE_ALL_REGIONS)
    val editor = myFixture.getEditor
    val text = editor.getDocument.getText
    val afterHeader = text.indexOf("def f():") + "def f():".length
    val regions = editor.getFoldingModel.getAllFoldRegions.toList
    assertTrue(
      regions
        .map(region =>
          s"${region.getStartOffset}-${region.getEndOffset}:${region.isExpanded}"
        )
        .mkString(","),
      regions.exists(region =>
        region.getStartOffset == afterHeader && !region.isExpanded
      )
    )
