package com.dearlordylord.bend.idea.features.editing

import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.codeInsight.highlighting.BraceMatchingUtil
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.*

final class BendDelimiterTest extends BasePlatformTestCase:
  private def editing(source: String): Unit =
    myFixture.configureByText("pairs.bend", source)
    ()

  def testOrdinaryPairsOvertypingAndBackspace(): Unit =
    editing("def f(): <caret>")
    myFixture.`type`("(")
    assertEquals("def f(): ()", myFixture.getEditor.getDocument.getText)
    assertEquals(10, myFixture.getEditor.getCaretModel.getOffset)
    myFixture.`type`(")")
    assertEquals("def f(): ()", myFixture.getEditor.getDocument.getText)
    myFixture.getEditor.getCaretModel.moveToOffset(10)
    myFixture.performEditorAction(IdeActions.ACTION_EDITOR_BACKSPACE)
    assertEquals("def f(): ", myFixture.getEditor.getDocument.getText)

  def testOrdinaryPairAfterExistingDeclarations(): Unit =
    editing(
      "import Base\n\ntype EditorPair is Data:\n  MakePair{left: U32, right: U32}\n\n" +
        "def main() -> U32:\n  3\n\ndef f(): <caret>"
    )
    myFixture.`type`("(")
    assertTrue(myFixture.getEditor.getDocument.getText.endsWith("def f(): ()"))

  def testOrdinaryPairBeforeNextDeclaration(): Unit =
    editing("def f(): <caret>\ndef next(): 1\n")
    myFixture.`type`("(")
    assertEquals(
      "def f(): ()\ndef next(): 1\n",
      myFixture.getEditor.getDocument.getText
    )
    myFixture.`type`(")")
    assertEquals(
      "def f(): ()\ndef next(): 1\n",
      myFixture.getEditor.getDocument.getText
    )
    myFixture.getEditor.getCaretModel.moveToOffset("def f(): (".length)
    myFixture.performEditorAction(IdeActions.ACTION_EDITOR_BACKSPACE)
    assertEquals(
      "def f(): \ndef next(): 1\n",
      myFixture.getEditor.getDocument.getText
    )

  def testBracesAndBracketsPair(): Unit =
    editing("def f(): <caret>")
    myFixture.`type`("{")
    assertEquals("def f(): {}", myFixture.getEditor.getDocument.getText)
    myFixture.`type`("[")
    assertEquals("def f(): {[]}", myFixture.getEditor.getDocument.getText)

  def testQuotesAndComments(): Unit =
    editing("def f(): <caret>")
    myFixture.`type`("\"")
    assertEquals("def f(): \"\"", myFixture.getEditor.getDocument.getText)
    myFixture.`type`("a")
    myFixture.`type`("\"")
    assertEquals("def f(): \"a\"", myFixture.getEditor.getDocument.getText)
    editing("# <caret>")
    myFixture.`type`("\"")
    assertEquals("# \"", myFixture.getEditor.getDocument.getText)

  def testTypeAnglesButNotComparison(): Unit =
    editing("type Box<caret>")
    myFixture.`type`("<")
    assertEquals("type Box<>", myFixture.getEditor.getDocument.getText)
    myFixture.`type`(">")
    assertEquals("type Box<>", myFixture.getEditor.getDocument.getText)
    editing("def compare(x: Nat, y: Nat): x <caret>")
    myFixture.`type`("<")
    assertEquals(
      "def compare(x: Nat, y: Nat): x <",
      myFixture.getEditor.getDocument.getText
    )

  def testQuoteAndAnglePairedBackspace(): Unit =
    editing("def f(): <caret>")
    myFixture.`type`("\"")
    myFixture.performEditorAction(IdeActions.ACTION_EDITOR_BACKSPACE)
    assertEquals("def f(): ", myFixture.getEditor.getDocument.getText)
    editing("type Box<caret>")
    myFixture.`type`("<")
    myFixture.performEditorAction(IdeActions.ACTION_EDITOR_BACKSPACE)
    assertEquals("type Box", myFixture.getEditor.getDocument.getText)

  def testNestedTypeArgumentsAndLiteralState(): Unit =
    editing("def f(x: List<caret>): x")
    myFixture.`type`("<")
    assertEquals("def f(x: List<>): x", myFixture.getEditor.getDocument.getText)
    editing("def f(): \"open <caret>")
    myFixture.`type`("{")
    assertEquals("def f(): \"open {", myFixture.getEditor.getDocument.getText)
    myFixture.`type`("\"")
    assertEquals("def f(): \"open {\"", myFixture.getEditor.getDocument.getText)

  def testPairInsertionIsUndoable(): Unit =
    editing("def f(): <caret>")
    myFixture.`type`("\"")
    myFixture.performEditorAction(IdeActions.ACTION_UNDO)
    assertEquals("def f(): ", myFixture.getEditor.getDocument.getText)

  def testNestedGenericClosersFollowInsertedPairs(): Unit =
    editing("def f(x: List<caret>): x")
    myFixture.`type`("<")
    myFixture.`type`("Map")
    myFixture.`type`("<")
    myFixture.`type`("Unit")
    myFixture.`type`(">")
    myFixture.`type`(">")
    assertEquals(
      "def f(x: List<Map<Unit>>): x",
      myFixture.getEditor.getDocument.getText
    )

  def testComparisonAndManualAngleAreNotConsumedAsInsertedPairs(): Unit =
    editing("def f(): x <caret>> y")
    myFixture.`type`(">")
    assertEquals("def f(): x >> y", myFixture.getEditor.getDocument.getText)
    editing("type Box<<caret>>")
    myFixture.performEditorAction(IdeActions.ACTION_EDITOR_BACKSPACE)
    assertEquals("type Box>", myFixture.getEditor.getDocument.getText)

  def testEscapedQuoteBeforeExistingQuoteIsInserted(): Unit =
    editing("def f(): \"a\\<caret>\"")
    myFixture.`type`("\"")
    assertEquals("def f(): \"a\\\"\"", myFixture.getEditor.getDocument.getText)

  def testDoTypeArgumentPairsButBodyComparisonDoesNot(): Unit =
    editing("def f():\n  do IO<caret>")
    myFixture.`type`("<")
    assertEquals("def f():\n  do IO<>", myFixture.getEditor.getDocument.getText)
    editing("def f():\n  do IO<Unit>: x <caret>")
    myFixture.`type`("<")
    assertEquals(
      "def f():\n  do IO<Unit>: x <",
      myFixture.getEditor.getDocument.getText
    )

  def testQuotedOrPreviousLineDoDoesNotMakeTypeContext(): Unit =
    editing("def f(): \"do IO\" Map<caret>")
    myFixture.`type`("<")
    assertEquals(
      "def f(): \"do IO\" Map<",
      myFixture.getEditor.getDocument.getText
    )
    editing("def f():\n  do IO\n  Map<caret>")
    myFixture.`type`("<")
    assertEquals(
      "def f():\n  do IO\n  Map<",
      myFixture.getEditor.getDocument.getText
    )

  def testPreviousIncompleteDoDoesNotMatchFollowingAngle(): Unit =
    editing("def f():\n  do IO\n  Map<X>\n")
    val editor = myFixture.getEditor.asInstanceOf[EditorEx]
    val text = editor.getDocument.getText
    val offset = text.indexOf("Map<") + 3
    assertFalse(
      BraceMatchingUtil.isLBraceToken(
        editor.getHighlighter.createIterator(offset),
        text,
        myFixture.getFile.getFileType
      )
    )

  def testTypeAngleMatchingExcludesComparisonsAndShifts(): Unit =
    editing("def f(x: List<List<U32>>) -> U32:\n  x < y >> 1\n# < >\n")
    val editor = myFixture.getEditor.asInstanceOf[EditorEx]
    val text = editor.getDocument.getText
    val typeOpen = text.indexOf("List<") + 4
    val typeClose = text.indexOf(">>")
    val comparison = text.indexOf("x < y") + 2
    val shift = text.lastIndexOf(">>")
    val fileType = myFixture.getFile.getFileType
    def left(offset: Int): Boolean =
      val iterator = editor.getHighlighter.createIterator(offset)
      BraceMatchingUtil.isLBraceToken(iterator, text, fileType)
    def right(offset: Int): Boolean =
      val iterator = editor.getHighlighter.createIterator(offset)
      BraceMatchingUtil.isRBraceToken(iterator, text, fileType)
    assertTrue(left(typeOpen))
    assertTrue(right(typeClose))
    assertTrue(right(typeClose + 1))
    val outer = editor.getHighlighter.createIterator(typeOpen)
    assertTrue(BraceMatchingUtil.matchBrace(text, fileType, outer, true))
    assertEquals(typeClose + 1, outer.getStart)
    assertFalse(left(comparison))
    assertFalse(right(shift))
    assertFalse(right(shift + 1))

  def testConstructorFieldTypeAnglesMatchOutsideDeclarationHeader(): Unit =
    editing(
      "type Box is Data:\n  Box{value: Map<List<U32>, U32>}\ndef f(): Box < other\n"
    )
    val editor = myFixture.getEditor.asInstanceOf[EditorEx]
    val text = editor.getDocument.getText
    val fileType = myFixture.getFile.getFileType
    val fieldOpen = text.indexOf("Map<") + 3
    val nestedOpen = text.indexOf("List<") + 4
    val nestedClose = text.indexOf(">, U32")
    val fieldClose = text.indexOf(">}")
    val comparison = text.indexOf("Box < other") + 4
    def left(offset: Int): Boolean =
      BraceMatchingUtil.isLBraceToken(
        editor.getHighlighter.createIterator(offset),
        text,
        fileType
      )
    def right(offset: Int): Boolean =
      BraceMatchingUtil.isRBraceToken(
        editor.getHighlighter.createIterator(offset),
        text,
        fileType
      )
    assertTrue(left(fieldOpen))
    assertTrue(left(nestedOpen))
    assertTrue(right(nestedClose))
    assertTrue(right(fieldClose))
    assertFalse(left(comparison))

  def testConstructorFieldTypeAnglesPairAtNestedArgument(): Unit =
    editing("type Box is Data:\n  Box{value: Map<U32, List<caret>>}\n")
    myFixture.`type`("<")
    assertEquals(
      "type Box is Data:\n  Box{value: Map<U32, List<>>}\n",
      myFixture.getEditor.getDocument.getText
    )
    myFixture.`type`(">")
    assertEquals(
      "type Box is Data:\n  Box{value: Map<U32, List<>>}\n",
      myFixture.getEditor.getDocument.getText
    )
