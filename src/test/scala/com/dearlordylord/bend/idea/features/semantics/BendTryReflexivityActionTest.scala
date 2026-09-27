package com.dearlordylord.bend.idea.features.semantics

import com.dearlordylord.bend.idea.adapters.cli.RealBendCompilerFixture
import com.dearlordylord.bend.idea.analysis.api.{
  BendCheckService,
  BendProofEditPreviewOutcome,
  BendProofEditValidator
}
import com.dearlordylord.bend.idea.analysis.model.BendCheckResult
import com.dearlordylord.bend.idea.model.FileId
import com.dearlordylord.bend.idea.test.VfsTestRoots
import com.dearlordylord.bend.idea.toolchain.api.{
  BendToolchainChoices,
  BendToolchainSettings
}
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.ui.{TestDialog, TestDialogManager}
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import java.nio.file.{Files, Path}
import org.junit.Assert.*
import java.util.concurrent.atomic.AtomicReference

final class BendTryReflexivityActionTest extends BasePlatformTestCase:
  private var original: BendToolchainChoices = null
  private var directory: Path = null

  override def setUp(): Unit =
    super.setUp()
    VfsTestRoots.allowSystemTemporaryDirectory(getTestRootDisposable)
    val settings = ApplicationManager.getApplication.getService(
      classOf[BendToolchainSettings]
    )
    original = settings.choices
    directory = Files.createTempDirectory("bend-refl-editor-")
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

  private def perform(source: String): Unit =
    myFixture.configureByText("proof.bend", source)
    myFixture.getEditor.getCaretModel.moveToOffset(source.indexOf("?need") + 2)
    myFixture.performEditorAction("Bend.TryReflexivity")

  private def awaitText(expected: String): Unit =
    val deadline = System.nanoTime() + 20_000_000_000L
    while myFixture.getEditor.getDocument.getText != expected &&
      System.nanoTime() < deadline
    do Thread.sleep(50)
    assertEquals(expected, myFixture.getEditor.getDocument.getText)

  private def awaitGoal(): BendCheckResult =
    val file = myFixture.getFile.getVirtualFile
    val id = new FileId(file.getCanonicalPath, true)
    val service = getProject.getService(classOf[BendCheckService])
    val deadline = System.nanoTime() + 20_000_000_000L
    var result = service.resultsFor(id).find(_.goal.nonEmpty)
    while result.isEmpty && System.nanoTime() < deadline do
      Thread.sleep(50)
      result = service.resultsFor(id).find(_.goal.nonEmpty)
    assertTrue("expected a current compiler goal", result.nonEmpty)
    result.get

  private def awaitPreview(
      check: BendCheckResult
  ): BendProofEditPreviewOutcome =
    val outcome = new AtomicReference[BendProofEditPreviewOutcome]()
    getProject
      .getService(classOf[BendProofEditValidator])
      .previewReflexivity(check, check.goal.get)(value => outcome.set(value))
    val deadline = System.nanoTime() + 20_000_000_000L
    while outcome.get() == null && System.nanoTime() < deadline do
      UIUtil.dispatchAllInvocationEvents()
      Thread.sleep(50)
    assertNotNull("expected a completed compiler preview", outcome.get())
    outcome.get()

  def testValidatedReflexivityIsUndoable(): Unit =
    val originalText =
      "import Base\ndef refl(x: U32) -> {x == x : U32}:\n  ?need\n"
    val expected = originalText.replace("?need", "{==}")
    val previous = TestDialogManager.setTestDialog(TestDialog.YES)
    try
      perform(originalText)
      awaitText(expected)
      val virtual = myFixture.getFile.getVirtualFile
      val editor = FileEditorManager
        .getInstance(getProject)
        .getSelectedEditor(virtual)
      val undo = UndoManager.getInstance(getProject)
      assertTrue(undo.isUndoAvailable(editor))
      undo.undo(editor)
      assertEquals(originalText, myFixture.getEditor.getDocument.getText)
    finally
      val _ = TestDialogManager.setTestDialog(previous)

  def testRejectedReflexivityNeverChangesSource(): Unit =
    val source =
      "import Base\ndef impossible() -> {0n == 1n : Nat}:\n  ?need\n"
    val previous = TestDialogManager.setTestDialog(TestDialog.YES)
    try
      perform(source)
      assertTrue(
        awaitPreview(awaitGoal()).isInstanceOf[
          BendProofEditPreviewOutcome.Rejected
        ]
      )
      assertEquals(source, myFixture.getEditor.getDocument.getText)
    finally
      val _ = TestDialogManager.setTestDialog(previous)

  def testRemainingTodoKeepsProofIncompleteAfterLocalStep(): Unit =
    val source =
      "import Base\ndef refl() -> {0n == 0n : Nat}:\n  ?need\ndef later() -> U32:\n  ?TODO\n"
    val previous = TestDialogManager.setTestDialog(TestDialog.YES)
    try
      perform(source)
      awaitText(source.replace("?need", "{==}"))
      assertTrue(myFixture.getEditor.getDocument.getText.contains("?TODO"))
    finally
      val _ = TestDialogManager.setTestDialog(previous)

  def testLawSpecificationIsNeverEdited(): Unit =
    val source = "law claim:\n  ?need\n"
    val previous = TestDialogManager.setTestDialog(TestDialog.YES)
    try
      perform(source)
      val until = System.nanoTime() + 15_000_000_000L
      while getProject
          .getService(
            classOf[com.dearlordylord.bend.idea.analysis.api.BendCheckService]
          )
          .busy && System.nanoTime() < until
      do Thread.sleep(50)
      assertEquals(source, myFixture.getEditor.getDocument.getText)
    finally
      val _ = TestDialogManager.setTestDialog(previous)

  def testStaleGoalIsRejectedBeforePreview(): Unit =
    val source =
      "import Base\ndef refl(x: U32) -> {x == x : U32}:\n  ?need\n"
    val previous = TestDialogManager.setTestDialog(TestDialog.NO)
    try
      perform(source)
      val check = awaitGoal()
      WriteCommandAction.runWriteCommandAction(
        getProject,
        new Runnable:
          override def run(): Unit =
            myFixture.getEditor.getDocument.insertString(0, "// edit\n")
      )
      assertTrue(
        awaitPreview(check).isInstanceOf[
          BendProofEditPreviewOutcome.Rejected
        ]
      )
      assertEquals(
        "// edit\n" + source,
        myFixture.getEditor.getDocument.getText
      )
    finally
      val _ = TestDialogManager.setTestDialog(previous)
