package com.dearlordylord.bend.idea.features.checking

import com.dearlordylord.bend.idea.adapters.cli.RealBendCompilerFixture
import com.dearlordylord.bend.idea.adapters.intellij.{
  BendBackgroundChecking,
  BendCheckSession
}
import com.dearlordylord.bend.idea.analysis.api.BendCheckService
import com.dearlordylord.bend.idea.analysis.model.*
import com.dearlordylord.bend.idea.model.FileId
import com.dearlordylord.bend.idea.test.VfsTestRoots
import com.dearlordylord.bend.idea.toolchain.api.{
  BendToolchainChoices,
  BendToolchainSettings
}
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.*
import java.nio.file.{Files, Path}

final class BendBackgroundCheckingTest extends BasePlatformTestCase:
  private var original: BendToolchainChoices = null
  private var directory: Path = null

  override def setUp(): Unit =
    super.setUp()
    VfsTestRoots.allowSystemTemporaryDirectory(getTestRootDisposable)
    val settings = ApplicationManager.getApplication.getService(
      classOf[BendToolchainSettings]
    )
    original = settings.choices
    directory = Files.createTempDirectory("bend-background-editor-")
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

  private def id(file: VirtualFile): FileId =
    new FileId(
      Option(file.getCanonicalPath).getOrElse(file.getPath),
      file.getCanonicalPath != null
    )

  private def awaitResult(
      root: FileId,
      predicate: BendCheckResult => Boolean
  ): BendCheckResult =
    val service = getProject.getService(classOf[BendCheckService])
    val until = System.nanoTime() + 20_000_000_000L
    var found = service.result(root).filter(predicate)
    while found.isEmpty && System.nanoTime() < until do
      Thread.sleep(50)
      found = service.result(root).filter(predicate)
    found.getOrElse(
      throw new AssertionError(
        "No matching result for " + root.value +
          "; last=" + service
            .result(root)
            .map(r => (r.fresh, r.details.take(300)))
      )
    )

  def testTwoRootsKeepIndependentDiagnosticsOnOneDependency(): Unit =
    val dependency = myFixture.addFileToProject(
      "shared.bend",
      "import Base\ndef value() -> U32:\n  unknown_shared\n"
    )
    val first = myFixture.addFileToProject(
      "first.bend",
      "import ./shared.bend as Shared\ndef main() -> U32:\n  Shared.value()\n"
    )
    val second = myFixture.addFileToProject(
      "second.bend",
      "import ./shared.bend as Shared\ndef main() -> U32:\n  Shared.value()\n"
    )
    val service = getProject.getService(classOf[BendCheckService])
    myFixture.openFileInEditor(first.getVirtualFile)
    myFixture.performEditorAction("Bend.CheckCurrentFile")
    val firstResult = awaitResult(id(first.getVirtualFile), _.fresh)
    myFixture.openFileInEditor(second.getVirtualFile)
    myFixture.performEditorAction("Bend.CheckCurrentFile")
    val secondResult = awaitResult(id(second.getVirtualFile), _.fresh)
    List(firstResult, secondResult).foreach { result =>
      assertEquals(BendCheckOutcome.Failed, result.outcome)
      assertEquals(BendCompleteness.Unknown, result.completeness)
      assertEquals(BendReliance.Unknown, result.reliance)
      assertEquals("Check failed", result.status)
    }
    val dep = id(dependency.getVirtualFile)
    assertEquals(
      2,
      service
        .resultsFor(dep)
        .count(r =>
          r.fresh &&
            r.diagnostics.exists(_.message.contains("unknown_shared"))
        )
    )
    myFixture.openFileInEditor(dependency.getVirtualFile)
    assertEquals(
      2,
      myFixture
        .doHighlighting()
        .toArray
        .count(_.toString.contains("unknown_shared"))
    )
    myFixture.openFileInEditor(first.getVirtualFile)
    WriteCommandAction.runWriteCommandAction(
      getProject,
      new Runnable:
        override def run(): Unit = myFixture.getEditor.getDocument.setText(
          "import Base\ndef main() -> U32:\n  0\n"
        )
    )
    myFixture.performEditorAction("Bend.CheckCurrentFile")
    val _ = awaitResult(
      id(first.getVirtualFile),
      r =>
        r.fresh &&
          r.sources.forall(_.id != dep)
    )
    assertTrue(service.result(id(second.getVirtualFile)).exists(_.fresh))
    assertEquals(
      1,
      service
        .resultsFor(dep)
        .count(r =>
          r.fresh &&
            r.diagnostics.exists(_.message.contains("unknown_shared"))
        )
    )
    myFixture.openFileInEditor(dependency.getVirtualFile)
    assertEquals(
      1,
      myFixture
        .doHighlighting()
        .toArray
        .count(_.toString.contains("unknown_shared"))
    )

  def testRapidEditsAndDependencyOnlyEditPublishLatestBackgroundResult(): Unit =
    val dep = myFixture.addFileToProject(
      "value.bend",
      "import Base\ndef value() -> U32:\n  1\n"
    )
    myFixture.configureByText(
      "root.bend",
      "import ./value.bend as V\ndef main() -> U32:\n  V.value()\n"
    )
    val root = id(myFixture.getFile.getVirtualFile)
    val service = getProject.getService(classOf[BendCheckService])
    val settings = ApplicationManager.getApplication.getService(
      classOf[BendToolchainSettings]
    )
    getProject.getService(classOf[BendBackgroundChecking])
    settings.update(settings.choices.copy(diagnosticsEnabled = true))
    WriteCommandAction.runWriteCommandAction(
      getProject,
      new Runnable:
        override def run(): Unit =
          myFixture.getEditor.getDocument.setText(
            "import ./value.bend as V\ndef main() -> U32:\n  unknown_old\n"
          )
          myFixture.getEditor.getDocument.setText(
            "import ./value.bend as V\ndef main() -> U32:\n  V.value()\n"
          )
    )
    val _ = awaitResult(root, r => r.fresh && r.sources.exists(_.id == root))
    myFixture.openFileInEditor(dep.getVirtualFile)
    WriteCommandAction.runWriteCommandAction(
      getProject,
      new Runnable:
        override def run(): Unit = myFixture.getEditor.getDocument.setText(
          "import Base\ndef value() -> U32:\n  unknown_dependency\n"
        )
    )
    val changed = awaitResult(
      root,
      r =>
        r.fresh &&
          r.details.contains("unknown_dependency")
    )
    assertFalse(changed.details.contains("unknown_old"))
    settings.update(settings.choices.copy(diagnosticsEnabled = false))
    assertEquals(BendCheckingStatus.Disabled, service.status(root))

  def testDisablingAndDisposalCancelPendingWork(): Unit =
    myFixture.configureByText(
      "pending.bend",
      "import Base\ndef main() -> U32:\n  0\n"
    )
    val root = id(myFixture.getFile.getVirtualFile)
    val settings = ApplicationManager.getApplication.getService(
      classOf[BendToolchainSettings]
    )
    val scheduler = new BendBackgroundChecking(getProject)
    settings.update(settings.choices.copy(diagnosticsEnabled = true))
    scheduler.configurationChanged()
    scheduler.dispose()
    settings.update(settings.choices.copy(diagnosticsEnabled = false))
    Thread.sleep(750)
    assertTrue(
      getProject.getService(classOf[BendCheckService]).result(root).isEmpty
    )

  def testCanceledBackgroundProcessCannotPublishAfterNewEdit(): Unit =
    val settings = ApplicationManager.getApplication.getService(
      classOf[BendToolchainSettings]
    )
    val marker = directory.resolve("started")
    val slow = directory.resolve("slow-bend")
    val _ = RealBendCompilerFixture.writeLauncher(
      slow,
      RealBendCompilerFixture.markAndSleep(marker, 3)
    )
    myFixture.configureByText(
      "race.bend",
      "import Base\ndef main() -> U32:\n  unknown_old\n"
    )
    val root = id(myFixture.getFile.getVirtualFile)
    getProject.getService(classOf[BendBackgroundChecking])
    settings.update(
      settings.choices
        .copy(executable = slow.toString, diagnosticsEnabled = true)
    )
    val started = System.nanoTime() + 10_000_000_000L
    while !Files.exists(marker) && System.nanoTime() < started do
      Thread.sleep(25)
    assertTrue("The first process must start", Files.exists(marker))
    WriteCommandAction.runWriteCommandAction(
      getProject,
      new Runnable:
        override def run(): Unit = myFixture.getEditor.getDocument.setText(
          "import Base\ndef main() -> U32:\n  unknown_new\n"
        )
    )
    val checked =
      awaitResult(root, r => r.fresh && r.details.contains("unknown_new"))
    assertFalse(checked.details.contains("unknown_old"))
    assertTrue(
      "Edit must start a replacement process",
      Files.size(marker) >= 2L
    )

  def testManualActionPreemptsRunningBackgroundProcess(): Unit =
    val settings = ApplicationManager.getApplication.getService(
      classOf[BendToolchainSettings]
    )
    val marker = directory.resolve("manual-started")
    val slow = directory.resolve("manual-slow-bend")
    val _ = RealBendCompilerFixture.writeLauncher(
      slow,
      RealBendCompilerFixture.markAndSleep(marker, 3)
    )
    myFixture.configureByText(
      "manual.bend",
      "import Base\ndef main() -> U32:\n  unknown_manual\n"
    )
    val root = id(myFixture.getFile.getVirtualFile)
    getProject.getService(classOf[BendBackgroundChecking])
    settings.update(
      settings.choices
        .copy(executable = slow.toString, diagnosticsEnabled = true)
    )
    val started = System.nanoTime() + 10_000_000_000L
    while !Files.exists(marker) && System.nanoTime() < started do
      Thread.sleep(25)
    assertTrue(Files.exists(marker))
    myFixture.performEditorAction("Bend.CheckCurrentFile")
    val checked =
      awaitResult(root, r => r.fresh && r.details.contains("unknown_manual"))
    assertEquals(BendCheckOutcome.Failed, checked.outcome)
    assertTrue(
      "Manual action must start its own process",
      Files.size(marker) >= 2L
    )

  def testCapturedSameRootBackgroundCheckCannotConsumeManualReservation()
      : Unit =
    val settings = ApplicationManager.getApplication.getService(
      classOf[BendToolchainSettings]
    )
    val backgroundText =
      "import Base\ndef main() -> U32:\n  unknown_background\n"
    val manualText = "import Base\ndef main() -> U32:\n  unknown_manual\n"
    val file = myFixture
      .configureByText("same-root-capture.bend", backgroundText)
      .getVirtualFile
    val root = id(file)
    val backgroundSnapshot = BendCheckSnapshot(
      root,
      file.getPath,
      backgroundText,
      1L,
      settings.selection
    )
    val manualSnapshot =
      backgroundSnapshot.copy(text = manualText, sourceRevision = 2L)
    val session = new BendCheckSession(getProject)
    try
      val control = session
      val scheduled = control
        .requestBackground(root)
        .getOrElse(
          throw new AssertionError(
            "Background request must produce a schedule token"
          )
        )
      val ticket = control
        .backgroundTimerFired(root, scheduled.token)
        .getOrElse(
          throw new AssertionError("Current background timer must become due")
        )
      val backgroundReservation = control
        .beginBackground(
          ticket,
          backgroundSnapshot,
          callback => () => (),
          () => true
        )
        .getOrElse(
          throw new AssertionError("Background capture must reserve the worker")
        )
      val manualReservation = session
        .begin(manualSnapshot, callback => () => (), () => true)
        .getOrElse(
          throw new AssertionError(
            "Manual request must preempt the reserved capture"
          )
        )
      assertTrue(session.busy)

      assertTrue(
        "Stale capture must not dispatch against the manual reservation",
        session
          .check(backgroundSnapshot, backgroundReservation, () => false)
          .isEmpty
      )
      assertTrue(
        "Stale capture must not release the manual worker slot",
        session.busy
      )
      assertTrue("Stale capture must not publish", session.result(root).isEmpty)

      val checked = session
        .check(manualSnapshot, manualReservation, () => false)
        .getOrElse(
          throw new AssertionError(
            "Manual reservation must complete its own check"
          )
        )
      assertEquals(manualSnapshot.sourceRevision, checked.key.sourceRevision)
      assertTrue(checked.details.contains("unknown_manual"))
      assertFalse(checked.details.contains("unknown_background"))
      assertEquals(Some(checked), session.result(root))
      assertFalse("Completed manual check must release its slot", session.busy)
    finally Disposer.dispose(session)

  def testDependencyEditDuringFirstRootCheckSchedulesReplacement(): Unit =
    val settings = ApplicationManager.getApplication.getService(
      classOf[BendToolchainSettings]
    )
    val marker = directory.resolve("dependency-started")
    val slow = directory.resolve("dependency-slow-bend")
    val _ = RealBendCompilerFixture.writeLauncher(
      slow,
      RealBendCompilerFixture.markAndSleep(marker, 3)
    )
    val dep = myFixture.addFileToProject(
      "early.bend",
      "import Base\ndef value() -> U32:\n  unknown_old_dependency\n"
    )
    myFixture.openFileInEditor(dep.getVirtualFile)
    myFixture.configureByText(
      "early_root.bend",
      "import ./early.bend as Early\ndef main() -> U32:\n  Early.value()\n"
    )
    FileEditorManager.getInstance(getProject).closeFile(dep.getVirtualFile)
    val root = id(myFixture.getFile.getVirtualFile)
    getProject.getService(classOf[BendBackgroundChecking])
    settings.update(
      settings.choices
        .copy(executable = slow.toString, diagnosticsEnabled = true)
    )
    val started = System.nanoTime() + 10_000_000_000L
    val service = getProject.getService(classOf[BendCheckService])
    while (!Files.exists(marker) || service.status(
        root
      ) != BendCheckingStatus.Checking) &&
      System.nanoTime() < started
    do Thread.sleep(25)
    assertTrue(
      "First graph check must reach the compiler",
      Files.exists(marker)
    )
    assertEquals(
      "Marker must belong to the root process",
      BendCheckingStatus.Checking,
      service.status(root)
    )
    assertTrue("No root result has published yet", service.result(root).isEmpty)
    myFixture.openFileInEditor(dep.getVirtualFile)
    WriteCommandAction.runWriteCommandAction(
      getProject,
      new Runnable:
        override def run(): Unit = myFixture.getEditor.getDocument.setText(
          "import Base\ndef value() -> U32:\n  unknown_new_dependency\n"
        )
    )
    val checked = awaitResult(
      root,
      r => r.fresh && r.details.contains("unknown_new_dependency")
    )
    assertFalse(checked.details.contains("unknown_old_dependency"))

  def testCompilerReplacementDuringFirstCheckRetriesWithoutVfsEvent(): Unit =
    val settings = ApplicationManager.getApplication.getService(
      classOf[BendToolchainSettings]
    )
    val marker = directory.resolve("replacement-started")
    val slow = directory.resolve("replacement-bend")
    val _ = RealBendCompilerFixture.writeLauncher(
      slow,
      RealBendCompilerFixture.markAndSleep(marker, 2)
    )
    myFixture.configureByText(
      "replacement.bend",
      "import Base\ndef main() -> U32:\n  unknown_replacement\n"
    )
    val root = id(myFixture.getFile.getVirtualFile)
    getProject.getService(classOf[BendBackgroundChecking])
    settings.update(
      settings.choices
        .copy(executable = slow.toString, diagnosticsEnabled = true)
    )
    val started = System.nanoTime() + 10_000_000_000L
    val service = getProject.getService(classOf[BendCheckService])
    while (!Files.exists(marker) || service.status(
        root
      ) != BendCheckingStatus.Checking) &&
      System.nanoTime() < started
    do Thread.sleep(25)
    assertTrue("First root process must be running", Files.exists(marker))
    assertEquals(BendCheckingStatus.Checking, service.status(root))
    assertTrue(service.result(root).isEmpty)
    // Direct filesystem replacement deliberately supplies no VFS event.
    Files.writeString(
      slow,
      "\n# same-path replacement\n",
      java.nio.file.StandardOpenOption.APPEND
    )
    val checked = awaitResult(
      root,
      r =>
        r.fresh &&
          r.details.contains("unknown_replacement")
    )
    assertEquals(BendCheckOutcome.Failed, checked.outcome)
    assertTrue(
      "Rejected first result must trigger another compiler invocation",
      Files.size(marker) >= 2L
    )

  def testDisposingActiveSessionTerminatesProcessAndDropsResult(): Unit =
    exerciseActiveDisposal()

  private def exerciseActiveDisposal(): Unit =
    val settings = ApplicationManager.getApplication.getService(
      classOf[BendToolchainSettings]
    )
    val marker = directory.resolve("dispose-started")
    val slow = directory.resolve("dispose-slow-bend")
    Files.writeString(
      slow,
      "#!/bin/sh\nif [ \"$1\" = \"--help\" ]; then echo '  bend <file.bend> --check-only check the file and its imports; run nothing'; exit 0; fi\nprintf x >> '" + marker + "'\nsleep 10\n"
    )
    slow.toFile.setExecutable(true)
    settings.update(
      settings.choices
        .copy(executable = slow.toString, diagnosticsEnabled = false)
    )
    myFixture.configureByText("dispose.bend", "def main() -> Type:\n  Type\n")
    val file = myFixture.getFile.getVirtualFile
    val document = myFixture.getEditor.getDocument
    val root = id(file)
    val snapshot = BendCheckSnapshot(
      root,
      file.getPath,
      document.getText,
      document.getModificationStamp,
      settings.selection
    )
    val transient = new BendCheckSession(getProject)
    val reservation = transient
      .begin(snapshot, callback => () => (), () => true)
      .getOrElse(
        throw new AssertionError("Transient check must reserve its worker")
      )
    val worker = new Thread(new Runnable:
      override def run(): Unit = {
        val _ = transient.check(snapshot, reservation, () => false); ()
      })
    worker.start()
    try
      val started = System.nanoTime() + 10_000_000_000L
      while !Files.exists(marker) && System.nanoTime() < started do
        Thread.sleep(25)
      assertTrue("Process must start before disposal", Files.exists(marker))
    finally Disposer.dispose(transient)
    worker.join(5000)
    assertFalse("Disposal must terminate the process", worker.isAlive)
    assertTrue(
      "Disposed session must drop all results",
      transient.result(root).isEmpty
    )
