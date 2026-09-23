package com.dearlordylord.bend.idea.features.checking

import com.dearlordylord.bend.idea.analysis.api.BendCheckService
import com.dearlordylord.bend.idea.analysis.model.{BendCheckOutcome, BendCheckSnapshot, BendCompleteness}
import com.dearlordylord.bend.idea.model.FileId
import com.dearlordylord.bend.idea.toolchain.api.{BendToolchainChoices, BendToolchainSettings}
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.event.{DocumentEvent, DocumentListener}
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.*
import java.nio.file.{Files, Path}

final class BendCheckCurrentFileTest extends BasePlatformTestCase:
  private var original: BendToolchainChoices = null
  private var directory: Path = null

  override def setUp(): Unit =
    super.setUp()
    val settings = ApplicationManager.getApplication.getService(classOf[BendToolchainSettings])
    original = settings.choices
    directory = Files.createTempDirectory("bend-check-editor-")
    val pinned = Path.of(".references/bend/bend2/main.ts").toAbsolutePath.normalize()
    val executable = directory.resolve("bend")
    Files.writeString(executable, "#!/bin/sh\nexec npx --yes bun '" + pinned + "' \"$@\"\n")
    executable.toFile.setExecutable(true)
    val selectedBase = directory.resolve("base.bend")
    Files.copy(pinned.resolveSibling("base.bend"), selectedBase)
    settings.update(BendToolchainChoices(executable = executable.toString,
      baseSource = selectedBase.toString))

  override def tearDown(): Unit =
    try
      ApplicationManager.getApplication.getService(classOf[BendToolchainSettings]).update(original)
      if directory != null then
        val paths = Files.walk(directory)
        try paths.sorted(java.util.Comparator.reverseOrder()).forEach(p => Files.deleteIfExists(p))
        finally paths.close()
    finally super.tearDown()

  def testExplicitActionUsesUnsavedDocumentAndProjectsCompilerError(): Unit =
    myFixture.configureByText("editor.bend", "import Base\ndef main() -> U32:\n  0\n")
    WriteCommandAction.runWriteCommandAction(getProject, new Runnable:
      override def run(): Unit =
        myFixture.getEditor.getDocument.setText("import Base\ndef main() -> U32:\n  unknown_name\n"))
    val file = myFixture.getFile.getVirtualFile
    val id = new FileId(Option(file.getCanonicalPath).getOrElse(file.getPath),
      file.getCanonicalPath != null)
    assertTrue(getProject.getService(classOf[BendCheckService]).result(id).isEmpty)
    myFixture.performEditorAction("Bend.CheckCurrentFile")
    val until = System.nanoTime() + 15_000_000_000L
    while getProject.getService(classOf[BendCheckService]).result(id).isEmpty &&
        System.nanoTime() < until do Thread.sleep(50)
    val result = getProject.getService(classOf[BendCheckService]).result(id).getOrElse(
      throw new AssertionError("Explicit check did not publish a result"))
    assertEquals(BendCheckOutcome.Failed, result.outcome)
    assertEquals(BendCompleteness.Unknown, result.completeness)
    assertTrue(result.details.contains("unknown_name"))
    val highlights = myFixture.doHighlighting()
    assertTrue(highlights.toArray.exists(_.toString.contains("unknown_name")))
    val selectedBase = directory.resolve("base.bend")
    Files.writeString(selectedBase, Files.readString(selectedBase) + "\n# changed after check\n")
    assertFalse("Base changed on disk must stale the recorded result",
      getProject.getService(classOf[BendCheckService]).result(id).get.fresh)
    assertFalse(myFixture.doHighlighting().toArray.exists(_.toString.contains("unknown_name")))
    // A fresh check reestablishes the result before testing same-path compiler replacement.
    Files.writeString(selectedBase, Files.readString(selectedBase).stripSuffix("\n# changed after check\n"))
    myFixture.performEditorAction("Bend.CheckCurrentFile")
    val nextDeadline = System.nanoTime() + 15_000_000_000L
    while getProject.getService(classOf[BendCheckService]).result(id).forall(!_.fresh) &&
        System.nanoTime() < nextDeadline do Thread.sleep(50)
    assertTrue(getProject.getService(classOf[BendCheckService]).result(id).exists(_.fresh))
    val compiler = directory.resolve("bend")
    Files.writeString(compiler, Files.readString(compiler) + "\n# replaced compiler wrapper\n")
    assertFalse("Compiler replaced at same path must stale the recorded result",
      getProject.getService(classOf[BendCheckService]).result(id).get.fresh)
    ApplicationManager.getApplication.getService(classOf[BendToolchainSettings]).update(
      BendToolchainChoices(executable = directory.resolve("missing-bend").toString))
    assertFalse(getProject.getService(classOf[BendCheckService]).result(id).get.fresh)
    assertFalse(myFixture.doHighlighting().toArray.exists(_.toString.contains("unknown_name")))
    WriteCommandAction.runWriteCommandAction(getProject, new Runnable:
      override def run(): Unit =
        myFixture.getEditor.getDocument.setText("import Base\ndef main() -> U32:\n  0\n"))
    assertFalse(getProject.getService(classOf[BendCheckService]).result(id).get.fresh)

  def testEditDuringCheckRejectsOldResultAndSecondActionCannotStartWorker(): Unit =
    val settings = ApplicationManager.getApplication.getService(classOf[BendToolchainSettings])
    val marker = directory.resolve("started")
    val executable = directory.resolve("slow-bend")
    Files.writeString(executable,
      "#!/bin/sh\nif [ \"$1\" = \"--help\" ]; then echo 'bend <file.bend> --check-only check the file and its imports; run nothing'; exit 0; fi\nprintf x >> '" + marker + "'\nsleep 10\n")
    executable.toFile.setExecutable(true)
    settings.update(BendToolchainChoices(executable = executable.toString))
    myFixture.configureByText("slow.bend", "def main() -> Type:\n  Type\n")
    val file = myFixture.getFile.getVirtualFile
    val id = new FileId(Option(file.getCanonicalPath).getOrElse(file.getPath),
      file.getCanonicalPath != null)
    val document = myFixture.getEditor.getDocument
    val snapshot = BendCheckSnapshot(id, file.getPath, document.getText,
      document.getModificationStamp, settings.selection)
    val service = getProject.getService(classOf[BendCheckService])
    def subscribe(callback: () => Unit): () => Unit =
      val listener = new DocumentListener:
        override def documentChanged(event: DocumentEvent): Unit = callback()
      document.addDocumentListener(listener)
      () => document.removeDocumentListener(listener)
    assertTrue(service.begin(snapshot, subscribe, () =>
      document.getModificationStamp == snapshot.sourceRevision))
    val worker = new Thread(new Runnable:
      override def run(): Unit = { service.check(snapshot, () => false); () })
    worker.start()
    val until = System.nanoTime() + 10_000_000_000L
    while !Files.exists(marker) && System.nanoTime() < until do Thread.sleep(25)
    assertTrue("First worker must have started", Files.exists(marker))
    assertFalse("A second worker must be rejected", service.begin(snapshot, subscribe, () => true))
    assertTrue("A reservation may be claimed by only one worker",
      service.check(snapshot, () => false).isEmpty)
    WriteCommandAction.runWriteCommandAction(getProject, new Runnable:
      override def run(): Unit =
        myFixture.getEditor.getDocument.setText("def main() -> Type:\n  Type # edited\n"))
    worker.join(5000)
    assertFalse("Canceled worker must finish promptly", worker.isAlive)
    assertFalse("Canceled worker must release the project slot", service.busy)
    assertEquals("Only one source worker may start", 1L, Files.size(marker))
    assertTrue("An edited in-flight result must not publish",
      getProject.getService(classOf[BendCheckService]).result(id).isEmpty)
    val next = snapshot.copy(text = document.getText,
      sourceRevision = document.getModificationStamp)
    assertTrue(service.begin(next, subscribe, () => true))
    service.cancel(id)
    assertFalse("Cancel before the worker starts must release its reservation", service.busy)
