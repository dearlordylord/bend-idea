package com.dearlordylord.bend.idea.features.editing

import com.dearlordylord.bend.idea.features.formatting.BendWrappingFixtures
import com.intellij.application.options.CodeStyle
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.psi.codeStyle.CodeStyleSettingsManager
import org.editorconfig.Utils
import org.editorconfig.configmanagement.extended.EditorConfigCodeStyleSettingsModifier
import org.editorconfig.settings.EditorConfigSettings
import org.junit.Assert.*
import java.nio.file.{Files, Path}

final class BendIndentTest extends BasePlatformTestCase:
  private def enter(source: String): String =
    myFixture.configureByText("indent.bend", source)
    myFixture.performEditorAction(IdeActions.ACTION_EDITOR_ENTER)
    myFixture.getEditor.getDocument.getText

  def testTwoSpaceDefaultsAndDeclarationEnter(): Unit =
    val file = myFixture.configureByText("options.bend", "def f(): 1")
    assertEquals(2, CodeStyle.getIndentOptions(file).INDENT_SIZE)
    assertEquals("def f():\n  ", enter("def f():<caret>"))

  def testNestedEditorConfigControlsEnterBackspaceAndReformat(): Unit =
    Utils.setFullIntellijSettingsSupportEnabledInTest(true)
    EditorConfigCodeStyleSettingsModifier.Handler.INSTANCE.setEnabledInTests(
      true
    )
    val originalSettings = CodeStyle.getSettings(getProject)
    CodeStyle.dropTemporarySettings(getProject)
    val projectSettings = CodeStyle
      .getSettings(getProject)
      .getCustomSettings(classOf[EditorConfigSettings])
    val oldEnabled = projectSettings.ENABLED
    try
      projectSettings.ENABLED = true
      val root = Files.createDirectories(Path.of(getProject.getBasePath))
      Files.writeString(
        root.resolve(".editorconfig"),
        "root = true\n[*.bend]\nindent_style = space\nindent_size = 4\n"
      )
      val nested = Files.createDirectories(root.resolve("nested"))
      Files.writeString(
        nested.resolve(".editorconfig"),
        "[*.bend]\nindent_style = tab\nindent_size = 4\ntab_width = 4\n"
      )
      val unset = Files.createDirectories(root.resolve("unset"))
      Files.writeString(
        unset.resolve(".editorconfig"),
        "[*.bend]\nindent_size = unset\n"
      )
      val wide = Files.createDirectories(root.resolve("wide"))
      Files.writeString(
        wide.resolve(".editorconfig"),
        "[*.bend]\nindent_style = tab\nindent_size = 8\ntab_width = 4\n"
      )
      val parentSource =
        Files.writeString(root.resolve("parent.bend"), "def f():")
      val nestedSource =
        Files.writeString(nested.resolve("child.bend"), "def f():")
      val parentFormat =
        Files.writeString(root.resolve("format.bend"), "def f():\n  1\n")
      val childFormat =
        Files.writeString(nested.resolve("format.bend"), "def f():\n  1\n")
      val unsetSource =
        Files.writeString(unset.resolve("child.bend"), "def f():")
      val wideSource = Files.writeString(wide.resolve("child.bend"), "def f():")
      val wideFormat =
        Files.writeString(wide.resolve("format.bend"), "def f():\n  1\n")
      val local = LocalFileSystem.getInstance
      val projectRoot = local.refreshAndFindFileByNioFile(root)
      val parent = local.refreshAndFindFileByNioFile(parentSource)
      val child = local.refreshAndFindFileByNioFile(nestedSource)
      assertNotNull(projectRoot)
      assertNotNull(parent)
      assertNotNull(child)
      PsiTestUtil.addContentRoot(getModule, projectRoot)
      IndexingTestUtil.waitUntilIndexesAreReady(getProject)
      CodeStyleSettingsManager
        .getInstance(getProject)
        .notifyCodeStyleSettingsChanged()
      myFixture.configureFromExistingVirtualFile(parent)
      assertEquals(
        4,
        CodeStyle
          .getSettings(myFixture.getFile)
          .OTHER_INDENT_OPTIONS
          .INDENT_SIZE
      )
      myFixture.getEditor.getCaretModel.moveToOffset(
        myFixture.getEditor.getDocument.getTextLength
      )
      myFixture.performEditorAction(IdeActions.ACTION_EDITOR_ENTER)
      assertEquals("def f():\n    ", myFixture.getEditor.getDocument.getText)
      myFixture.configureFromExistingVirtualFile(child)
      val options =
        CodeStyle.getSettings(myFixture.getFile).OTHER_INDENT_OPTIONS
      assertTrue(options.USE_TAB_CHARACTER)
      assertEquals(4, options.TAB_SIZE)
      myFixture.getEditor.getCaretModel.moveToOffset(
        myFixture.getEditor.getDocument.getTextLength
      )
      myFixture.performEditorAction(IdeActions.ACTION_EDITOR_ENTER)
      assertEquals("def f():\n\t", myFixture.getEditor.getDocument.getText)
      myFixture.performEditorAction(IdeActions.ACTION_EDITOR_BACKSPACE)
      assertEquals("def f():\n", myFixture.getEditor.getDocument.getText)
      myFixture.configureFromExistingVirtualFile(
        local.refreshAndFindFileByNioFile(parentFormat)
      )
      myFixture.performEditorAction(IdeActions.ACTION_EDITOR_REFORMAT)
      assertEquals("def f():\n    1\n", myFixture.getEditor.getDocument.getText)
      myFixture.configureFromExistingVirtualFile(
        local.refreshAndFindFileByNioFile(childFormat)
      )
      myFixture.performEditorAction(IdeActions.ACTION_EDITOR_REFORMAT)
      assertEquals("def f():\n\t1\n", myFixture.getEditor.getDocument.getText)
      myFixture.configureFromExistingVirtualFile(
        local.refreshAndFindFileByNioFile(unsetSource)
      )
      myFixture.getEditor.getCaretModel.moveToOffset(
        myFixture.getEditor.getDocument.getTextLength
      )
      myFixture.performEditorAction(IdeActions.ACTION_EDITOR_ENTER)
      assertEquals("def f():\n  ", myFixture.getEditor.getDocument.getText)
      myFixture.configureFromExistingVirtualFile(
        local.refreshAndFindFileByNioFile(wideSource)
      )
      myFixture.getEditor.getCaretModel.moveToOffset(
        myFixture.getEditor.getDocument.getTextLength
      )
      myFixture.performEditorAction(IdeActions.ACTION_EDITOR_ENTER)
      assertEquals("def f():\n\t\t", myFixture.getEditor.getDocument.getText)
      myFixture.configureFromExistingVirtualFile(
        local.refreshAndFindFileByNioFile(wideFormat)
      )
      myFixture.performEditorAction(IdeActions.ACTION_EDITOR_REFORMAT)
      assertEquals("def f():\n\t\t1\n", myFixture.getEditor.getDocument.getText)
    finally
      projectSettings.ENABLED = oldEnabled
      CodeStyle.setTemporarySettings(getProject, originalSettings)
      EditorConfigCodeStyleSettingsModifier.Handler.INSTANCE.setEnabledInTests(
        false
      )
      Utils.setFullIntellijSettingsSupportEnabledInTest(false)

  def testConfiguredFourSpaceEnterAndBackspace(): Unit =
    val file = myFixture.configureByText("four.bend", "def f():<caret>")
    val options =
      CodeStyle.getSettings(getProject).getIndentOptions(file.getFileType)
    val original = options.INDENT_SIZE
    try
      options.INDENT_SIZE = 4
      myFixture.performEditorAction(IdeActions.ACTION_EDITOR_ENTER)
      assertEquals("def f():\n    ", myFixture.getEditor.getDocument.getText)
      myFixture.performEditorAction(IdeActions.ACTION_EDITOR_BACKSPACE)
      assertEquals("def f():\n", myFixture.getEditor.getDocument.getText)
    finally options.INDENT_SIZE = original

  def testConfiguredTabsEnterAndBackspace(): Unit =
    val file = myFixture.configureByText("tabs.bend", "def f():<caret>")
    val options =
      CodeStyle.getSettings(getProject).getIndentOptions(file.getFileType)
    val originalSize = options.INDENT_SIZE
    val originalTab = options.TAB_SIZE
    val originalUseTabs = options.USE_TAB_CHARACTER
    try
      options.INDENT_SIZE = 4
      options.TAB_SIZE = 4
      options.USE_TAB_CHARACTER = true
      myFixture.performEditorAction(IdeActions.ACTION_EDITOR_ENTER)
      assertEquals("def f():\n\t", myFixture.getEditor.getDocument.getText)
      myFixture.performEditorAction(IdeActions.ACTION_EDITOR_BACKSPACE)
      assertEquals("def f():\n", myFixture.getEditor.getDocument.getText)
    finally
      options.INDENT_SIZE = originalSize
      options.TAB_SIZE = originalTab
      options.USE_TAB_CHARACTER = originalUseTabs

  def testEnterAndBackspaceRemainUndoable(): Unit =
    myFixture.configureByText("undo-enter.bend", "def f():<caret>")
    myFixture.performEditorAction(IdeActions.ACTION_EDITOR_ENTER)
    assertEquals("def f():\n  ", myFixture.getEditor.getDocument.getText)
    myFixture.performEditorAction(IdeActions.ACTION_UNDO)
    assertEquals("def f():", myFixture.getEditor.getDocument.getText)

    myFixture.configureByText("undo-backspace.bend", "def f():\n  <caret>")
    myFixture.performEditorAction(IdeActions.ACTION_EDITOR_BACKSPACE)
    assertEquals("def f():\n", myFixture.getEditor.getDocument.getText)
    myFixture.performEditorAction(IdeActions.ACTION_UNDO)
    assertEquals("def f():\n  ", myFixture.getEditor.getDocument.getText)

  def testNestedCaseAndDoEnter(): Unit =
    assertEquals(
      "def f(x: Nat):\n  match x:\n    case Zero{}:\n      ",
      enter("def f(x: Nat):\n  match x:\n    case Zero{}:<caret>")
    )
    assertEquals(
      "def f():\n  do IO<Unit>:\n    ",
      enter("def f():\n  do IO<Unit>:<caret>")
    )

  def testBackspaceStepsThroughLeadingIndentOnly(): Unit =
    myFixture.configureByText(
      "backspace.bend",
      "def f():\n  match x:\n    case Zero{}:\n      <caret>value"
    )
    myFixture.performEditorAction(IdeActions.ACTION_EDITOR_BACKSPACE)
    assertEquals(
      "def f():\n  match x:\n    case Zero{}:\n    value",
      myFixture.getEditor.getDocument.getText
    )
    myFixture.performEditorAction(IdeActions.ACTION_EDITOR_BACKSPACE)
    assertEquals(
      "def f():\n  match x:\n    case Zero{}:\n  value",
      myFixture.getEditor.getDocument.getText
    )

  def testContinuedAndIncompleteHeaders(): Unit =
    assertEquals("def f(x: Nat,\n  ", enter("def f(x: Nat,<caret>"))
    assertEquals("def f(\n  x: Nat,\n  ", enter("def f(\n  x: Nat,<caret>"))
    assertEquals("def broken(\n  ", enter("def broken(<caret>"))
    assertEquals(
      "def f():\n  match x:\n    ",
      enter("def f():\n  match x:<caret>")
    )

  def testCommentAndUnfinishedLiteralEnter(): Unit =
    assertEquals("def f():\n  # note\n  ", enter("def f():\n  # note<caret>"))
    assertEquals("def f(): \"open\n", enter("def f(): \"open<caret>"))

  def testBackspaceInExpressionDoesNotChangeLeadingIndentOrOperators(): Unit =
    myFixture.configureByText(
      "expression.bend",
      "def f():\n  value<caret> + other"
    )
    myFixture.performEditorAction(IdeActions.ACTION_EDITOR_BACKSPACE)
    assertEquals(
      "def f():\n  valu + other",
      myFixture.getEditor.getDocument.getText
    )

  def testEnterInsideInlineCallKeepsFollowingExpression(): Unit =
    assertEquals(
      "def f(): call(\n  value)",
      enter("def f(): call(<caret>value)")
    )

  def testBlankLineBackspaceKeepsCodeUntouched(): Unit =
    myFixture.configureByText("blank.bend", "def f():\n    <caret>\n  value")
    myFixture.performEditorAction(IdeActions.ACTION_EDITOR_BACKSPACE)
    assertEquals(
      "def f():\n  \n  value",
      myFixture.getEditor.getDocument.getText
    )

  def testTabWidthDoesNotSetSpaceIndentAndTabRemaindersWork(): Unit =
    val root = Files.createTempDirectory(
      Files.createDirectories(Path.of(getProject.getBasePath)),
      "indent-width-remainder-"
    )
    for ((properties, indent, continuation), index) <-
        BendWrappingFixtures.editorConfigIndentCases.zipWithIndex
    do
      val dir = Files.createDirectories(root.resolve("case" + index))
      Files.writeString(
        dir.resolve(".editorconfig"),
        "root = true\n[*.bend]\n" + properties + "bend_max_line_length = 20\n"
      )
      def open(name: String, source: String): Unit =
        val path = dir.resolve(name + ".bend")
        Files.writeString(path, source)
        myFixture.configureFromExistingVirtualFile(
          LocalFileSystem.getInstance.refreshAndFindFileByNioFile(path)
        )
      open("typing", "def f():")
      myFixture.getEditor.getCaretModel.moveToOffset(
        myFixture.getEditor.getDocument.getTextLength
      )
      myFixture.performEditorAction(IdeActions.ACTION_EDITOR_ENTER)
      assertEquals(
        "def f():\n" + indent,
        myFixture.getEditor.getDocument.getText
      )
      myFixture.performEditorAction(IdeActions.ACTION_EDITOR_BACKSPACE)
      assertEquals("def f():\n", myFixture.getEditor.getDocument.getText)
      open("format", "def f():\n  1\n")
      myFixture.performEditorAction(IdeActions.ACTION_EDITOR_REFORMAT)
      assertEquals(
        "def f():\n" + indent + "1\n",
        myFixture.getEditor.getDocument.getText
      )
      myFixture.performEditorAction(IdeActions.ACTION_EDITOR_REFORMAT)
      assertEquals(
        "def f():\n" + indent + "1\n",
        myFixture.getEditor.getDocument.getText
      )
      open("wrap", "def main():\n" + indent + "combine(alpha,beta,gamma)\n")
      val expected =
        "def main():\n" + indent + "combine(\n" + continuation + "alpha,\n" + continuation + "beta,\n" + continuation + "gamma\n" + indent + ")\n"
      myFixture.performEditorAction(IdeActions.ACTION_EDITOR_REFORMAT)
      assertEquals(expected, myFixture.getEditor.getDocument.getText)
      myFixture.performEditorAction(IdeActions.ACTION_EDITOR_REFORMAT)
      assertEquals(expected, myFixture.getEditor.getDocument.getText)

  def testDisabledEditorConfigUsesIdeDefaultsAndCanBeEnabledAgain(): Unit =
    val settings = CodeStyle
      .getSettings(getProject)
      .getCustomSettings(classOf[EditorConfigSettings])
    val previousEnabled = settings.ENABLED
    val root = Files.createTempDirectory(
      Files.createDirectories(Path.of(getProject.getBasePath)),
      "editorconfig-toggle-"
    )
    Files.writeString(
      root.resolve(".editorconfig"),
      "root = true\n[*.bend]\nindent_style = space\nindent_size = 4\nbend_max_line_length = 20\n"
    )
    def open(name: String, source: String): Unit =
      val path = root.resolve(name + ".bend")
      Files.writeString(path, source)
      myFixture.configureFromExistingVirtualFile(
        LocalFileSystem.getInstance.refreshAndFindFileByNioFile(path)
      )
    try
      for enabled <- List(false, true) do
        settings.ENABLED = enabled
        CodeStyleSettingsManager
          .getInstance(getProject)
          .notifyCodeStyleSettingsChanged()
        val indent = if enabled then "    " else "  "
        open("typing" + enabled, "def f():")
        myFixture.getEditor.getCaretModel.moveToOffset(
          myFixture.getEditor.getDocument.getTextLength
        )
        myFixture.performEditorAction(IdeActions.ACTION_EDITOR_ENTER)
        assertEquals(
          "def f():\n" + indent,
          myFixture.getEditor.getDocument.getText
        )
        myFixture.performEditorAction(IdeActions.ACTION_EDITOR_BACKSPACE)
        assertEquals("def f():\n", myFixture.getEditor.getDocument.getText)
        open("format" + enabled, "def f():\n    1\n")
        myFixture.performEditorAction(IdeActions.ACTION_EDITOR_REFORMAT)
        assertEquals(
          "def f():\n" + indent + "1\n",
          myFixture.getEditor.getDocument.getText
        )
        open(
          "wrap" + enabled,
          "def main():\n" + indent + "combine(alpha,beta,gamma)\n"
        )
        myFixture.performEditorAction(IdeActions.ACTION_EDITOR_REFORMAT)
        val expected = if enabled then
          "def main():\n    combine(\n        alpha,\n        beta,\n        gamma\n    )\n"
        else "def main():\n  combine(alpha, beta, gamma)\n"
        assertEquals(expected, myFixture.getEditor.getDocument.getText)
    finally settings.ENABLED = previousEnabled

  def testEnterDoesNotCanonicalizeAcrossUnsafePhysicalTabBoundary(): Unit =
    val root = Files.createTempDirectory(
      Files.createDirectories(Path.of(getProject.getBasePath)),
      "physical-tab-boundary-"
    )
    Files.writeString(
      root.resolve(".editorconfig"),
      "root = true\n[*.bend]\nindent_style = tab\nindent_size = 2\ntab_width = 8\n"
    )
    for (name, source) <- List(
        (
          "nested",
          "def main():\n  match value:\n    case value:\n      match value:"
        ),
        (
          "same-level",
          "def main():\n  match value:\n    case value:\n      match value:\n        value"
        )
      )
    do
      val path = root.resolve(name + ".bend")
      Files.writeString(path, source)
      myFixture.configureFromExistingVirtualFile(
        LocalFileSystem.getInstance.refreshAndFindFileByNioFile(path)
      )
      myFixture.getEditor.getCaretModel.moveToOffset(
        myFixture.getEditor.getDocument.getTextLength
      )
      myFixture.performEditorAction(IdeActions.ACTION_EDITOR_ENTER)
      val result = myFixture.getEditor.getDocument.getText
      assertTrue(result.startsWith(source + "\n"))
      val continuation = result.substring(source.length + 1)
      assertFalse(
        "Unsafe visual tab normalization must not change physical ownership",
        continuation.contains('\t')
      )
      assertTrue(
        "Fallback indentation remains whitespace only",
        continuation.forall(_ == ' ')
      )
