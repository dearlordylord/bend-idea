package com.dearlordylord.bend.idea.features.editing

import com.dearlordylord.bend.idea.syntax.lexer.{BendColors, BendSyntaxHighlighter, BendTokens}
import com.intellij.lang.LanguageCommenters
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.psi.TokenType
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.*

final class BendEditorTest extends BasePlatformTestCase:
  def testRegisteredHighlighterAndCommenterInEditor(): Unit =
    val file = myFixture.configureByText("sample.bend", "def main(): \"# not comment\"\r\n# actual\r\n")
    val highlighter = SyntaxHighlighterFactory.getSyntaxHighlighter(file.getLanguage, getProject, file.getVirtualFile)
    assertTrue(highlighter.isInstanceOf[BendSyntaxHighlighter])
    assertEquals("#", LanguageCommenters.INSTANCE.forLanguage(file.getLanguage).getLineCommentPrefix)
    assertTrue(highlighter.getTokenHighlights(com.dearlordylord.bend.idea.syntax.lexer.BendTokens.Comment).contains(BendColors.Comment))

  def testToggleHashCommentAtEofWithoutNewlineAndUndo(): Unit =
    myFixture.configureByText("end.bend", "def main(): ?TODO")
    myFixture.getEditor.getCaretModel.moveToOffset(0)
    myFixture.performEditorAction(IdeActions.ACTION_COMMENT_LINE)
    assertEquals("#def main(): ?TODO", myFixture.getEditor.getDocument.getText)
    myFixture.performEditorAction(IdeActions.ACTION_UNDO)
    assertEquals("def main(): ?TODO", myFixture.getEditor.getDocument.getText)
    myFixture.performEditorAction(IdeActions.ACTION_COMMENT_LINE)
    myFixture.performEditorAction(IdeActions.ACTION_COMMENT_LINE)
    assertEquals("def main(): ?TODO", myFixture.getEditor.getDocument.getText)

  def testToggleSelectedCrLfLines(): Unit =
    myFixture.configureByText("lines.bend", "def first(): 1\r\ndef second(): 2")
    val editor = myFixture.getEditor
    editor.getSelectionModel.setSelection(0, editor.getDocument.getTextLength)
    myFixture.performEditorAction(IdeActions.ACTION_COMMENT_LINE)
    // IntelliJ normalizes CRLF to LF in its document before applying the action.
    assertEquals("#def first(): 1\n#def second(): 2", editor.getDocument.getText)

  def testIncrementalEditInsideExistingStringKeepsCommentTextLiteral(): Unit =
    myFixture.configureByText("edit.bend", "def x(): \"hello world\"\n# real comment")
    val editor = myFixture.getEditor
    editor.getCaretModel.moveToOffset(editor.getDocument.getText.indexOf("world"))
    myFixture.`type`("# def ")
    val text = editor.getDocument.getText
    val literalHash = text.indexOf("# def")
    val realHash = text.indexOf("# real")
    val stringColor = editor.getHighlighter.createIterator(literalHash).getTextAttributes.getForegroundColor
    val adjacentStringColor = editor.getHighlighter.createIterator(literalHash + 2).getTextAttributes.getForegroundColor
    val commentColor = editor.getHighlighter.createIterator(realHash).getTextAttributes.getForegroundColor
    assertEquals(adjacentStringColor, stringColor)
    assertNotEquals(commentColor, stringColor)

  def testIncompleteImportAndInvalidNameInEditor(): Unit =
    val file = myFixture.configureByText("incomplete.bend", "import\r\ndef okay(as: Nat): do IO<Unit>: as\ndef bad.(): ?TODO")
    val lexer = SyntaxHighlighterFactory.getSyntaxHighlighter(file.getLanguage, getProject, file.getVirtualFile).getHighlightingLexer
    val source = myFixture.getEditor.getDocument.getText
    lexer.start(source)
    val found = List.newBuilder[(String, com.intellij.psi.tree.IElementType)]
    while lexer.getTokenType != null do
      found += ((source.substring(lexer.getTokenStart, lexer.getTokenEnd), lexer.getTokenType))
      lexer.advance()
    val tokens = found.result()
    assertTrue(tokens.contains(("def", BendTokens.DeclarationKeyword)))
    assertTrue(tokens.contains(("okay", BendTokens.FunctionName)))
    assertTrue(tokens.contains(("bad.", TokenType.BAD_CHARACTER)))
    assertEquals(2, tokens.count(_ == (("as", BendTokens.Identifier))))
    assertTrue(tokens.contains(("do", BendTokens.Keyword)))
    assertTrue(tokens.contains(("<", BendTokens.Operator)))

  def testNewDeclarationRecoversAfterUnfinishedPreviousOne(): Unit =
    val file = myFixture.configureByText("recover.bend", "def\r\nlaw L: ?TODO\ntype\ndef f(): 1\ndef\n  multiline(): 2")
    val lexer = SyntaxHighlighterFactory.getSyntaxHighlighter(file.getLanguage, getProject, file.getVirtualFile).getHighlightingLexer
    val source = myFixture.getEditor.getDocument.getText
    lexer.start(source)
    val found = List.newBuilder[(String, com.intellij.psi.tree.IElementType)]
    while lexer.getTokenType != null do
      found += ((source.substring(lexer.getTokenStart, lexer.getTokenEnd), lexer.getTokenType))
      lexer.advance()
    val tokens = found.result()
    assertTrue(tokens.contains(("law", BendTokens.DeclarationKeyword)))
    assertTrue(tokens.contains(("def", BendTokens.DeclarationKeyword)))
    assertTrue(tokens.contains(("L", BendTokens.FunctionName)))
    assertTrue(tokens.contains(("f", BendTokens.FunctionName)))
    assertTrue(tokens.contains(("multiline", BendTokens.FunctionName)))
