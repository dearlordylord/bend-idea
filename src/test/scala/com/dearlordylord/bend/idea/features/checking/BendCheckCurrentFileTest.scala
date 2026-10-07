package com.dearlordylord.bend.idea.features.checking

import com.dearlordylord.bend.idea.adapters.cli.RealBendCompilerFixture
import com.dearlordylord.bend.idea.analysis.api.{
  BendCheckService,
  BendExplicitCheckOutcome,
  BendExplicitCheckRunner
}
import com.dearlordylord.bend.idea.analysis.model.{
  BendCheckOutcome,
  BendCheckResult,
  BendCheckSnapshot,
  BendCompleteness,
  BendLocation,
  BendTextRange
}
import com.dearlordylord.bend.idea.analysis.model.BendReliance
import com.dearlordylord.bend.idea.model.FileId
import com.dearlordylord.bend.idea.features.documentation.BendDocumentationProvider
import com.dearlordylord.bend.idea.features.proofs.{
  BendProofRootState,
  BendProofRootStore
}
import com.dearlordylord.bend.idea.toolchain.api.{
  BendToolchainChoices,
  BendToolchainSettings
}
import com.dearlordylord.bend.idea.test.VfsTestRoots
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.fileEditor.{FileDocumentManager, FileEditorManager}
import com.intellij.psi.PsiDocumentManager
import com.intellij.openapi.editor.event.{DocumentEvent, DocumentListener}
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.*
import java.nio.file.{Files, Path}
import java.util.concurrent.{CountDownLatch, TimeUnit}
import scala.jdk.CollectionConverters.*

