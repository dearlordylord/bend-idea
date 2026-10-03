package com.dearlordylord.bend.idea.features.formatting

import com.intellij.application.options.CodeStyle
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.codeStyle.CodeStyleSettingsManager
import com.intellij.testFramework.{IndexingTestUtil, PsiTestUtil}
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.fixtures.impl.CodeInsightTestFixtureImpl
import com.intellij.testFramework.fixtures.impl.CodeInsightTestFixtureImpl.SelectionAndCaretMarkupApplyPolicy
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import org.editorconfig.Utils
import org.editorconfig.configmanagement.extended.EditorConfigCodeStyleSettingsModifier
import org.editorconfig.settings.EditorConfigSettings
import org.junit.Assert.*

final class BendEditorConfigSaveTest extends BasePlatformTestCase:
  def testNativeEditorConfigSavePropertiesCoexistWithWrapping(): Unit =
    checkSaveProperties("utf-8")

  def testNativeNewUtf8BomFileKeepsEncodingDuringWrapping(): Unit =
    checkSaveProperties("utf-8-bom")

  private def checkSaveProperties(charset: String): Unit =
    Utils.setFullIntellijSettingsSupportEnabledInTest(true)
    val oldEnabledInTests = Utils.isEnabledInTests()
    Utils.setEnabledInTests(true)
    EditorConfigCodeStyleSettingsModifier.Handler.INSTANCE.setEnabledInTests(
      true
    )
    val originalSettings = CodeStyle.getSettings(getProject)
    CodeStyle.dropTemporarySettings(getProject)
    val settings = CodeStyle.getSettings(getProject)
    val editorConfig = settings.getCustomSettings(classOf[EditorConfigSettings])
    val oldEnabled = editorConfig.ENABLED
    val fixture = myFixture.asInstanceOf[CodeInsightTestFixtureImpl]
    val root = Files.createTempDirectory(
      Files.createDirectories(Path.of(getProject.getBasePath)),
      "editorconfig-save-"
    )
    val local = LocalFileSystem.getInstance
    try
      editorConfig.ENABLED = true
      fixture.setSelectionAndCaretMarkupApplyPolicy(
        SelectionAndCaretMarkupApplyPolicy.UPDATE_DOCUMENT_AND_LEAVE_IT_DIRTY
      )
      for
        separator <- List("lf", "crlf")
        finalNewline <- List(true, false)
        originalFinalNewline <- List(true, false)
      do
        val directory = Files.createDirectories(
          root.resolve(
            s"$separator-$finalNewline-$originalFinalNewline-$charset"
          )
        )
        Files.writeString(
          directory.resolve(".editorconfig"),
          s"root = true\n[*.bend]\nindent_style = space\nindent_size = 4\nbend_max_line_length = 24\nend_of_line = $separator\ninsert_final_newline = $finalNewline\ntrim_trailing_whitespace = true\ncharset = $charset\n"
        )
        val path = directory.resolve("save.bend")
        val originalSuffix = if originalFinalNewline then "\n" else ""
        val source = "def f():\n  combine_arguments(1,2,3)  " + originalSuffix
        val virtualRoot = local.refreshAndFindFileByNioFile(directory)
        PsiTestUtil.addContentRoot(getModule, virtualRoot)
        IndexingTestUtil.waitUntilIndexesAreReady(getProject)
        val file = com.intellij.openapi.command.WriteCommandAction
          .writeCommandAction(getProject)
          .compute[com.intellij.openapi.vfs.VirtualFile, RuntimeException](() =>
            virtualRoot.createChildData(this, "save.bend")
          )
        val wrapped =
          "def f():\n    combine_arguments(\n        1,\n        2,\n        3\n    )"
        val preferredProject = com.intellij.openapi.project.ProjectLocator
          .withPreferredProject(file, getProject)
        try
          CodeStyleSettingsManager
            .getInstance(getProject)
            .notifyCodeStyleSettingsChanged()
          // Open an empty native file and type into its document. Its first save
          // uses EditorConfig; existing physical files retain detected endings.
          myFixture.configureFromExistingVirtualFile(file)
          com.intellij.openapi.command.WriteCommandAction
            .runWriteCommandAction(
              getProject,
              new Runnable:
                override def run(): Unit =
                  myFixture.getEditor.getDocument.setText(source)
            )
          myFixture.getEditor.getCaretModel.moveToOffset(0)
          myFixture.performEditorAction(IdeActions.ACTION_EDITOR_REFORMAT)
          assertEquals(
            wrapped + "  " + originalSuffix,
            myFixture.getEditor.getDocument.getText
          )
          val options = com.intellij.openapi.editor.impl.TrailingSpacesStripper
            .getOptions(myFixture.getEditor.getDocument)
          assertEquals(finalNewline, options.isEnsureNewLineAtEOF)
          assertTrue(options.isStripTrailingSpaces)
          FileDocumentManager.getInstance.saveDocument(
            myFixture.getEditor.getDocument
          )
        finally preferredProject.close()
        val newline = if separator == "crlf" then "\r\n" else "\n"
        val expected =
          (wrapped + (if finalNewline || originalFinalNewline then "\n"
                      else "")).replace("\n", newline)
        val bytes = Files.readAllBytes(path)
        val bom = Array(0xef.toByte, 0xbb.toByte, 0xbf.toByte)
        val hasBom = bytes.take(3).sameElements(bom)
        assertEquals(charset == "utf-8-bom", hasBom)
        assertEquals(
          expected,
          new String(
            if hasBom then bytes.drop(3) else bytes,
            StandardCharsets.UTF_8
          )
        )
        assertEquals(StandardCharsets.UTF_8, file.getCharset)
    finally
      editorConfig.ENABLED = oldEnabled
      CodeStyle.setTemporarySettings(getProject, originalSettings)
      fixture.setSelectionAndCaretMarkupApplyPolicy(
        SelectionAndCaretMarkupApplyPolicy.UPDATE_FILE_AND_KEEP_DOCUMENT_CLEAN
      )
      EditorConfigCodeStyleSettingsModifier.Handler.INSTANCE.setEnabledInTests(
        false
      )
      Utils.setFullIntellijSettingsSupportEnabledInTest(false)
      Utils.setEnabledInTests(oldEnabledInTests)
