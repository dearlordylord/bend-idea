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

  def testApprovedNestedWrappingAndSelectiveMultilineReflow(): Unit =
    for (before, after) <- BendWrappingFixtures.exactCases
    do
      assertEquals(after, reformat(before))
      myFixture.performEditorAction(IdeActions.ACTION_EDITOR_REFORMAT)
      assertEquals(after, myFixture.getEditor.getDocument.getText)

  def testEqualityWrappingUsesWholeFileSelectionAndUndo(): Unit =
    val source = BendWrappingFixtures.equalityBefore
    myFixture.configureByText("equality-selection.bend", source)
    myFixture.getEditor.getSelectionModel
      .setSelection(0, source.stripSuffix("\n").length)
    myFixture.performEditorAction(IdeActions.ACTION_EDITOR_REFORMAT)
    assertEquals(
      BendWrappingFixtures.equalityAfter,
      myFixture.getEditor.getDocument.getText
    )
    myFixture.performEditorAction(IdeActions.ACTION_UNDO)
    assertEquals(source, myFixture.getEditor.getDocument.getText)

  def testPartialEqualitySelectionDoesNotInsertLineBreaks(): Unit =
    val prefix = "# Outside the selection.\n"
    val source = prefix + BendWrappingFixtures.equalityBefore
    myFixture.configureByText("equality-partial.bend", source)
    myFixture.getEditor.getSelectionModel
      .setSelection(prefix.length, source.length)
    myFixture.performEditorAction(IdeActions.ACTION_EDITOR_REFORMAT)
    assertEquals(source, myFixture.getEditor.getDocument.getText)

  def testWrappingUsesNormalEditorUndo(): Unit =
    val source = BendWrappingFixtures.actorBefore
    myFixture.configureByText("undo.bend", source)
    myFixture.performEditorAction(IdeActions.ACTION_EDITOR_REFORMAT)
    assertEquals(
      BendWrappingFixtures.actorAfter,
      myFixture.getEditor.getDocument.getText
    )
    myFixture.performEditorAction(IdeActions.ACTION_UNDO)
    assertEquals(source, myFixture.getEditor.getDocument.getText)

  def testSelectedReformatLeavesOtherDefinitionUntouched(): Unit =
    val first = "def first(x: U32,y: U32) -> U32:\n  x\n"
    val second = "def second(x: U32,y: U32) -> U32:\n  y\n"
    myFixture.configureByText("selection.bend", first + second)
    myFixture.getEditor.getSelectionModel.setSelection(0, first.length)
    myFixture.performEditorAction(IdeActions.ACTION_EDITOR_REFORMAT)
    assertEquals(
      first.replace("U32,y", "U32, y") + second,
      myFixture.getEditor.getDocument.getText
    )

  def testWidthOnlyEditorConfigAndNestedOverrides(): Unit =
    val root = Files.createTempDirectory(
      Files.createDirectories(java.nio.file.Path.of(getProject.getBasePath)),
      "wrapping-"
    )
    Files.writeString(
      root.resolve(".editorconfig"),
      "root = true\n[*.bend]\nbend_max_line_length = 28\n"
    )
    def physical(path: java.nio.file.Path, source: String): String =
      Files.writeString(path, source)
      val file = com.intellij.openapi.vfs.LocalFileSystem.getInstance
        .refreshAndFindFileByNioFile(path)
      assertNotNull(file)
      myFixture.configureFromExistingVirtualFile(file)
      myFixture.performEditorAction(IdeActions.ACTION_EDITOR_REFORMAT)
      myFixture.getEditor.getDocument.getText
    assertEquals(
      BendWrappingFixtures.callWrapped,
      physical(root.resolve("parent.bend"), BendWrappingFixtures.callBefore)
    )
    for (width, expected) <- BendWrappingFixtures.widthCases do
      val child = Files.createDirectories(root.resolve("width" + width))
      Files.writeString(
        child.resolve(".editorconfig"),
        s"[*.bend]\nbend_max_line_length = $width\n"
      )
      assertEquals(
        expected,
        physical(child.resolve("child.bend"), BendWrappingFixtures.callBefore)
      )
    val invalid = Files.createDirectories(root.resolve("invalid"))
    Files.writeString(
      invalid.resolve(".editorconfig"),
      "[*.bend]\nbend_max_line_length = invalid\n"
    )
    assertEquals(
      BendWrappingFixtures.callBefore,
      physical(invalid.resolve("child.bend"), BendWrappingFixtures.callBefore)
    )

  def testPartialLongListSelectionDoesNotApplyHalfAWrap(): Unit =
    val source = BendWrappingFixtures.actorBefore
    myFixture.configureByText("partial.bend", source)
    val opener = source.indexOf("T.Cr{")
    myFixture.getEditor.getSelectionModel.setSelection(opener, opener + 15)
    myFixture.performEditorAction(IdeActions.ACTION_EDITOR_REFORMAT)
    assertEquals(source, myFixture.getEditor.getDocument.getText)

  def testConfiguredTabsAndNoFinalNewlineWrapping(): Unit =
    val root = Files.createTempDirectory(
      Files.createDirectories(java.nio.file.Path.of(getProject.getBasePath)),
      "wrapping-"
    )
    Files.writeString(
      root.resolve(".editorconfig"),
      "root = true\n[*.bend]\nindent_style = tab\nindent_size = 4\ntab_width = 4\nbend_max_line_length = 28\n"
    )
    val path = root.resolve("tabs.bend")
    Files.writeString(path, BendWrappingFixtures.tabBefore)
    myFixture.configureFromExistingVirtualFile(
      com.intellij.openapi.vfs.LocalFileSystem.getInstance
        .refreshAndFindFileByNioFile(path)
    )
    myFixture.performEditorAction(IdeActions.ACTION_EDITOR_REFORMAT)
    assertEquals(
      BendWrappingFixtures.tabAfter,
      myFixture.getEditor.getDocument.getText
    )
    myFixture.performEditorAction(IdeActions.ACTION_EDITOR_REFORMAT)
    assertEquals(
      BendWrappingFixtures.tabAfter,
      myFixture.getEditor.getDocument.getText
    )

  def testApprovedLayoutsPreserveNoFinalNewline(): Unit =
    for (before, after) <- BendWrappingFixtures.exactCases do
      assertEquals(after.stripSuffix("\n"), reformat(before.stripSuffix("\n")))
      myFixture.performEditorAction(IdeActions.ACTION_EDITOR_REFORMAT)
      assertEquals(
        after.stripSuffix("\n"),
        myFixture.getEditor.getDocument.getText
      )

  def testFittingMultilineSignatureGroupingStaysIntact(): Unit =
    assertEquals(
      BendWrappingFixtures.multilineSignatureBefore,
      reformat(BendWrappingFixtures.multilineSignatureBefore)
    )

  def testMultilineReflowNestedSiblingsAndUnsafeRequests(): Unit =
    val root = Files.createTempDirectory(
      Files.createDirectories(java.nio.file.Path.of(getProject.getBasePath)),
      "wrapping-reflow-"
    )
    def physical(name: String, source: String, width: Int): String =
      val dir = Files.createDirectories(root.resolve(name))
      Files.writeString(
        dir.resolve(".editorconfig"),
        s"root = true\n[*.bend]\nbend_max_line_length = $width\n"
      )
      val path = dir.resolve("sample.bend")
      Files.writeString(path, source)
      myFixture.configureFromExistingVirtualFile(
        com.intellij.openapi.vfs.LocalFileSystem.getInstance
          .refreshAndFindFileByNioFile(path)
      )
      myFixture.performEditorAction(IdeActions.ACTION_EDITOR_REFORMAT)
      myFixture.getEditor.getDocument.getText
    assertEquals(
      BendWrappingFixtures.multilineSignatureAfter,
      physical("signature", BendWrappingFixtures.multilineSignatureBefore, 20)
    )
    assertEquals(
      BendWrappingFixtures.siblingsAfter,
      physical("siblings", BendWrappingFixtures.siblingsBefore, 30)
    )
    for ((width, before, after), index) <-
        BendWrappingFixtures.boundedCases.zipWithIndex
    do assertEquals(after, physical("bounded" + index, before, width))
    for (source, index) <- BendWrappingFixtures.unsafeCases.zipWithIndex do
      assertEquals(source, physical("unsafe" + index, source, 20))

  def testPhysicalCrLfAndFinalNewlineConventionSurviveEditorReformat(): Unit =
    val root = Files.createTempDirectory(
      Files.createDirectories(java.nio.file.Path.of(getProject.getBasePath)),
      "wrapping-crlf-"
    )
    Files.writeString(
      root.resolve(".editorconfig"),
      "root = true\n[*.bend]\nbend_max_line_length = 100\n"
    )
    val settings = CodeStyle.getSettings(getProject)
    val previousSeparator = settings.LINE_SEPARATOR
    val fixture = myFixture.asInstanceOf[
      com.intellij.testFramework.fixtures.impl.CodeInsightTestFixtureImpl
    ]
    import com.intellij.testFramework.fixtures.impl.CodeInsightTestFixtureImpl.SelectionAndCaretMarkupApplyPolicy
    val manager =
      com.intellij.openapi.fileEditor.FileDocumentManager.getInstance
    val local = com.intellij.openapi.vfs.LocalFileSystem.getInstance
    def detected(file: com.intellij.openapi.vfs.VirtualFile): String =
      com.intellij.openapi.fileEditor.impl.LoadTextUtil
        .detectLineSeparator(file, true)
    // The fixture's default markup loader writes normalized text back to disk,
    // even without Reformat Code. Establish that control before preserving the
    // original physical bytes with its document-only markup policy.
    val control = root.resolve("save-only-control.bend")
    Files.writeString(control, "def control():\r\n  1\r\n")
    val controlFile = local.refreshAndFindFileByNioFile(control)
    assertEquals("\r\n", detected(controlFile))
    myFixture.configureFromExistingVirtualFile(controlFile)
    assertEquals("def control():\n  1\n", Files.readString(control))
    manager.saveDocument(myFixture.getEditor.getDocument)
    assertEquals("def control():\n  1\n", Files.readString(control))
    try
      settings.LINE_SEPARATOR = null
      fixture.setSelectionAndCaretMarkupApplyPolicy(
        SelectionAndCaretMarkupApplyPolicy.UPDATE_DOCUMENT_AND_LEAVE_IT_DIRTY
      )
      for
        ((before, after), index) <- BendWrappingFixtures.exactCases.zipWithIndex
        finalNewline <- List(true, false)
      do
        val source = if finalNewline then before else before.stripSuffix("\n")
        val expected = if finalNewline then after else after.stripSuffix("\n")
        val path = root.resolve(s"crlf-$index-$finalNewline.bend")
        val originalBytes = source.replace("\n", "\r\n")
        Files.writeString(path, originalBytes)
        val file = local.refreshAndFindFileByNioFile(path)
        assertEquals("\r\n", detected(file))
        myFixture.configureFromExistingVirtualFile(file)
        assertEquals(originalBytes, Files.readString(path))
        assertEquals("\r\n", manager.getLineSeparator(file, getProject))
        myFixture.performEditorAction(IdeActions.ACTION_EDITOR_REFORMAT)
        assertEquals(expected, myFixture.getEditor.getDocument.getText)
        assertEquals(
          "\r\n",
          com.intellij.openapi.fileEditor.impl.LoadTextUtil
            .detectLineSeparator(file, false)
        )
        assertEquals("\r\n", manager.getLineSeparator(file, getProject))
        manager.saveDocument(myFixture.getEditor.getDocument)
        assertEquals(expected.replace("\n", "\r\n"), Files.readString(path))
    finally
      settings.LINE_SEPARATOR = previousSeparator
      fixture.setSelectionAndCaretMarkupApplyPolicy(
        SelectionAndCaretMarkupApplyPolicy.UPDATE_FILE_AND_KEEP_DOCUMENT_CLEAN
      )

  def testWrappingSettingsCannotChangeEnterOrBackspace(): Unit =
    val root = Files.createTempDirectory(
      Files.createDirectories(java.nio.file.Path.of(getProject.getBasePath)),
      "wrapping-typing-"
    )
    for (width, index) <- List(
        None,
        Some("1"),
        Some("off"),
        Some("invalid")
      ).zipWithIndex
    do
      val dir = Files.createDirectories(root.resolve("width" + index))
      val property = width.fold("")(value => s"bend_max_line_length = $value\n")
      Files.writeString(
        dir.resolve(".editorconfig"),
        "root = true\n[*.bend]\nindent_style = space\nindent_size = 4\n" + property
      )
      val path = dir.resolve("typing.bend")
      Files.writeString(path, "def f():")
      myFixture.configureFromExistingVirtualFile(
        com.intellij.openapi.vfs.LocalFileSystem.getInstance
          .refreshAndFindFileByNioFile(path)
      )
      myFixture.getEditor.getCaretModel.moveToOffset(
        myFixture.getEditor.getDocument.getTextLength
      )
      myFixture.performEditorAction(IdeActions.ACTION_EDITOR_ENTER)
      assertEquals("def f():\n    ", myFixture.getEditor.getDocument.getText)
      myFixture.performEditorAction(IdeActions.ACTION_EDITOR_BACKSPACE)
      assertEquals("def f():\n", myFixture.getEditor.getDocument.getText)

  def testFullActorSelectionIncludingFinalNewlineWraps(): Unit =
    val source = BendWrappingFixtures.actorBefore
    myFixture.configureByText("actor-selection-all.bend", source)
    myFixture.getEditor.getSelectionModel.setSelection(0, source.length)
    myFixture.performEditorAction(IdeActions.ACTION_EDITOR_REFORMAT)
    assertEquals(
      BendWrappingFixtures.actorAfter,
      myFixture.getEditor.getDocument.getText
    )

  def testFullActorSelectionExcludingFinalNewlineWraps(): Unit =
    val source = BendWrappingFixtures.actorBefore
    myFixture.configureByText("actor-selection-content.bend", source)
    myFixture.getEditor.getSelectionModel
      .setSelection(0, source.stripSuffix("\n").length)
    myFixture.performEditorAction(IdeActions.ACTION_EDITOR_REFORMAT)
    assertEquals(
      BendWrappingFixtures.actorAfter,
      myFixture.getEditor.getDocument.getText
    )

    myFixture.performEditorAction(IdeActions.ACTION_UNDO)
    assertEquals(source, myFixture.getEditor.getDocument.getText)

  def testPhysicalActorWithoutSelectionAtWidth100Wraps(): Unit =
    val root = Files.createTempDirectory(
      Files.createDirectories(java.nio.file.Path.of(getProject.getBasePath)),
      "actor-manual-"
    )
    Files.writeString(
      root.resolve(".editorconfig"),
      "root = true\n[*.bend]\nbend_max_line_length = 100\n"
    )
    val path = root.resolve("actor.bend")
    Files.writeString(path, BendWrappingFixtures.actorBefore)
    myFixture.configureFromExistingVirtualFile(
      com.intellij.openapi.vfs.LocalFileSystem.getInstance
        .refreshAndFindFileByNioFile(path)
    )
    assertFalse(myFixture.getEditor.getSelectionModel.hasSelection)
    myFixture.performEditorAction(IdeActions.ACTION_EDITOR_REFORMAT)
    assertEquals(
      BendWrappingFixtures.actorAfter,
      myFixture.getEditor.getDocument.getText
    )

  def testWholeLogicalActorSelectionPreservesUnselectedBoundaryWhitespace()
      : Unit =
    val prefix = "\n\n"
    val suffix = "\n\n"
    val source = prefix + BendWrappingFixtures.actorBefore + suffix
    myFixture.configureByText("actor-whitespace-boundary.bend", source)
    myFixture.getEditor.getSelectionModel.setSelection(
      prefix.length,
      prefix.length + BendWrappingFixtures.actorBefore.stripSuffix("\n").length
    )
    myFixture.performEditorAction(IdeActions.ACTION_EDITOR_REFORMAT)
    assertEquals(
      prefix + BendWrappingFixtures.actorAfter + suffix,
      myFixture.getEditor.getDocument.getText
    )
    myFixture.performEditorAction(IdeActions.ACTION_UNDO)
    assertEquals(source, myFixture.getEditor.getDocument.getText)

  def testActorSelectionWithUnselectedCommentRemainsPartial(): Unit =
    val prefix = "# outside the selected source\n"
    val source = prefix + BendWrappingFixtures.actorBefore
    myFixture.configureByText("actor-partial-comment.bend", source)
    myFixture.getEditor.getSelectionModel
      .setSelection(prefix.length, source.length)
    myFixture.performEditorAction(IdeActions.ACTION_EDITOR_REFORMAT)
    val result = myFixture.getEditor.getDocument.getText
    assertTrue(result.startsWith(prefix))
    assertEquals(source.count(_ == '\n'), result.count(_ == '\n'))
    assertTrue(result.contains("case T.Cr{T.Alive{"))

  def testMultilineStringWithFakeHeaderKeepsLiteralBytesDuringRealEdits()
      : Unit =
    val root = Files.createTempDirectory(
      Files.createDirectories(java.nio.file.Path.of(getProject.getBasePath)),
      "literal-provenance-"
    )
    Files.writeString(
      root.resolve(".editorconfig"),
      "root = true\n[*.bend]\nindent_size = 4\n"
    )
    val path = root.resolve("literal.bend")
    Files.writeString(path, BendWrappingFixtures.multilineLiteralBefore)
    myFixture.configureFromExistingVirtualFile(
      com.intellij.openapi.vfs.LocalFileSystem.getInstance
        .refreshAndFindFileByNioFile(path)
    )
    myFixture.performEditorAction(IdeActions.ACTION_EDITOR_REFORMAT)
    assertEquals(
      BendWrappingFixtures.multilineLiteralAfterFourSpaces,
      myFixture.getEditor.getDocument.getText
    )
    myFixture.performEditorAction(IdeActions.ACTION_EDITOR_REFORMAT)
    assertEquals(
      BendWrappingFixtures.multilineLiteralAfterFourSpaces,
      myFixture.getEditor.getDocument.getText
    )
    myFixture.performEditorAction(IdeActions.ACTION_UNDO)
    assertEquals(
      BendWrappingFixtures.multilineLiteralBefore,
      myFixture.getEditor.getDocument.getText
    )

  def testUpperAndLowercaseComparisonOperandsHaveIdenticalFormattingSupport()
      : Unit =
    val root = Files.createTempDirectory(
      Files.createDirectories(java.nio.file.Path.of(getProject.getBasePath)),
      "comparison-operands-"
    )
    for (name, before, twoSpaces, fourSpaces) <-
        BendWrappingFixtures.comparisonCases
    do
      assertEquals(twoSpaces, reformat(before))
      myFixture.performEditorAction(IdeActions.ACTION_EDITOR_REFORMAT)
      assertEquals(twoSpaces, myFixture.getEditor.getDocument.getText)
      val dir = Files.createDirectories(root.resolve(name))
      Files.writeString(
        dir.resolve(".editorconfig"),
        "root = true\n[*.bend]\nindent_size = 4\n"
      )
      val path = dir.resolve("comparison.bend")
      Files.writeString(path, before)
      myFixture.configureFromExistingVirtualFile(
        com.intellij.openapi.vfs.LocalFileSystem.getInstance
          .refreshAndFindFileByNioFile(path)
      )
      myFixture.performEditorAction(IdeActions.ACTION_EDITOR_REFORMAT)
      assertEquals(fourSpaces, myFixture.getEditor.getDocument.getText)
      myFixture.performEditorAction(IdeActions.ACTION_EDITOR_REFORMAT)
      assertEquals(fourSpaces, myFixture.getEditor.getDocument.getText)
