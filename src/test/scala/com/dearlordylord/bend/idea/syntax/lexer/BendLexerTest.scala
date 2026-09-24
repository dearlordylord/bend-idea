package com.dearlordylord.bend.idea.syntax.lexer

import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import org.junit.Assert.*
import org.junit.Test

final class BendLexerTest:
  private case class Token(
      kind: IElementType,
      text: String,
      start: Int,
      end: Int,
      state: Int
  )

  private def tokens(
      source: String,
      offset: Int = 0,
      state: Int = 0
  ): List[Token] =
    val lexer = new BendLexer()
    lexer.start(source, offset, source.length, state)
    val result = List.newBuilder[Token]
    var previous = offset
    while lexer.getTokenType != null do
      assertEquals("token gap", previous, lexer.getTokenStart)
      assertTrue("lexer must advance", lexer.getTokenEnd > previous)
      result += Token(
        lexer.getTokenType,
        source.substring(previous, lexer.getTokenEnd),
        previous,
        lexer.getTokenEnd,
        lexer.getState
      )
      previous = lexer.getTokenEnd
      lexer.advance()
    assertEquals(source.length, previous)
    result.result()

  @Test def coversCrLfAstralAndInvalidInput(): Unit =
    val source = "def f() :\r\n  \"😀\\u{1F600}\\q\" # end\r\n§"
    val found = tokens(source)
    assertTrue(
      found.exists(t =>
        t.kind == BendTokens.StringContent && t.text == "😀" && t.end - t.start == 2
      )
    )
    assertTrue(
      found.exists(t => t.kind == BendTokens.Escape && t.text == "\\u{1F600}")
    )
    assertTrue(
      found.exists(t => t.kind == BendTokens.InvalidEscape && t.text == "\\q")
    )
    assertTrue(
      found.exists(t => t.kind == BendTokens.Comment && t.text == "# end")
    )
    assertTrue(
      found.exists(t => t.kind == TokenType.BAD_CHARACTER && t.text == "§")
    )

  @Test def differentiatesLanguageVocabulary(): Unit =
    val found = tokens(
      "import ./A.bend as A\nlaw L: for x: Nat\ndef f(&2 x: U32): {==} ?TODO\ntype T is Data:\ndo IO<Unit>: f!(~Nat)"
    )
    def contains(kind: IElementType, value: String): Boolean =
      found.exists(t => t.kind == kind && t.text == value)
    assertTrue(contains(BendTokens.ImportPath, "./A.bend"))
    assertTrue(contains(BendTokens.NamespaceName, "A"))
    assertTrue(contains(BendTokens.FunctionName, "L"))
    assertTrue(contains(BendTokens.FunctionName, "f"))
    assertTrue(contains(BendTokens.TypeName, "T"))
    assertTrue(contains(BendTokens.Quantity, "&2"))
    assertTrue(contains(BendTokens.Rewrite, "{==}"))
    assertTrue(contains(BendTokens.Hole, "?TODO"))
    assertTrue(contains(BendTokens.Keyword, "do"))
    assertTrue(contains(BendTokens.LeftAngle, "<"))
    assertTrue(contains(BendTokens.RightAngle, ">"))
    assertTrue(contains(BendTokens.Operator, "~"))

  @Test def unfinishedImportDoesNotColorNextLineAsPath(): Unit =
    val source = "import\r\ndef next(): ?TODO"
    val found = tokens(source)
    assertTrue(
      found.exists(t =>
        t.kind == BendTokens.DeclarationKeyword && t.text == "def"
      )
    )
    assertTrue(
      found.exists(t => t.kind == BendTokens.FunctionName && t.text == "next")
    )
    for boundary <- found do
      assertEquals(
        found.dropWhile(_.start < boundary.start),
        tokens(source, boundary.start, boundary.state)
      )

  @Test def unfinishedDeclarationRecoversAtNextReservedWord(): Unit =
    val source = "def\r\nlaw L: ?TODO\ntype\ndef f(): 1\ndef\n  multiline(): 2"
    val found = tokens(source)
    assertEquals(5, found.count(_.kind == BendTokens.DeclarationKeyword))
    assertTrue(
      found.exists(t => t.kind == BendTokens.FunctionName && t.text == "L")
    )
    assertTrue(
      found.exists(t => t.kind == BendTokens.FunctionName && t.text == "f")
    )
    assertTrue(
      found.exists(t =>
        t.kind == BendTokens.FunctionName && t.text == "multiline"
      )
    )
    for boundary <- found do
      assertEquals(
        found.dropWhile(_.start < boundary.start),
        tokens(source, boundary.start, boundary.state)
      )

  @Test def trailingDotIsInvalidOverWholeLexemeAndAsIsImportOnly(): Unit =
    val source = "def broken.(): 1\ndef valid(as: Nat): as\nimport Base as B"
    val found = tokens(source)
    assertTrue(
      found.exists(t =>
        t.kind == TokenType.BAD_CHARACTER && t.text == "broken."
      )
    )
    assertEquals(
      2,
      found.count(t => t.kind == BendTokens.Identifier && t.text == "as")
    )
    assertTrue(
      found.exists(t => t.kind == BendTokens.Keyword && t.text == "as")
    )
    assertTrue(
      found.exists(t => t.kind == BendTokens.NamespaceName && t.text == "B")
    )

  @Test def restartsFromEveryTokenBoundaryAndAfterLiteralEdit(): Unit =
    val original =
      "def greet() : \"line one\r\nline two 😀 \\n done\" # note\r\ndef next(): ?TODO"
    val all = tokens(original)
    for boundary <- all do
      val resumed = tokens(original, boundary.start, boundary.state)
      assertEquals(all.dropWhile(_.start < boundary.start), resumed)
    val edited = original.replace("line two", "line # def type")
    val editedTokens = tokens(edited)
    assertFalse(
      editedTokens.exists(t =>
        t.kind == BendTokens.Comment && t.text.contains("def type")
      )
    )
    assertTrue(
      editedTokens.exists(t =>
        t.kind == BendTokens.StringContent && t.text.contains("line # def type")
      )
    )

  @Test def unfinishedLiteralsStillConsumeAllText(): Unit =
    val source = "def x(): \"unfinished\r\n# still literal 😀\\"
    val found = tokens(source)
    assertFalse(found.exists(_.kind == BendTokens.Comment))
    assertEquals(BendTokens.InvalidEscape, found.last.kind)
