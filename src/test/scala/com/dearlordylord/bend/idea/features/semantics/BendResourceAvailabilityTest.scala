package com.dearlordylord.bend.idea.features.semantics

import com.dearlordylord.bend.idea.adapters.cli.RealBendCompilerFixture
import com.dearlordylord.bend.idea.analysis.api.{
  BendCheckService,
  BendProofEditValidator,
  BendResourceCandidateStatus,
  BendResourceReport,
  BendResourceReportOutcome
}
import com.dearlordylord.bend.idea.model.FileId
import com.dearlordylord.bend.idea.test.VfsTestRoots
import com.dearlordylord.bend.idea.toolchain.api.{
  BendToolchainChoices,
  BendToolchainSettings
}
import com.dearlordylord.bend.idea.workspace.api.BendSourceCatalog
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import java.nio.file.{Files, Path}
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*

final class BendResourceAvailabilityTest extends BasePlatformTestCase:
  private var original: BendToolchainChoices = null
  private var directory: Path = null

  override def setUp(): Unit =
    super.setUp()
    VfsTestRoots.allowSystemTemporaryDirectory(getTestRootDisposable)
    val settings = ApplicationManager.getApplication.getService(
      classOf[BendToolchainSettings]
    )
    original = settings.choices
    directory = Files.createTempDirectory("bend-resource-editor-")
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

  private def goalCheck(source: String): (
      com.dearlordylord.bend.idea.analysis.model.BendCheckResult,
      com.dearlordylord.bend.idea.analysis.model.BendGoal
  ) =
    val path = Files.createTempFile(directory, "resources-", ".bend")
    Files.writeString(path, source)
    val physical = LocalFileSystem
      .getInstance()
      .refreshAndFindFileByNioFile(
        path
      )
    assertNotNull(physical)
    myFixture.openFileInEditor(physical)
    myFixture.getEditor.getCaretModel.moveToOffset(source.indexOf("?need") + 2)
    val file = myFixture.getFile.getVirtualFile
    val id = new FileId(
      Option(file.getCanonicalPath).getOrElse(file.getPath),
      file.getCanonicalPath != null
    )
    myFixture.performEditorAction("Bend.InspectGoal")
    val service = getProject.getService(classOf[BendCheckService])
    val deadline = System.nanoTime() + 20_000_000_000L
    var result = service
      .result(id)
      .filter(
        _.goal.exists(
          _.compatibleBindings.nonEmpty
        )
      )
    while result.isEmpty && System.nanoTime() < deadline do
      UIUtil.dispatchAllInvocationEvents()
      Thread.sleep(50)
      result = service
        .result(id)
        .filter(
          _.goal.exists(
            _.compatibleBindings.nonEmpty
          )
        )
    assertTrue(
      "expected a compiler goal and binder comparison",
      result.nonEmpty
    )
    (result.get, result.get.goal.get)

  private def report(source: String): BendResourceReport =
    val (check, goal) = goalCheck(source)
    val completed = new AtomicReference[BendResourceReportOutcome]()
    getProject
      .getService(classOf[BendProofEditValidator])
      .probeGoalResources(check, goal)(value => completed.set(value))
    val deadline = System.nanoTime() + 30_000_000_000L
    while completed.get() == null && System.nanoTime() < deadline do
      UIUtil.dispatchAllInvocationEvents()
      Thread.sleep(50)
    completed.get() match
      case BendResourceReportOutcome.Available(value) => value
      case other => throw new AssertionError(s"No resource report: $other")

  private def status(
      report: BendResourceReport,
      name: String
  ): BendResourceCandidateStatus =
    report.bindings.find(_.name == name).get.status

  def testAffineReuseRejectedButSeparateBranchUseAccepted(): Unit =
    val repeated = report(
      "import Base\ndef test(x: U32) -> U32 & U32:\n  (x, ?need)\n"
    )
    status(repeated, "x") match
      case BendResourceCandidateStatus.Rejected(details) =>
        assertTrue(details.contains("consumed more than once"))
      case other => fail(s"Expected whole-root rejection, got $other")
    assertTrue(BendExplainResourcesAction.render(repeated).contains("affine"))

    val branch = report(
      "import Base\ndef test(x: U32, flag: Bool) -> U32:\n" +
        "  match flag:\n    case True{}:\n      x\n" +
        "    case False{}:\n      ?need\n"
    )
    assertTrue(
      status(branch, "x").isInstanceOf[
        BendResourceCandidateStatus.AcceptedComplete
      ]
    )

  def testClosureReusableErasedAndUnusedAffineRemainDistinct(): Unit =
    val closure = report(
      "import Base\ndef test(x: U32) -> U32 & (U32 -> U32):\n" +
        "  (x, y => ?need)\n"
    )
    assertTrue(
      status(closure, "x").isInstanceOf[
        BendResourceCandidateStatus.Rejected
      ]
    )
    assertTrue(
      status(closure, "y").isInstanceOf[
        BendResourceCandidateStatus.AcceptedComplete
      ]
    )
    val reusable = report(
      "import Base\ndef test(+x: U32) -> U32 & U32:\n  (x, ?need)\n"
    )
    assertEquals("+", reusable.bindings.find(_.name == "x").get.quantity)
    assertTrue(
      status(reusable, "x").isInstanceOf[
        BendResourceCandidateStatus.AcceptedComplete
      ]
    )
    val erased = report(
      "import Base\ndef test(-x: U32) -> U32:\n  ?need\n"
    )
    assertEquals("-", erased.bindings.find(_.name == "x").get.quantity)
    assertFalse(
      status(erased, "x").isInstanceOf[
        BendResourceCandidateStatus.AcceptedComplete
      ]
    )
    val unused = report(
      "import Base\ndef test(x: U32, y: U32) -> U32:\n  ?need\n"
    )
    assertTrue(
      status(unused, "x").isInstanceOf[
        BendResourceCandidateStatus.AcceptedComplete
      ]
    )
    assertTrue(
      status(unused, "y").isInstanceOf[
        BendResourceCandidateStatus.AcceptedComplete
      ]
    )

  def testProofContextAndStaleEdit(): Unit =
    val proof = report(
      "import Base\ndef test(x: U32, p: {x == x : U32}) -> {x == x : U32}:\n  ?need\n"
    )
    assertTrue(
      status(proof, "p").isInstanceOf[
        BendResourceCandidateStatus.AcceptedComplete
      ]
    )
    assertEquals(
      BendResourceCandidateStatus.NotTypeCompatible,
      status(proof, "x")
    )
    val typePosition = report(
      "import Base\ndef typeProof(A: Type) -> Type:\n  ?need\n"
    )
    assertTrue(
      status(typePosition, "A").isInstanceOf[
        BendResourceCandidateStatus.AcceptedComplete
      ]
    )

    val (check, goal) = goalCheck(
      "import Base\ndef test(x: U32) -> U32:\n  ?need\n"
    )
    WriteCommandAction.runWriteCommandAction(
      getProject,
      new Runnable:
        override def run(): Unit =
          myFixture.getEditor.getDocument.insertString(0, "# changed\n")
    )
    FileDocumentManager
      .getInstance()
      .saveDocument(
        myFixture.getEditor.getDocument
      )
    val completed = new AtomicReference[BendResourceReportOutcome]()
    getProject
      .getService(classOf[BendProofEditValidator])
      .probeGoalResources(check, goal)(value => completed.set(value))
    val deadline = System.nanoTime() + 20_000_000_000L
    while completed.get() == null && System.nanoTime() < deadline do
      UIUtil.dispatchAllInvocationEvents()
      Thread.sleep(50)
    assertTrue(
      "stale resource report should be unavailable",
      completed
        .get()
        .isInstanceOf[
          BendResourceReportOutcome.Unavailable
        ]
    )

  def testEditingDuringCandidateCheckCancelsResourceReport(): Unit =
    val armed = directory.resolve("arm-candidate")
    val marker = directory.resolve("candidate-started")
    val prelude =
      s"""if [ "$${2:-}" = "--check-only" ] && [ -e "${armed.toString}" ]; then
          |    printf x > "${marker.toString}"
          |    sleep 6
          |fi""".stripMargin
    val _ = RealBendCompilerFixture.inputs.writeStructuredLauncher(
      directory.resolve("bend"),
      checkOnlyPrelude = prelude
    )
    val source = "import Base\ndef test(x: U32) -> U32:\n  ?need\n"
    val (check, goal) = goalCheck(source)
    Files.writeString(armed, "x")
    val completed = new AtomicReference[BendResourceReportOutcome]()
    val validator = getProject.getService(classOf[BendProofEditValidator])
    val started = new Thread(() =>
      validator.probeGoalResources(check, goal)(value => completed.set(value))
    )
    started.start()
    val startedBy = System.nanoTime() + 15_000_000_000L
    while !Files.exists(marker) && System.nanoTime() < startedBy do
      Thread.sleep(50)
    assertTrue("candidate compiler probe did not start", Files.exists(marker))
    assertNull("resource report completed before edit", completed.get())
    WriteCommandAction.runWriteCommandAction(
      getProject,
      new Runnable:
        override def run(): Unit =
          myFixture.getEditor.getDocument.insertString(0, "# changed\n")
    )
    FileDocumentManager
      .getInstance()
      .saveDocument(
        myFixture.getEditor.getDocument
      )
    val captured = check.sources.find(_.id == goal.source).get
    assertEquals(
      myFixture.getEditor.getDocument.getText,
      getProject
        .getService(classOf[BendSourceCatalog])
        .source(captured.path)
        .get
        .text
    )
    val completedBy = System.nanoTime() + 15_000_000_000L
    while completed.get() == null && System.nanoTime() < completedBy do
      UIUtil.dispatchAllInvocationEvents()
      Thread.sleep(50)
    assertTrue(
      "in-flight resource report should be unavailable after edit: " +
        Option(completed.get()).map(_.getClass.getSimpleName),
      completed.get().isInstanceOf[BendResourceReportOutcome.Unavailable]
    )
    assertEquals(
      "# changed\n" + source,
      myFixture.getEditor.getDocument.getText
    )

  def testUnsavedDependencyEditMakesCandidateUnavailable(): Unit =
    val dependencyPath = directory.resolve("candidate-dependency.bend")
    Files.writeString(dependencyPath, "# initial dependency\n")
    val dependency = LocalFileSystem
      .getInstance()
      .refreshAndFindFileByNioFile(dependencyPath)
    assertNotNull(dependency)
    val source =
      "import Base\nimport ./candidate-dependency.bend as Dep\n" +
        "def test(x: U32) -> U32:\n  ?need\n"
    val (check, goal) = goalCheck(source)
    myFixture.openFileInEditor(dependency)
    WriteCommandAction.runWriteCommandAction(
      getProject,
      new Runnable:
        override def run(): Unit = myFixture.getEditor.getDocument.setText(
          "# unsaved dependency edit\n"
        )
    )
    val completed = new AtomicReference[BendResourceReportOutcome]()
    getProject
      .getService(classOf[BendProofEditValidator])
      .probeGoalResources(check, goal)(value => completed.set(value))
    val deadline = System.nanoTime() + 20_000_000_000L
    while completed.get() == null && System.nanoTime() < deadline do
      UIUtil.dispatchAllInvocationEvents()
      Thread.sleep(50)
    assertTrue(
      "Edited dependency must make the candidate unavailable",
      completed.get().isInstanceOf[BendResourceReportOutcome.Unavailable]
    )

  def testEditorActionProbesCandidateWithoutEditingSource(): Unit =
    val armed = directory.resolve("arm-action")
    val marker = directory.resolve("action-candidate-started")
    val prelude =
      s"""if [ "$${2:-}" = "--check-only" ] && [ -e "${armed.toString}" ] && ! grep -q '?need' "$$1"; then
          |  printf x > "${marker.toString}"
          |fi""".stripMargin
    val _ = RealBendCompilerFixture.inputs.writeStructuredLauncher(
      directory.resolve("bend"),
      checkOnlyPrelude = prelude
    )
    val source = "import Base\ndef test(x: U32) -> U32:\n  ?need\n"
    val (check, goal) = goalCheck(source)
    assertEquals(
      myFixture.getEditor.getDocument.getModificationStamp,
      check.sources.find(_.id == goal.source).get.revision
    )
    Files.writeString(armed, "x")
    myFixture.performEditorAction("Bend.ExplainResources")
    val deadline = System.nanoTime() + 20_000_000_000L
    while !Files.exists(marker) && System.nanoTime() < deadline do
      UIUtil.dispatchAllInvocationEvents()
      Thread.sleep(50)
    assertTrue(
      "resource action did not check a candidate",
      Files.exists(marker)
    )
    assertEquals(source, myFixture.getEditor.getDocument.getText)
