package com.dearlordylord.bend.idea.features.checking

import com.dearlordylord.bend.idea.adapters.cli.RealBendCompilerFixture
import com.dearlordylord.bend.idea.analysis.api.BendCheckService
import com.dearlordylord.bend.idea.analysis.model.{
  BendCheckOutcome,
  BendCheckSnapshot,
  BendCompleteness
}
import com.dearlordylord.bend.idea.analysis.model.BendReliance
import com.dearlordylord.bend.idea.model.FileId
import com.dearlordylord.bend.idea.toolchain.api.{
  BendToolchainChoices,
  BendToolchainSettings
}
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.editor.event.{DocumentEvent, DocumentListener}
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.*
import java.nio.file.{Files, Path}

final class BendCheckCurrentFileTest extends BasePlatformTestCase:
  private var original: BendToolchainChoices = null
  private var directory: Path = null

  override def setUp(): Unit =
    super.setUp()
    val settings = ApplicationManager.getApplication.getService(
      classOf[BendToolchainSettings]
    )
    original = settings.choices
    directory = Files.createTempDirectory("bend-check-editor-")
    val compiler = RealBendCompilerFixture.inputs
    val executable = directory.resolve("bend")
    val _ = compiler.writeLauncher(executable)
    val selectedBase = directory.resolve("base.bend")
    Files.copy(compiler.base, selectedBase)
    settings.update(
      BendToolchainChoices(
        executable = executable.toString,
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
        val paths = Files.walk(directory)
        try
          paths
            .sorted(java.util.Comparator.reverseOrder())
            .forEach(p => {
              val _ = Files.deleteIfExists(p)
            })
        finally paths.close()
    finally super.tearDown()

  def testExplicitActionUsesUnsavedDocumentAndProjectsCompilerError(): Unit =
    val settings = ApplicationManager.getApplication.getService(
      classOf[BendToolchainSettings]
    )
    myFixture.configureByText(
      "editor.bend",
      "import Base\ndef main() -> U32:\n  0\n"
    )
    WriteCommandAction.runWriteCommandAction(
      getProject,
      new Runnable:
        override def run(): Unit =
          myFixture.getEditor.getDocument.setText(
            "import Base\ndef main() -> U32:\n  unknown_name\n"
          )
    )
    val file = myFixture.getFile.getVirtualFile
    val id = new FileId(
      Option(file.getCanonicalPath).getOrElse(file.getPath),
      file.getCanonicalPath != null
    )
    assertTrue(
      getProject.getService(classOf[BendCheckService]).result(id).isEmpty
    )
    myFixture.performEditorAction("Bend.CheckCurrentFile")
    val until = System.nanoTime() + 15_000_000_000L
    while getProject.getService(classOf[BendCheckService]).result(id).isEmpty &&
      System.nanoTime() < until
    do Thread.sleep(50)
    val result = getProject
      .getService(classOf[BendCheckService])
      .result(id)
      .getOrElse(
        throw new AssertionError("Explicit check did not publish a result")
      )
    assertEquals(BendCheckOutcome.Failed, result.outcome)
    assertEquals(BendCompleteness.Unknown, result.completeness)
    assertEquals(BendReliance.Unknown, result.reliance)
    assertEquals("Check failed", result.status)
    assertTrue(result.details.contains("unknown_name"))
    val highlights = myFixture.doHighlighting()
    assertTrue(highlights.toArray.exists(_.toString.contains("unknown_name")))
    val selectedBase = directory.resolve("base.bend")
    Files.writeString(
      selectedBase,
      Files.readString(selectedBase) + "\n# changed after check\n"
    )
    assertFalse(
      "Base changed on disk must stale the recorded result",
      getProject.getService(classOf[BendCheckService]).result(id).get.fresh
    )
    assertFalse(
      myFixture
        .doHighlighting()
        .toArray
        .exists(_.toString.contains("unknown_name"))
    )
    // A fresh check reestablishes the result before testing same-path compiler replacement.
    Files.writeString(
      selectedBase,
      Files.readString(selectedBase).stripSuffix("\n# changed after check\n")
    )
    myFixture.performEditorAction("Bend.CheckCurrentFile")
    val nextDeadline = System.nanoTime() + 15_000_000_000L
    while getProject
        .getService(classOf[BendCheckService])
        .result(id)
        .forall(!_.fresh) &&
      System.nanoTime() < nextDeadline
    do Thread.sleep(50)
    assertTrue(
      getProject
        .getService(classOf[BendCheckService])
        .result(id)
        .exists(_.fresh)
    )
    val compiler = directory.resolve("bend")
    Files.writeString(
      compiler,
      Files.readString(compiler) + "\n# replaced compiler wrapper\n"
    )
    assertFalse(
      "Compiler replaced at same path must stale the recorded result",
      getProject.getService(classOf[BendCheckService]).result(id).get.fresh
    )
    settings.update(
      settings.choices.copy(
        executable = directory.resolve("missing-bend").toString
      )
    )
    assertFalse(
      getProject.getService(classOf[BendCheckService]).result(id).get.fresh
    )
    assertFalse(
      myFixture
        .doHighlighting()
        .toArray
        .exists(_.toString.contains("unknown_name"))
    )
    WriteCommandAction.runWriteCommandAction(
      getProject,
      new Runnable:
        override def run(): Unit =
          myFixture.getEditor.getDocument.setText(
            "import Base\ndef main() -> U32:\n  0\n"
          )
    )
    assertFalse(
      getProject.getService(classOf[BendCheckService]).result(id).get.fresh
    )

  def testEditDuringCheckRejectsOldResultAndSecondActionCannotStartWorker()
      : Unit =
    val settings = ApplicationManager.getApplication.getService(
      classOf[BendToolchainSettings]
    )
    val marker = directory.resolve("started")
    val executable = directory.resolve("slow-bend")
    Files.writeString(
      executable,
      "#!/bin/sh\nif [ \"$1\" = \"--help\" ]; then echo '  bend <file.bend> --check-only check the file and its imports; run nothing'; exit 0; fi\nprintf x >> '" + marker + "'\nsleep 10\n"
    )
    executable.toFile.setExecutable(true)
    settings.update(settings.choices.copy(executable = executable.toString))
    myFixture.configureByText("slow.bend", "def main() -> Type:\n  Type\n")
    val file = myFixture.getFile.getVirtualFile
    val id = new FileId(
      Option(file.getCanonicalPath).getOrElse(file.getPath),
      file.getCanonicalPath != null
    )
    val document = myFixture.getEditor.getDocument
    val snapshot = BendCheckSnapshot(
      id,
      file.getPath,
      document.getText,
      document.getModificationStamp,
      settings.selection
    )
    val service = getProject.getService(classOf[BendCheckService])
    def subscribe(callback: () => Unit): () => Unit =
      val listener = new DocumentListener:
        override def documentChanged(event: DocumentEvent): Unit = callback()
      document.addDocumentListener(listener)
      () => document.removeDocumentListener(listener)
    val reservation = service
      .begin(
        snapshot,
        subscribe,
        () => document.getModificationStamp == snapshot.sourceRevision
      )
      .getOrElse(
        throw new AssertionError("First check must reserve the worker")
      )
    val worker = new Thread(new Runnable:
      override def run(): Unit = {
        val _ = service.check(snapshot, reservation, () => false); ()
      })
    worker.start()
    val until = System.nanoTime() + 10_000_000_000L
    while !Files.exists(marker) && System.nanoTime() < until do Thread.sleep(25)
    assertTrue("First worker must have started", Files.exists(marker))
    assertTrue(
      "A second worker must be rejected",
      service.begin(snapshot, subscribe, () => true).isEmpty
    )
    assertTrue(
      "A reservation may be claimed by only one worker",
      service.check(snapshot, reservation, () => false).isEmpty
    )
    WriteCommandAction.runWriteCommandAction(
      getProject,
      new Runnable:
        override def run(): Unit =
          myFixture.getEditor.getDocument.setText(
            "def main() -> Type:\n  Type # edited\n"
          )
    )
    worker.join(5000)
    assertFalse("Canceled worker must finish promptly", worker.isAlive)
    assertFalse("Canceled worker must release the project slot", service.busy)
    assertEquals("Only one source worker may start", 1L, Files.size(marker))
    assertTrue(
      "An edited in-flight result must not publish",
      getProject.getService(classOf[BendCheckService]).result(id).isEmpty
    )
    val next = snapshot.copy(
      text = document.getText,
      sourceRevision = document.getModificationStamp
    )
    assertTrue(service.begin(next, subscribe, () => true).isDefined)
    service.cancel(id)
    assertFalse(
      "Cancel before the worker starts must release its reservation",
      service.busy
    )

  def testUnsavedDependencyErrorProjectsToImportedEditorAndEditStalesRoot()
      : Unit =
    // This test projects an explicit result. Background rechecks can restart the
    // daemon during doHighlighting and obscure the projection assertion.
    val settings = ApplicationManager.getApplication.getService(
      classOf[BendToolchainSettings]
    )
    settings.update(settings.choices.copy(diagnosticsEnabled = false))
    val imported = myFixture.addFileToProject(
      "math.bend",
      "import Base\ndef square() -> U32:\n  1\n"
    )
    myFixture.openFileInEditor(imported.getVirtualFile)
    val dependencyDocument = myFixture.getEditor.getDocument
    WriteCommandAction.runWriteCommandAction(
      getProject,
      new Runnable:
        override def run(): Unit = dependencyDocument.setText(
          "import Base\ndef square() -> U32:\n  unknown_value\n"
        )
    )
    myFixture.configureByText(
      "main.bend",
      "import ./math.bend as M\ndef main() -> U32:\n  M.square()\n"
    )
    val root = myFixture.getFile.getVirtualFile
    val rootId = new FileId(
      Option(root.getCanonicalPath).getOrElse(root.getPath),
      root.getCanonicalPath != null
    )
    myFixture.performEditorAction("Bend.CheckCurrentFile")
    val service = getProject.getService(classOf[BendCheckService])
    val deadline = System.nanoTime() + 15_000_000_000L
    while service.result(rootId).isEmpty && System.nanoTime() < deadline do
      Thread.sleep(50)
    val result = service
      .result(rootId)
      .getOrElse(throw new AssertionError("Graph check did not publish"))
    assertEquals(result.details, BendCheckOutcome.Failed, result.outcome)
    assertTrue(result.details.contains("unknown_value"))
    val dep = imported.getVirtualFile
    val depId = new FileId(
      Option(dep.getCanonicalPath).getOrElse(dep.getPath),
      dep.getCanonicalPath != null
    )
    assertEquals(
      com.dearlordylord.bend.idea.analysis.model.BendLocation
        .SourceLine(depId, 2),
      result.diagnostics.head.location
    )
    val checkedDependency = result.sources.find(_.id == depId).get
    assertTrue("Published graph result must remain current", result.fresh)
    assertEquals(
      "Captured dependency text must match its current editor buffer",
      dependencyDocument.getText,
      checkedDependency.text
    )
    assertEquals(
      "Captured dependency revision must match its current editor buffer",
      dependencyDocument.getModificationStamp,
      checkedDependency.revision
    )
    myFixture.openFileInEditor(dep)
    val importedResults = service.resultsFor(depId)
    assertTrue(
      "Published root diagnostics should stay current for the imported source",
      importedResults.exists(r =>
        r.fresh && r.diagnostics.exists(_.message.contains("unknown_value"))
      )
    )
    assertTrue(
      myFixture
        .doHighlighting()
        .toArray
        .exists(_.toString.contains("unknown_value"))
    )
    WriteCommandAction.runWriteCommandAction(
      getProject,
      new Runnable:
        override def run(): Unit =
          dependencyDocument.setText("import Base\ndef square() -> U32:\n  1\n")
    )
    assertFalse(
      "Dependency listener must stale the root without a query",
      service.resultsFor(depId).head.fresh
    )
    assertFalse(service.result(rootId).get.fresh)
    assertFalse(
      myFixture
        .doHighlighting()
        .toArray
        .exists(_.toString.contains("unknown_value"))
    )

  def testMissingImportCreationStalesRootResult(): Unit =
    myFixture.configureByText("main.bend", "import ./later.bend as Later\n")
    val root = myFixture.getFile.getVirtualFile
    val rootId = new FileId(
      Option(root.getCanonicalPath).getOrElse(root.getPath),
      root.getCanonicalPath != null
    )
    val service = getProject.getService(classOf[BendCheckService])
    myFixture.performEditorAction("Bend.CheckCurrentFile")
    val deadline = System.nanoTime() + 15_000_000_000L
    while service.result(rootId).isEmpty && System.nanoTime() < deadline do
      Thread.sleep(50)
    val result = service
      .result(rootId)
      .getOrElse(
        throw new AssertionError("Missing import check did not publish")
      )
    assertTrue(result.details.contains("Cannot read imported module"))
    assertTrue(result.fresh)
    myFixture.addFileToProject("later.bend", "def item() -> Type:\n  Type\n")
    assertFalse(service.result(rootId).get.fresh)

  def testOpeningDependencyAfterCheckStillTracksUnsavedEdit(): Unit =
    val imported =
      myFixture.addFileToProject("later.bend", "def value() -> Type:\n  Type\n")
    myFixture.configureByText(
      "main.bend",
      "import ./later.bend as Later\ndef main() -> Type:\n  Later.value()\n"
    )
    val root = myFixture.getFile.getVirtualFile
    val rootId = new FileId(
      Option(root.getCanonicalPath).getOrElse(root.getPath),
      root.getCanonicalPath != null
    )
    val service = getProject.getService(classOf[BendCheckService])
    myFixture.performEditorAction("Bend.CheckCurrentFile")
    val deadline = System.nanoTime() + 15_000_000_000L
    while service.result(rootId).isEmpty && System.nanoTime() < deadline do
      Thread.sleep(50)
    assertTrue(service.result(rootId).get.fresh)
    val dep = imported.getVirtualFile
    val depId = new FileId(
      Option(dep.getCanonicalPath).getOrElse(dep.getPath),
      dep.getCanonicalPath != null
    )
    myFixture.openFileInEditor(dep)
    WriteCommandAction.runWriteCommandAction(
      getProject,
      new Runnable:
        override def run(): Unit = myFixture.getEditor.getDocument.setText(
          "def value() -> Type:\n  unknown\n"
        )
    )
    assertFalse(
      "Document opened after publication must invalidate its root",
      service.resultsFor(depId).head.fresh
    )

  def testExternalSymlinkTargetOpenedAfterCheckInvalidatesRoot(): Unit =
    val target = directory.resolve("external.bend")
    val alias = directory.resolve("alias.bend")
    Files.writeString(target, "def value() -> Type:\n  Type\n")
    Files.createSymbolicLink(alias, target.getFileName)
    myFixture.configureByText(
      "main.bend",
      s"import ${alias.toString} as External\ndef main() -> Type:\n  External.value()\n"
    )
    val root = myFixture.getFile.getVirtualFile
    val rootId = new FileId(
      Option(root.getCanonicalPath).getOrElse(root.getPath),
      root.getCanonicalPath != null
    )
    val service = getProject.getService(classOf[BendCheckService])
    myFixture.performEditorAction("Bend.CheckCurrentFile")
    val deadline = System.nanoTime() + 15_000_000_000L
    while service.result(rootId).isEmpty && System.nanoTime() < deadline do
      Thread.sleep(50)
    val checked = service
      .result(rootId)
      .getOrElse(
        throw new AssertionError("External graph check did not publish")
      )
    assertEquals(checked.details, BendCheckOutcome.Success, checked.outcome)
    val external =
      LocalFileSystem.getInstance().refreshAndFindFileByNioFile(target)
    assertNotNull(external)
    myFixture.openFileInEditor(external)
    val depId = new FileId(target.toRealPath().toString, true)
    WriteCommandAction.runWriteCommandAction(
      getProject,
      new Runnable:
        override def run(): Unit = myFixture.getEditor.getDocument.setText(
          "def value() -> Type:\n  unknown\n"
        )
    )
    assertFalse(
      "Canonical target edit must invalidate a symlink import root",
      service.resultsFor(depId).head.fresh
    )

  def testProofSiblingLawsEditInvalidatesGuardResult(): Unit =
    val laws = myFixture.addFileToProject("LAWS.bend", "# initial laws\n")
    myFixture.configureByText("PROOF.bend", "def main() -> Type:\n  Type\n")
    val proof = myFixture.getFile.getVirtualFile
    val proofId = new FileId(
      Option(proof.getCanonicalPath).getOrElse(proof.getPath),
      proof.getCanonicalPath != null
    )
    val service = getProject.getService(classOf[BendCheckService])
    myFixture.performEditorAction("Bend.CheckCurrentFile")
    val deadline = System.nanoTime() + 15_000_000_000L
    while service.result(proofId).isEmpty && System.nanoTime() < deadline do
      Thread.sleep(50)
    val checked = service
      .result(proofId)
      .getOrElse(throw new AssertionError("PROOF guard check did not publish"))
    assertTrue(checked.details.contains("PROOF.bend must import ./LAWS.bend"))
    myFixture.openFileInEditor(laws.getVirtualFile)
    WriteCommandAction.runWriteCommandAction(
      getProject,
      new Runnable:
        override def run(): Unit =
          myFixture.getEditor.getDocument.setText("# unsaved laws\n")
    )
    assertFalse(
      "Sibling LAWS edit must stale a PROOF guard result",
      service.resultsFor(proofId).head.fresh
    )
