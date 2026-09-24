package com.dearlordylord.bend.idea.features.editing

import com.dearlordylord.bend.idea.syntax.lexer.{
  BendColors,
  BendSyntaxHighlighter,
  BendTokens
}
import com.intellij.lang.LanguageCommenters
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.editor.{
  DefaultLanguageHighlighterColors,
  HighlighterColors
}
import com.intellij.openapi.editor.colors.{
  EditorColorsManager,
  EditorColorsScheme
}
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.psi.TokenType
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.PlatformTestUtil
import org.junit.Assert.*
import java.awt.Color

final class BendEditorTest extends BasePlatformTestCase:
  private def scheme(
      name: String,
      background: Color,
      foreground: Color,
      keyword: Color
  ): EditorColorsScheme =
    val copy = EditorColorsManager
      .getInstance()
      .getGlobalScheme
      .clone()
      .asInstanceOf[EditorColorsScheme]
    copy.setName(name)
    val text = copy.getAttributes(HighlighterColors.TEXT).clone()
    text.setBackgroundColor(background)
    text.setForegroundColor(foreground)
    copy.setAttributes(HighlighterColors.TEXT, text)
    val identifier = new TextAttributes()
    identifier.setForegroundColor(foreground)
    copy.setAttributes(DefaultLanguageHighlighterColors.IDENTIFIER, identifier)
    val customKeyword = new TextAttributes()
    customKeyword.setForegroundColor(keyword)
    copy.setAttributes(BendColors.Keyword, customKeyword)
    copy

  private def assertEffectiveEditorColors(
      background: Color,
      foreground: Color,
      keyword: Color
  ): Unit =
    val editor = myFixture.getEditor.asInstanceOf[EditorEx]
    assertEquals(background, editor.getBackgroundColor)
    assertEquals(background, editor.getColorsScheme.getDefaultBackground)
    assertEquals(foreground, editor.getColorsScheme.getDefaultForeground)
    assertEquals(
      foreground,
      editor.getColorsScheme
        .getAttributes(HighlighterColors.TEXT)
        .getForegroundColor
    )
    val plainOffset = editor.getDocument.getText.indexOf("plain")
    assertEquals(
      foreground,
      editor.getHighlighter
        .createIterator(plainOffset)
        .getTextAttributes
        .getForegroundColor
    )
    val keywordOffset = editor.getDocument.getText.indexOf("do")
    assertEquals(
      keyword,
      editor.getHighlighter
        .createIterator(keywordOffset)
        .getTextAttributes
        .getForegroundColor
    )

  def testEffectiveEditorColorsFollowDarkAndLightSchemes(): Unit =
    val manager = EditorColorsManager.getInstance()
    val original = manager.getGlobalScheme
    val dark = scheme(
      "Bend fixture dark",
      new Color(30, 33, 38),
      new Color(220, 224, 230),
      new Color(220, 150, 90)
    )
    val light = scheme(
      "Bend fixture light",
      new Color(250, 248, 244),
      new Color(35, 39, 45),
      new Color(90, 60, 160)
    )
    try
      for (selected, background, foreground, keyword) <- Seq(
          (
            dark,
            new Color(30, 33, 38),
            new Color(220, 224, 230),
            new Color(220, 150, 90)
          ),
          (
            light,
            new Color(250, 248, 244),
            new Color(35, 39, 45),
            new Color(90, 60, 160)
          )
        )
      do
        manager.setGlobalScheme(selected)
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        myFixture.configureByText("ordinary.txt", "ordinary text")
        val ordinary = myFixture.getEditor.asInstanceOf[EditorEx]
        assertEquals(background, ordinary.getBackgroundColor)
        assertEquals(foreground, ordinary.getColorsScheme.getDefaultForeground)
        myFixture.configureByText(
          "scheme.bend",
          "def main(): do IO<Unit>: plain"
        )
        assertEffectiveEditorColors(background, foreground, keyword)
    finally
      manager.setGlobalScheme(original)
      PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

  def testOpenBendEditorUpdatesAfterLiveSchemeSwitch(): Unit =
    val manager = EditorColorsManager.getInstance()
    val original = manager.getGlobalScheme
    val dark = scheme(
      "Bend live dark",
      new Color(24, 28, 34),
      new Color(230, 230, 220),
      new Color(190, 140, 210)
    )
    val light = scheme(
      "Bend live light",
      new Color(255, 253, 249),
      new Color(30, 35, 40),
      new Color(70, 80, 180)
    )
    try
      manager.setGlobalScheme(dark)
      PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
      myFixture.configureByText("live.bend", "def main(): do IO<Unit>: plain")
      val editor = myFixture.getEditor
      val document = editor.getDocument
      assertEffectiveEditorColors(
        new Color(24, 28, 34),
        new Color(230, 230, 220),
        new Color(190, 140, 210)
      )
      manager.setGlobalScheme(light)
      PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
      assertSame(editor, myFixture.getEditor)
      assertSame(document, editor.getDocument)
      assertEffectiveEditorColors(
        new Color(255, 253, 249),
        new Color(30, 35, 40),
        new Color(70, 80, 180)
      )
    finally
      manager.setGlobalScheme(original)
      PlatformTestUtil.dispatchAllEventsInIdeEventQueue()

  def testRegisteredHighlighterAndCommenterInEditor(): Unit =
    val file = myFixture.configureByText(
      "sample.bend",
      "def main(): \"# not comment\"\r\n# actual\r\n"
    )
    val highlighter = SyntaxHighlighterFactory.getSyntaxHighlighter(
      file.getLanguage,
      getProject,
      file.getVirtualFile
    )
    assertTrue(highlighter.isInstanceOf[BendSyntaxHighlighter])
    assertEquals(
      "#",
      LanguageCommenters.INSTANCE
        .forLanguage(file.getLanguage)
        .getLineCommentPrefix
    )
    assertTrue(
      highlighter
        .getTokenHighlights(
          com.dearlordylord.bend.idea.syntax.lexer.BendTokens.Comment
        )
        .contains(BendColors.Comment)
    )

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
    assertEquals(
      "#def first(): 1\n#def second(): 2",
      editor.getDocument.getText
    )

  def testIncrementalEditInsideExistingStringKeepsCommentTextLiteral(): Unit =
    myFixture.configureByText(
      "edit.bend",
      "def x(): \"hello world\"\n# real comment"
    )
    val editor = myFixture.getEditor
    editor.getCaretModel.moveToOffset(
      editor.getDocument.getText.indexOf("world")
    )
    myFixture.`type`("# def ")
    val text = editor.getDocument.getText
    val literalHash = text.indexOf("# def")
    val realHash = text.indexOf("# real")
    val stringColor = editor.getHighlighter
      .createIterator(literalHash)
      .getTextAttributes
      .getForegroundColor
    val adjacentStringColor = editor.getHighlighter
      .createIterator(literalHash + 2)
      .getTextAttributes
      .getForegroundColor
    val commentColor = editor.getHighlighter
      .createIterator(realHash)
      .getTextAttributes
      .getForegroundColor
    assertEquals(adjacentStringColor, stringColor)
    assertNotEquals(commentColor, stringColor)

  def testIncompleteImportAndInvalidNameInEditor(): Unit =
    val file = myFixture.configureByText(
      "incomplete.bend",
      "import\r\ndef okay(as: Nat): do IO<Unit>: as\ndef bad.(): ?TODO"
    )
    val lexer = SyntaxHighlighterFactory
      .getSyntaxHighlighter(file.getLanguage, getProject, file.getVirtualFile)
      .getHighlightingLexer
    val source = myFixture.getEditor.getDocument.getText
    lexer.start(source)
    val found = List.newBuilder[(String, com.intellij.psi.tree.IElementType)]
    while lexer.getTokenType != null do
      found += ((
        source.substring(lexer.getTokenStart, lexer.getTokenEnd),
        lexer.getTokenType
      ))
      lexer.advance()
    val tokens = found.result()
    assertTrue(tokens.contains(("def", BendTokens.DeclarationKeyword)))
    assertTrue(tokens.contains(("okay", BendTokens.FunctionName)))
    assertTrue(tokens.contains(("bad.", TokenType.BAD_CHARACTER)))
    assertEquals(2, tokens.count(_ == (("as", BendTokens.Identifier))))
    assertTrue(tokens.contains(("do", BendTokens.Keyword)))
    assertTrue(tokens.contains(("<", BendTokens.LeftAngle)))

  def testNewDeclarationRecoversAfterUnfinishedPreviousOne(): Unit =
    val file = myFixture.configureByText(
      "recover.bend",
      "def\r\nlaw L: ?TODO\ntype\ndef f(): 1\ndef\n  multiline(): 2"
    )
    val lexer = SyntaxHighlighterFactory
      .getSyntaxHighlighter(file.getLanguage, getProject, file.getVirtualFile)
      .getHighlightingLexer
    val source = myFixture.getEditor.getDocument.getText
    lexer.start(source)
    val found = List.newBuilder[(String, com.intellij.psi.tree.IElementType)]
    while lexer.getTokenType != null do
      found += ((
        source.substring(lexer.getTokenStart, lexer.getTokenEnd),
        lexer.getTokenType
      ))
      lexer.advance()
    val tokens = found.result()
    assertTrue(tokens.contains(("law", BendTokens.DeclarationKeyword)))
    assertTrue(tokens.contains(("def", BendTokens.DeclarationKeyword)))
    assertTrue(tokens.contains(("L", BendTokens.FunctionName)))
    assertTrue(tokens.contains(("f", BendTokens.FunctionName)))
    assertTrue(tokens.contains(("multiline", BendTokens.FunctionName)))