final class BendCheckCurrentFileTest extends BasePlatformTestCase:
  private var original: BendToolchainChoices = null
  private var directory: Path = null

  override def setUp(): Unit =
    super.setUp()
    VfsTestRoots.allowSystemTemporaryDirectory(getTestRootDisposable)
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

  def testNonExhaustiveMatchIsHighlightedAsCompilerError(): Unit =
    val text =
      "type Choice is Data:\n  A{}\n  B{}\ndef pick(x: Choice) -> Choice:\n  match x:\n    case A{}:\n      A{}\n"
    myFixture.configureByText("non-exhaustive.bend", text)
    val file = myFixture.getFile.getVirtualFile
    val id = new FileId(
      Option(file.getCanonicalPath).getOrElse(file.getPath),
      file.getCanonicalPath != null
    )

    myFixture.performEditorAction("Bend.CheckCurrentFile")
    val service = getProject.getService(classOf[BendCheckService])
    val until = System.nanoTime() + 15_000_000_000L
    while service.result(id).isEmpty && System.nanoTime() < until do
      Thread.sleep(50)
    val result = service
      .result(id)
      .getOrElse(throw new AssertionError("Match check did not publish"))
    assertEquals(BendCheckOutcome.Failed, result.outcome)
    assertTrue(result.details.contains("cases for B"))
    assertEquals(
      BendLocation.SourceLine(id, 4),
      result.diagnostics.head.location
    )

    val matchStart = text.indexOf("match x:")
    assertTrue(
      "The compiler's non-exhaustive match must be red in the editor",
      myFixture.doHighlighting().asScala.exists { info =>
        info.getSeverity == HighlightSeverity.ERROR &&
        info.getStartOffset <= matchStart && info.getEndOffset > matchStart &&
        info.getDescription.contains("cases for B")
      }
    )

  def testStructuredHelperHighlightsExactCurrentSourceRange(): Unit =
    val _ = RealBendCompilerFixture.inputs.writeStructuredLauncher(
      directory.resolve("bend")
    )
    val text = "import Base\n# 😀\ndef main() -> U32:\n  unknown_name\n"
    myFixture.configureByText("editor.bend", text)
    val file = myFixture.getFile.getVirtualFile
    val id = new FileId(
      Option(file.getCanonicalPath).getOrElse(file.getPath),
      file.getCanonicalPath != null
    )
    myFixture.performEditorAction("Bend.CheckCurrentFile")
    val until = System.nanoTime() + 15_000_000_000L
    while getProject.getService(classOf[BendCheckService]).result(id).isEmpty &&
      System.nanoTime() < until
    do Thread.sleep(50)
    val result = getProject
      .getService(classOf[BendCheckService])
      .result(id)
      .getOrElse(throw new AssertionError("Structured check did not publish"))
    val start = text.indexOf("unknown_name")
    assertEquals(
      BendLocation.SourceRange(id, BendTextRange(start, start + 12)),
      result.diagnostics.head.location
    )
    assertTrue(
      myFixture.doHighlighting().asScala.exists { info =>
        info.getStartOffset == start && info.getEndOffset == start + 12
      }
    )

  def testCompilerExpressionTypeInDocumentationExpiresOnEdit(): Unit =
    val _ = RealBendCompilerFixture.inputs.writeStructuredLauncher(
      directory.resolve("bend")
    )
    val text =
      "import Base\ndef id(A: Type, x: A) -> A:\n  x\ndef use() -> U32:\n  id(U32, 7)\n"
    myFixture.configureByText("types.bend", text)
    val file = myFixture.getFile.getVirtualFile
    val id = new FileId(
      Option(file.getCanonicalPath).getOrElse(file.getPath),
      file.getCanonicalPath != null
    )
    myFixture.performEditorAction("Bend.CheckCurrentFile")
    val service = getProject.getService(classOf[BendCheckService])
    val until = System.nanoTime() + 20_000_000_000L
    while service.result(id).forall(_.expressionTypes.isEmpty) &&
      System.nanoTime() < until
    do Thread.sleep(50)
    assertTrue(
      "type map did not publish",
      service
        .result(id)
        .exists(
          _.expressionTypes.nonEmpty
        )
    )
    val provider = new BendDocumentationProvider
    val psi = myFixture.getFile
    val appOffset = text.indexOf("id(U32, 7)") + "id(U32, 7".length
    val appElement = psi.findElementAt(appOffset)
    val appTarget = provider.getCustomDocumentationElement(
      myFixture.getEditor,
      psi,
      appElement,
      appOffset
    )
    assertNotNull(appTarget)
    val appDoc = provider.generateDoc(appTarget, appElement)
    assertTrue(appDoc.contains("Expression type (Bend compiler)"))
    assertTrue(appDoc.contains("<pre>U32</pre>"))
    val nameOffset = text.lastIndexOf("id(U32, 7)")
    val nameElement = psi.findElementAt(nameOffset)
    val nameTarget = provider.getCustomDocumentationElement(
      myFixture.getEditor,
      psi,
      nameElement,
      nameOffset
    )
    val nameDoc = provider.generateDoc(nameTarget, nameElement)
    assertTrue(nameDoc.contains("def id(A: Type, x: A) -&gt; A"))
    assertTrue(nameDoc.contains("Expression type (Bend compiler)"))
    val headerOffset = text.indexOf("def use")
    val headerElement = psi.findElementAt(headerOffset)
    val headerTarget = provider.getCustomDocumentationElement(
      myFixture.getEditor,
      psi,
      headerElement,
      headerOffset
    )
    assertNotNull(headerTarget)
    assertTrue(
      provider
        .generateDoc(headerTarget, headerElement)
        .contains("Bend syntax: def")
    )
    WriteCommandAction.runWriteCommandAction(
      getProject,
      new Runnable:
        override def run(): Unit =
          myFixture.getEditor.getDocument.insertString(0, "# changed\n")
    )
    val stale = provider.generateDoc(nameTarget, nameElement)
    assertFalse(
      stale != null && stale.contains("Expression type (Bend compiler)")
    )

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

  def testCheckUsesClosedModifiedAliasOfCanonicalDependency(): Unit =
    val target = directory.resolve("closed-module.bend").toAbsolutePath
    val alias = directory.resolve("closed-alias.bend")
    val saved = "def value() -> Type:\n  Type\n"
    val broken = "def value() -> Type:\n  unknown_closed_alias\n"
    Files.writeString(target, saved)
    Files.createSymbolicLink(alias, target.getFileName)
    val aliasFile =
      LocalFileSystem.getInstance().refreshAndFindFileByNioFile(alias)
    assertNotNull(aliasFile)
    myFixture.configureFromExistingVirtualFile(aliasFile)
    val document = myFixture.getEditor.getDocument
    def edit(text: String): Unit =
      WriteCommandAction.runWriteCommandAction(
        getProject,
        new Runnable:
          override def run(): Unit = document.setText(text)
      )
      PsiDocumentManager.getInstance(getProject).commitDocument(document)
    edit(broken)
    val editors = FileEditorManager.getInstance(getProject)
    editors.closeFile(aliasFile)
    assertFalse(editors.isFileOpen(aliasFile))
    assertTrue(FileDocumentManager.getInstance().isFileModified(aliasFile))
    val canonical = target.toRealPath()
    myFixture.configureByText(
      "closed-alias-root.bend",
      s"import $canonical as Module\ndef main() -> Type:\n  Module.value()\n"
    )
    val rootFile = myFixture.getFile.getVirtualFile
    val root = new FileId(rootFile.getCanonicalPath, true)
    val dependency = new FileId(canonical.toString, true)
    val service = getProject.getService(classOf[BendCheckService])
    def checkCurrent(text: String): BendCheckResult =
      myFixture.performEditorAction("Bend.CheckCurrentFile")
      val deadline = System.nanoTime() + 15_000_000_000L
      var result = service
        .result(root)
        .filter(r =>
          r.fresh && r.sources.exists(s => s.id == dependency && s.text == text)
        )
      while result.isEmpty && System.nanoTime() < deadline do
        Thread.sleep(25)
        result = service
          .result(root)
          .filter(r =>
            r.fresh && r.sources
              .exists(s => s.id == dependency && s.text == text)
          )
      result.getOrElse(
        throw new AssertionError(
          "Canonical root did not check its closed modified alias"
        )
      )
    val failed = checkCurrent(broken)
    assertEquals(failed.details, BendCheckOutcome.Failed, failed.outcome)
    assertTrue(failed.details.contains("unknown_closed_alias"))
    assertTrue(
      failed.sources.exists(s =>
        s.id == dependency && s.revision == document.getModificationStamp
      )
    )
    assertEquals(saved, Files.readString(target))
    edit(saved + "# repaired without reopening\n")
    assertFalse(
      "Closed document changes invalidate the canonical root",
      service.result(root).get.fresh
    )
    val repaired = checkCurrent(document.getText)
    assertEquals(repaired.details, BendCheckOutcome.Success, repaired.outcome)
    assertTrue(repaired.diagnostics.isEmpty)
    assertEquals(saved, Files.readString(target))

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

  def testSelectedRootChecksIndependentlyAndKeepsOtherRootResult(): Unit =
    myFixture.configureByText("active.bend", "def main() -> U32:\n  0\n")
    val good = myFixture.addFileToProject(
      "roots/good.bend",
      "import Base\ndef main() -> U32:\n  0\n"
    )
    val bad = myFixture.addFileToProject(
      "roots/bad.bend",
      "import Base\ndef main() -> U32:\n  missing_name\n"
    )
    val goodPath = good.getVirtualFile.getPath
    val badPath = bad.getVirtualFile.getPath
    val roots = getProject.getService(classOf[BendProofRootStore])
    roots.loadState(new BendProofRootState)
    roots.select(goodPath)
    val runner = getProject.getService(classOf[BendExplicitCheckRunner])

    def check(path: String): BendExplicitCheckOutcome =
      val completed = new CountDownLatch(1)
      var outcome: Option[BendExplicitCheckOutcome] = None
      runner.check(path, "Checking test root") { value =>
        outcome = Some(value)
        completed.countDown()
      }
      assertTrue(
        "Root check did not finish",
        completed.await(30, TimeUnit.SECONDS)
      )
      outcome.getOrElse(
        throw new AssertionError("Root check returned no result")
      )

    val first = check(goodPath) match
      case BendExplicitCheckOutcome.Published(result) => result
      case other                                      =>
        throw new AssertionError(s"Expected published good root, got $other")
    val firstId = first.key.root
    assertEquals(BendCheckOutcome.Success, first.outcome)
    assertEquals(List(goodPath), roots.selectedPaths)

    val second = check(badPath) match
      case BendExplicitCheckOutcome.Published(result) => result
      case other                                      =>
        throw new AssertionError(s"Expected published bad root, got $other")
    assertNotEquals(firstId, second.key.root)
    assertEquals(BendCheckOutcome.Failed, second.outcome)
    assertTrue(
      "Checking another root must not replace or stale the first result",
      getProject
        .getService(classOf[BendCheckService])
        .result(firstId)
        .exists(_.fresh)
    )

  def testLawAndTwoDifferentProofRootsHaveIndependentCompilerResults(): Unit =
    val laws = myFixture.addFileToProject(
      "proofs/LAWS.bend",
      "import Base\nlaw claim:\n  {0n == 0n : Nat}\n"
    )
    val firstProof = myFixture.addFileToProject(
      "proofs/one/PROOF.bend",
      "import Base\nimport ../LAWS.bend as Laws\ndef Laws.claim():\n  {==}\n"
    )
    val secondProof = myFixture.addFileToProject(
      "proofs/two/PROOF.bend",
      "import Base\nimport ../LAWS.bend as Laws\ndef refl() -> {0n == 0n : Nat}:\n  {==}\ndef Laws.claim():\n  refl()\n"
    )
    val roots = getProject.getService(classOf[BendProofRootStore])
    roots.loadState(new BendProofRootState)
    val runner = getProject.getService(classOf[BendExplicitCheckRunner])
    val service = getProject.getService(classOf[BendCheckService])

    def check(file: com.intellij.psi.PsiFile): BendCheckResult =
      val path = file.getVirtualFile.getPath
      roots.select(path)
      val completed = new CountDownLatch(1)
      var outcome: Option[BendExplicitCheckOutcome] = None
      runner.check(path, "Checking selected law/proof root") { value =>
        outcome = Some(value)
        completed.countDown()
      }
      assertTrue(
        s"Compiler check did not finish for $path",
        completed.await(30, TimeUnit.SECONDS)
      )
      outcome.getOrElse(
        throw new AssertionError(s"Compiler check returned no result for $path")
      ) match
        case BendExplicitCheckOutcome.Published(result) => result
        case other                                      =>
          throw new AssertionError(
            s"Expected a published result for $path, got $other"
          )

    val lawResult = check(laws)
    assertEquals(BendCompleteness.Incomplete, lawResult.completeness)
    assertEquals("Check incomplete", lawResult.status)
    val firstResult = check(firstProof)
    assertEquals(BendCheckOutcome.Success, firstResult.outcome)
    assertEquals(BendCompleteness.Complete, firstResult.completeness)
    val secondResult = check(secondProof)
    assertEquals(BendCheckOutcome.Success, secondResult.outcome)
    assertEquals(BendCompleteness.Complete, secondResult.completeness)
    List(lawResult, firstResult, secondResult).foreach { result =>
      assertTrue(
        s"Published result ${result.key.root} should be current before edits",
        service.isCurrent(result)
      )
    }

    List(lawResult, firstResult, secondResult).foreach { expected =>
      assertTrue(
        s"Checking another selected root must preserve ${expected.key.root}",
        service
          .result(expected.key.root)
          .exists(result =>
            result.fresh && result.outcome == expected.outcome &&
              result.completeness == expected.completeness
          )
      )
    }

    myFixture.openFileInEditor(laws.getVirtualFile)
    WriteCommandAction.runWriteCommandAction(
      getProject,
      new Runnable:
        override def run(): Unit = myFixture.getEditor.getDocument.setText(
          "import Base\nlaw claim:\n  {1n == 1n : Nat}\n"
        )
    )
    assertTrue(
      "Editing the shared law must stale its own result",
      service.result(lawResult.key.root).exists(result => !result.fresh)
    )
    assertFalse(service.isCurrent(lawResult))
    List(firstResult, secondResult).foreach { proofResult =>
      assertTrue(
        s"Editing LAWS.bend must stale dependent root ${proofResult.key.root}",
        service
          .result(proofResult.key.root)
          .exists(result => !result.fresh)
      )
      assertFalse(service.isCurrent(proofResult))
    }

  def testStatusWidgetShowsIncompleteWithBackgroundCheckingDisabled(): Unit =
    myFixture.configureByText(
      "goals.bend",
      "import Base\ndef identity(value: U32) -> U32:\n  ?need\n"
    )
    val file = myFixture.getFile.getVirtualFile
    val root = BendCheckPresentation.id(file)
    val widget = new BendCheckStatusWidget(getProject)
    assertEquals("Bend: Not checked", widget.getText)
    myFixture.performEditorAction("Bend.CheckCurrentFile")
    val service = getProject.getService(classOf[BendCheckService])
    val deadline = System.nanoTime() + 15_000_000_000L
    while service.result(root).isEmpty && System.nanoTime() < deadline do
      Thread.sleep(50)
    assertTrue(
      "Named-hole check did not publish",
      service.result(root).nonEmpty
    )
    assertEquals("Bend: Incomplete", widget.getText)
    myFixture.`type`(" ")
    assertEquals("Bend: Recheck needed", widget.getText)
    widget.dispose()

  def testStatusWidgetKeepsUnsafeRelianceVisibleAndTracksSelectedFile(): Unit =
    myFixture.configureByText(
      "unsafe.bend",
      "import Base\n@unsafe def main() -> U32:\n  1\n"
    )
    val root = BendCheckPresentation.id(myFixture.getFile.getVirtualFile)
    myFixture.performEditorAction("Bend.CheckCurrentFile")
    val service = getProject.getService(classOf[BendCheckService])
    val deadline = System.nanoTime() + 15_000_000_000L
    while service.result(root).isEmpty && System.nanoTime() < deadline do
      Thread.sleep(50)
    val widget = new BendCheckStatusWidget(getProject)
    assertEquals("Bend: Check passed · unsafe/foreign", widget.getText)
    myFixture.configureByText("other.bend", "import Base\n")
    assertEquals("Bend: Not checked", widget.getText)
    myFixture.configureByText("notes.txt", "notes")
    assertEquals("", widget.getText)
    widget.dispose()

  def testWidgetRefreshStopsAfterDisposal(): Unit =
    val updates = new java.util.concurrent.atomic.AtomicInteger()
    val statusBar = java.lang.reflect.Proxy
      .newProxyInstance(
        classOf[com.intellij.openapi.wm.StatusBar].getClassLoader,
        Array(classOf[com.intellij.openapi.wm.StatusBar]),
        new java.lang.reflect.InvocationHandler:
          override def invoke(
              proxy: Object,
              method: java.lang.reflect.Method,
              args: Array[Object]
          ): Object =
            method.getName match
              case "updateWidget" =>
                val _ = updates.incrementAndGet()
                null
              case "getProject" => getProject
              case "hashCode" => Integer.valueOf(System.identityHashCode(proxy))
              case "equals"   => java.lang.Boolean.valueOf(proxy eq args(0))
              case "toString" => "Test status bar"
              case _          => null
      )
      .asInstanceOf[com.intellij.openapi.wm.StatusBar]
    val widget = new BendCheckStatusWidget(getProject)
    widget.install(statusBar)
    com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents()
    val installed = updates.get()
    assertTrue(installed > 0)
    myFixture.configureByText("selected.bend", "import Base\n")
    com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents()
    assertTrue(
      "Selection must refresh the installed widget",
      updates.get() > installed
    )
    com.intellij.openapi.util.Disposer.dispose(widget)
    val disposedCount = updates.get()
    myFixture.configureByText("another.bend", "import Base\n")
    com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents()
    assertEquals(
      "Disposed widgets must not update the status bar",
      disposedCount,
      updates.get()
    )

  def testStatusWidgetIsRegisteredAndStatusActionIsEnabled(): Unit =
    val factories =
      com.intellij.openapi.wm.StatusBarWidgetFactory.EP_NAME.getExtensionList.asScala
    assertTrue(factories.exists(_.getId == BendCheckStatusWidget.Id))
    myFixture.configureByText("status.bend", "import Base\n")
    val action = com.intellij.openapi.actionSystem.ActionManager
      .getInstance()
      .getAction("Bend.CheckStatus")
    val context = com.intellij.openapi.actionSystem.impl.SimpleDataContext
      .builder()
      .add(com.intellij.openapi.actionSystem.CommonDataKeys.PROJECT, getProject)
      .add(
        com.intellij.openapi.actionSystem.CommonDataKeys.VIRTUAL_FILE,
        myFixture.getFile.getVirtualFile
      )
      .build()
    val event =
      com.intellij.openapi.actionSystem.AnActionEvent.createEvent(
        action,
        context,
        action.getTemplatePresentation.clone(),
        com.intellij.openapi.actionSystem.ActionPlaces.MAIN_MENU,
        com.intellij.openapi.actionSystem.ActionUiKind.MAIN_MENU,
        null
      )
    action.update(event)
    assertTrue(event.getPresentation.isEnabled)
    assertEquals("Show Bend Check Status", event.getPresentation.getText)
