package com.dearlordylord.bend.idea.features.semantics

import com.dearlordylord.bend.idea.adapters.cli.RealBendCompilerFixture
import com.dearlordylord.bend.idea.analysis.api.BendCheckService
import com.dearlordylord.bend.idea.model.FileId
import com.dearlordylord.bend.idea.test.VfsTestRoots
import com.dearlordylord.bend.idea.toolchain.api.{
  BendToolchainChoices,
  BendToolchainSettings
}
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.{Files, Path}
import org.junit.Assert.*

final class BendNormalizeExpressionActionTest extends BasePlatformTestCase:
  private var original: BendToolchainChoices = null
  private var directory: Path = null

  override def setUp(): Unit =
    super.setUp()
    VfsTestRoots.allowSystemTemporaryDirectory(getTestRootDisposable)
    val settings = ApplicationManager.getApplication.getService(
      classOf[BendToolchainSettings]
    )
    original = settings.choices
    directory = Files.createTempDirectory("bend-normal-editor-")
    val compiler = RealBendCompilerFixture.inputs
    val _ = compiler.writeStructuredLauncher(directory.resolve("bend"))
    val selectedBase = directory.resolve("base.bend")
    Files.copy(compiler.base, selectedBase)
    settings.update(
      BendToolchainChoices(
        executable = directory.resolve("bend").toString,
        baseSource = selectedBase.toString,
        diagnosticsEnabled = false
      )
    )

  override def tearDown(): Unit =
    try
      ApplicationManager.getApplication
        .getService(classOf[BendToolchainSettings])
        .update(original)
      if directory != null then
        val files = Files.walk(directory)
        try
          files
            .sorted(java.util.Comparator.reverseOrder())
            .forEach(p => {
              val _ = Files.deleteIfExists(p)
            })
        finally files.close()
    finally super.tearDown()

  private def id: FileId =
    val file = myFixture.getFile.getVirtualFile
    new FileId(
      Option(file.getCanonicalPath).getOrElse(file.getPath),
      file.getCanonicalPath != null
    )

  def testActionDisplaysClosedResultWithoutChangingSource(): Unit =
    val text =
      "import Base\ndef id(A: Type, x: A) -> A:\n  x\ndef use() -> U32:\n  id(U32, 7)\n"
    myFixture.configureByText("normal.bend", text)
    myFixture.getEditor.getCaretModel.moveToOffset(
      text.lastIndexOf("id(U32, 7)") + "id(U32, 7".length
    )
    myFixture.performEditorAction("Bend.NormalizeExpression")
    val service = getProject.getService(classOf[BendCheckService])
    val deadline = System.nanoTime() + 20_000_000_000L
    while service.result(id).flatMap(_.normalization).isEmpty &&
      System.nanoTime() < deadline
    do Thread.sleep(50)
    assertEquals("7", service.result(id).flatMap(_.normalization).get.text)
    assertEquals(text, myFixture.getEditor.getDocument.getText)

  def testEditCancelsInFlightNormalization(): Unit =
    val marker = directory.resolve("normalize-started")
    val _ = RealBendCompilerFixture.inputs.writeStructuredLauncher(
      directory.resolve("bend"),
      normalizationPrelude = s"  printf x > \"${marker.toString}\"\n  sleep 6"
    )
    val text =
      "import Base\ndef id(A: Type, x: A) -> A:\n  x\ndef use() -> U32:\n  id(U32, 7)\n"
    myFixture.configureByText("cancel.bend", text)
    myFixture.getEditor.getCaretModel.moveToOffset(
      text.lastIndexOf("id(U32, 7)") + "id(U32, 7".length
    )
    myFixture.performEditorAction("Bend.NormalizeExpression")
    val startedBy = System.nanoTime() + 20_000_000_000L
    while !Files.exists(marker) && System.nanoTime() < startedBy do
      Thread.sleep(50)
    assertTrue("normalization worker did not start", Files.exists(marker))
    WriteCommandAction.runWriteCommandAction(
      getProject,
      new Runnable:
        override def run(): Unit =
          myFixture.getEditor.getDocument.insertString(0, "# changed\n")
    )
    val service = getProject.getService(classOf[BendCheckService])
    val stoppedBy = System.nanoTime() + 20_000_000_000L
    while service.busy && System.nanoTime() < stoppedBy do Thread.sleep(50)
    assertFalse(service.busy)
    assertFalse(
      service
        .result(id)
        .exists(result => result.fresh && result.normalization.nonEmpty)
    )
