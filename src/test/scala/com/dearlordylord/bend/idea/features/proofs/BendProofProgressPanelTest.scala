package com.dearlordylord.bend.idea.features.proofs

import com.dearlordylord.bend.idea.adapters.cli.RealBendCompilerFixture
import com.dearlordylord.bend.idea.analysis.api.BendExplicitCheckOutcome
import com.dearlordylord.bend.idea.analysis.model.{
  BendCheckOutcome,
  BendCompleteness
}
import com.dearlordylord.bend.idea.toolchain.api.{
  BendToolchainChoices,
  BendToolchainSettings
}
import com.intellij.openapi.application.{ApplicationManager, ModalityState}
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import java.nio.file.{Files, Path}
import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*

final class BendProofProgressPanelTest extends BasePlatformTestCase:
  private var originalChoices: BendToolchainChoices = null
  private var processDir: Path = null

  override def setUp(): Unit =
    super.setUp()
    val settings = ApplicationManager.getApplication
      .getService(classOf[BendToolchainSettings])
    originalChoices = settings.choices
    settings.update(originalChoices.copy(diagnosticsEnabled = false))
    processDir = Files.createTempDirectory("bend-progress-panel-")

  override def tearDown(): Unit =
    try
      ApplicationManager.getApplication
        .getService(classOf[BendToolchainSettings])
        .update(originalChoices)
      if processDir != null then
        val paths = Files.walk(processDir)
        try
          paths
            .sorted(java.util.Comparator.reverseOrder())
            .forEach(p => {
              val _ = Files.deleteIfExists(p)
            })
        finally paths.close()
    finally super.tearDown()

  def testPanelRefreshesRootsBuffersAndExternalCheckResult(): Unit =
    val unique = System.nanoTime()
    val first = myFixture.addFileToProject(
      s"$unique/first/PROOF.bend",
      "def first():\n  ?TODO\n"
    )
    val second = myFixture.addFileToProject(
      s"$unique/second/PROOF.bend",
      "def second():\n  ?other\n"
    )
    val firstPath = first.getVirtualFile.getPath
    val secondPath = second.getVirtualFile.getPath
    val store = getProject.getService(classOf[BendProofRootStore])
    store.select(firstPath)
    store.select(secondPath)
    val panel = new BendProofProgressPanel(getProject)
    try
      panel.refresh()
      val _ = awaitSnapshot(panel, secondPath)(_ => true)
      onUi(panel.selectRootForTests(firstPath))
      val initial =
        awaitSnapshot(panel, firstPath)(_.entries.exists(_.name == "?TODO"))

      myFixture.openFileInEditor(first.getVirtualFile)
      WriteCommandAction.runWriteCommandAction(
        getProject,
        new Runnable:
          override def run(): Unit =
            myFixture.getEditor.getDocument.setText(
              "def first():\n  ?edited\n"
            )
      )
      PsiDocumentManager.getInstance(getProject).commitAllDocuments()
      val edited = awaitSnapshot(panel, firstPath)(
        _.entries.exists(_.name == "?edited")
      )
      assertFalse(edited.entries.exists(_.name == "?TODO"))
      assertTrue(initial.entries.exists(_.name == "?TODO"))

      onUi(panel.selectRootForTests(secondPath))
      val secondSnapshot = awaitSnapshot(panel, secondPath)(
        _.entries.exists(_.name == "?other")
      )
      assertFalse(secondSnapshot.entries.exists(_.name == "?edited"))

      onUi(panel.selectRootForTests(firstPath))
      val _ = awaitSnapshot(panel, firstPath)(
        _.entries.exists(_.name == "?edited")
      )
      val executable = processDir.resolve("bend-fixture")
      val compilerBase = RealBendCompilerFixture.inputs.base
      val basePath = processDir.resolve("base.bend")
      Files.copy(compilerBase, basePath)
      Files.writeString(
        executable,
        "#!/bin/sh\n" +
          "case \"$1\" in\n" +
          "  --help) printf '%s\\n' '  bend <file.bend> --check-only check the file and its imports; run nothing'; exit 0 ;;\n" +
          "  base) cat '" + basePath + "'; exit $? ;;\n" +
          "  *) printf '%s\\n' 'Error: 1 TODO found.' 'The code is incomplete, and not a valid proof yet.'; exit 1 ;;\n" +
          "esac\n"
      )
      assertTrue(executable.toFile.setExecutable(true))
      ApplicationManager.getApplication
        .getService(classOf[BendToolchainSettings])
        .update(
          BendToolchainChoices(
            executable = executable.toString,
            baseSource = basePath.toString,
            diagnosticsEnabled = false
          )
        )
      val completion = new CountDownLatch(1)
      val outcome = new AtomicReference[Option[BendExplicitCheckOutcome]](None)
      new BendCheckProofRootAction().check(
        getProject,
        firstPath,
        value => {
          outcome.set(Some(value))
          completion.countDown()
        }
      )
      val callbackDeadline = System.nanoTime() + 10_000_000_000L
      while completion.getCount > 0 && System.nanoTime() < callbackDeadline do
        pumpUiEvents()
        Thread.sleep(25)
      assertEquals(
        "Explicit check callback should finish",
        0L,
        completion.getCount
      )
      outcome.get() match
        case Some(BendExplicitCheckOutcome.Published(result)) =>
          assertEquals(BendCheckOutcome.Failed, result.outcome)
          assertEquals(BendCompleteness.Incomplete, result.completeness)
        case other =>
          throw new AssertionError(s"Expected compiler result: $other")
      val afterCheck = awaitSnapshot(panel, firstPath)(
        _.checkedStatus == "Compiler result: incomplete; reliance unknown"
      )
      assertTrue(afterCheck.entries.exists(_.name == "?edited"))
    finally panel.dispose()

  def testDisposalCancelsAnInflightInventoryRead(): Unit =
    val root = myFixture.addFileToProject("PROOF.bend", "def main(): 0\n")
    val path = root.getVirtualFile.getPath
    getProject.getService(classOf[BendProofRootStore]).select(path)
    val entered = new CountDownLatch(1)
    val stopped = new CountDownLatch(1)
    val slow = new BendProofProgressReader(getProject):
      override def roots(): List[String] = List(path)
      override def read(
          rootPath: String,
          canceled: () => Boolean
      ): Option[BendProofProgressSnapshot] =
        entered.countDown()
        while !canceled() && !stopped.await(25, TimeUnit.MILLISECONDS) do ()
        stopped.countDown()
        None
    val panel = new BendProofProgressPanel(getProject, slow)
    panel.refresh()
    val readDeadline = System.nanoTime() + 5_000_000_000L
    while entered.getCount > 0 && System.nanoTime() < readDeadline do
      pumpUiEvents()
      Thread.sleep(20)
    assertTrue(
      "The panel should start a pooled inventory read",
      entered.getCount == 0
    )
    panel.dispose()
    assertTrue(
      "Disposal should cancel the read",
      stopped.await(5, TimeUnit.SECONDS)
    )
    assertTrue(panel.isDisposedForTests)
    assertTrue(panel.snapshotForTests.isEmpty)

  private def awaitSnapshot(
      panel: BendProofProgressPanel,
      path: String
  )(matches: BendProofProgressSnapshot => Boolean): BendProofProgressSnapshot =
    val until = System.nanoTime() + 10_000_000_000L
    var result = panel.snapshotForTests.filter(_.rootPath == path)
    while !result.exists(matches) && System.nanoTime() < until do
      pumpUiEvents()
      Thread.sleep(25)
      result = panel.snapshotForTests.filter(_.rootPath == path)
    assertTrue(
      s"Timed out waiting for progress data for $path: $result",
      result.exists(matches)
    )
    result.get

  private def onUi(action: => Unit): Unit =
    val application = ApplicationManager.getApplication
    if application.isDispatchThread then action
    else
      application.invokeAndWait(
        new Runnable:
          override def run(): Unit = action
        ,
        ModalityState.any()
      )

  private def pumpUiEvents(): Unit =
    if ApplicationManager.getApplication.isDispatchThread then
      UIUtil.dispatchAllInvocationEvents()
