package com.dearlordylord.bend.idea.features.formatting

import com.dearlordylord.bend.idea.adapters.cli.RealBendCompilerFixture
import com.dearlordylord.bend.idea.adapters.cli.BendCliCheckBackend
import com.dearlordylord.bend.idea.analysis.model.{
  BendCheckOutcome,
  BendCheckSnapshot
}
import com.dearlordylord.bend.idea.model.FileId
import com.dearlordylord.bend.idea.toolchain.api.BendToolchainSelection
import com.dearlordylord.bend.idea.symbols.api.BendSourceSymbols
import com.dearlordylord.bend.idea.syntax.lexer.BendLexer
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.application.options.CodeStyle
import com.intellij.psi.TokenType
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.*
import java.nio.file.Files

final class BendFormattingTest extends BasePlatformTestCase:
  private def reformat(source: String): String =
    myFixture.configureByText("format.bend", source)
    myFixture.performEditorAction(IdeActions.ACTION_EDITOR_REFORMAT)
    myFixture.getEditor.getDocument.getText

  def testCommaSpacingInHeadersAndCallsIsIdempotent(): Unit =
    val expected = "def main(x: U32, y: U32) -> U32:\n  U32.add(x, y)\n"
    assertEquals(
      expected,
      reformat("def main(x: U32 ,y: U32) -> U32:\n  U32.add(x ,y)\n")
    )
    myFixture.performEditorAction(IdeActions.ACTION_EDITOR_REFORMAT)
    assertEquals(expected, myFixture.getEditor.getDocument.getText)

  def testReformatPreservesSurfaceTokensAndDeclarationKinds(): Unit =
    val source =
      "type Pair is Data:\n  Pair{left: U32,right: U32}\ndef main(x: U32,y: U32) -> U32:\n  U32.add(x,y)\n"
    val file = myFixture.configureByText("surface.bend", source)
    def shape: List[(String, String)] = BendSourceSymbols
      .declarations(file)
      .map(symbol => (symbol.category.toString, symbol.name))
    def tokens: List[(String, String)] =
      val lexer = new BendLexer()
      val text = file.getText
      lexer.start(text)
      val result = List.newBuilder[(String, String)]
      while lexer.getTokenType != null do
        if lexer.getTokenType != TokenType.WHITE_SPACE then
          result += ((
            lexer.getTokenType.toString,
            text.substring(lexer.getTokenStart, lexer.getTokenEnd)
          ))
        lexer.advance()
      result.result()
    val beforeShape = shape
    val beforeTokens = tokens
    myFixture.performEditorAction(IdeActions.ACTION_EDITOR_REFORMAT)
    assertEquals(beforeShape, shape)
    assertEquals(beforeTokens, tokens)

  def testSingleLineBodyIndentIsNormalized(): Unit =
    val expected = "def main() -> U32:\n  1\n"
    assertEquals(expected, reformat("def main() -> U32:\n    1\n"))

  def testConfiguredFourSpaceAndTabReformat(): Unit =
    val file =
      myFixture.configureByText("styled.bend", "def main() -> U32:\n  1\n")
    val options =
      CodeStyle.getSettings(getProject).getIndentOptions(file.getFileType)
    val originalSize = options.INDENT_SIZE
    val originalTab = options.TAB_SIZE
    val originalUseTabs = options.USE_TAB_CHARACTER
    try
      options.INDENT_SIZE = 4
      options.TAB_SIZE = 4
      myFixture.performEditorAction(IdeActions.ACTION_EDITOR_REFORMAT)
      assertEquals(
        "def main() -> U32:\n    1\n",
        myFixture.getEditor.getDocument.getText
      )
      options.USE_TAB_CHARACTER = true
      myFixture.performEditorAction(IdeActions.ACTION_EDITOR_REFORMAT)
      assertEquals(
        "def main() -> U32:\n\t1\n",
        myFixture.getEditor.getDocument.getText
      )
    finally
      options.INDENT_SIZE = originalSize
      options.TAB_SIZE = originalTab
      options.USE_TAB_CHARACTER = originalUseTabs

  def testSensitiveGapsAndLineBreaksStayIntact(): Unit =
    val source =
      "# a,b\ndef main():\n  pair(1,\n    2)\n  A<B<C>>\n  x{==}y\n  &1foo\n  \"a,b\"\n"
    assertEquals(source, reformat(source))

  def testIncompleteSourceKeepsNonCommaWhitespace(): Unit =
    val source = "def unfinished(a,b\n  value :   List<U32>\n# note\n"
    assertEquals(source, reformat(source))
    assertEquals("def unfinished(a,b)\n", reformat("def unfinished(a,b)\n"))

  def testPinnedCompilerAcceptsBeforeAndAfterReformat(): Unit =
    val compiler = RealBendCompilerFixture.inputs
    val directory = Files.createTempDirectory("bend-format-check-")
    try
      val executable = directory.resolve("bend")
      val _ = compiler.writeLauncher(executable)
      val original = directory.resolve("main.bend")
      val source =
        "import Base\ndef main(x: U32,y: U32) -> U32:\n    U32.add(x,y)\n"
      Files.writeString(original, source)
      val toolchain = BendToolchainSelection(
        executable.toString,
        compiler.base.toString,
        "",
        true,
        1L
      )
      val backend = new BendCliCheckBackend(directory)
      def outcome(text: String): BendCheckOutcome =
        backend
          .check(
            BendCheckSnapshot(
              new FileId(original.toString, false),
              original.toString,
              text,
              1L,
              toolchain
            )
          )
          .outcome
      val before = outcome(source)
      val formatted = reformat(source)
      assertEquals(BendCheckOutcome.Success, before)
      assertEquals(
        "import Base\ndef main(x: U32, y: U32) -> U32:\n  U32.add(x, y)\n",
        formatted
      )
      assertEquals(before, outcome(formatted))
      assertEquals(source, Files.readString(original))
    finally
      val paths = Files.walk(directory)
      try
        paths
          .sorted(java.util.Comparator.reverseOrder())
          .forEach(p => {
            val _ = Files.deleteIfExists(p)
          })
      finally paths.close()
