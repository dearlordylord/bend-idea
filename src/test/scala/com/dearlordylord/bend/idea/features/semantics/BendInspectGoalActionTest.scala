package com.dearlordylord.bend.idea.features.semantics

import com.dearlordylord.bend.idea.adapters.cli.RealBendCompilerFixture
import com.dearlordylord.bend.idea.analysis.api.{
  BendCheckService,
  BendGoalAvailability,
  BendGoalQuery
}
import com.dearlordylord.bend.idea.analysis.model.BendCompleteness
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

final class BendInspectGoalActionTest extends BasePlatformTestCase:
  private var original: BendToolchainChoices = null
  private var directory: Path = null

  override def setUp(): Unit =
    super.setUp()
    VfsTestRoots.allowSystemTemporaryDirectory(getTestRootDisposable)
    val settings = ApplicationManager.getApplication.getService(
      classOf[BendToolchainSettings]
    )
    original = settings.choices
    directory = Files.createTempDirectory("bend-goal-editor-")
    val compiler = RealBendCompilerFixture.inputs
    val executable = directory.resolve("bend")
    val _ = compiler.writeStructuredLauncher(executable)
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
        val files = Files.walk(directory)
        try
          files
            .sorted(java.util.Comparator.reverseOrder())
            .forEach(p => {
              val _ = Files.deleteIfExists(p)
            })
        finally files.close()
    finally super.tearDown()

  def testNamedHoleShowsDependentCompilerGoalAtCurrentCaret(): Unit =
    val source = "def choose(A: Type, x: A) -> A:\n  ?need\n"
    myFixture.configureByText("proof.bend", source)
    val editor = myFixture.getEditor
    val offset = source.indexOf("?need") + 2
    editor.getCaretModel.moveToOffset(offset)
    val file = myFixture.getFile.getVirtualFile
    val id = new FileId(
      Option(file.getCanonicalPath).getOrElse(file.getPath),
      file.getCanonicalPath != null
    )
    myFixture.performEditorAction("Bend.InspectGoal")
    val until = System.nanoTime() + 15_000_000_000L
    while getProject.getService(classOf[BendCheckService]).result(id).isEmpty &&
      System.nanoTime() < until
    do Thread.sleep(50)
    val result = getProject
      .getService(classOf[BendCheckService])
      .result(id)
      .getOrElse(throw new AssertionError("Goal check did not publish"))
    assertEquals(BendCompleteness.Incomplete, result.completeness)
    BendGoalQuery.at(
      result,
      id,
      editor.getDocument.getText,
      editor.getDocument.getModificationStamp,
      offset
    ) match
      case BendGoalAvailability.Available(goal) =>
        assertEquals("A", goal.expectedType)
        assertEquals(List("A", "x"), goal.context.map(_.name))
        assertTrue(BendInspectGoalAction.render(goal).contains("x : A"))
      case other => fail(s"Expected a current compiler goal, got $other")

    assertTrue(
      BendGoalQuery
        .at(
          result,
          id,
          source + "# changed",
          editor.getDocument.getModificationStamp,
          offset
        )
        .isInstanceOf[BendGoalAvailability.Unavailable]
    )

  def testEditingWhileGoalWorkerRunsCancelsObsoleteQuery(): Unit =
    val marker = directory.resolve("goal-started")
    val _ = RealBendCompilerFixture.inputs.writeStructuredLauncher(
      directory.resolve("bend"),
      s"  printf x > \"${marker.toString}\"\n  sleep 6"
    )
    val source = "def choose(A: Type, x: A) -> A:\n  ?need\n"
    myFixture.configureByText("cancel.bend", source)
    myFixture.getEditor.getCaretModel.moveToOffset(source.indexOf("?need") + 2)
    val file = myFixture.getFile.getVirtualFile
    val id = new FileId(
      Option(file.getCanonicalPath).getOrElse(file.getPath),
      file.getCanonicalPath != null
    )
    myFixture.performEditorAction("Bend.InspectGoal")
    val startedBy = System.nanoTime() + 15_000_000_000L
    while !Files.exists(marker) && System.nanoTime() < startedBy do
      Thread.sleep(50)
    assertTrue("Goal helper did not start", Files.exists(marker))
    WriteCommandAction.runWriteCommandAction(
      getProject,
      new Runnable:
        override def run(): Unit =
          myFixture.getEditor.getDocument.setText(
            "def choose(A: Type, x: A) -> A:\n  ?changed\n"
          )
    )
    val checkService = getProject.getService(classOf[BendCheckService])
    val stoppedBy = System.nanoTime() + 15_000_000_000L
    while checkService.busy && System.nanoTime() < stoppedBy do Thread.sleep(50)
    assertFalse("Canceled goal worker remained active", checkService.busy)
    assertFalse(
      "Obsolete goal was published",
      checkService
        .result(id)
        .exists(result => result.fresh && result.goal.nonEmpty)
    )

  def testCompatibleLocalRanksFirstAndReplacesOnlySelectedHole(): Unit =
    val source = "def choose(A: Type, x: A) -> A:\n  ?need\n"
    myFixture.configureByText("ranking.bend", source)
    val editor = myFixture.getEditor
    editor.getCaretModel.moveToOffset(source.indexOf("?need") + 3)
    val file = myFixture.getFile.getVirtualFile
    val id = new FileId(
      Option(file.getCanonicalPath).getOrElse(file.getPath),
      file.getCanonicalPath != null
    )
    myFixture.performEditorAction("Bend.InspectGoal")
    val service = getProject.getService(classOf[BendCheckService])
    val deadline = System.nanoTime() + 20_000_000_000L
    while service
        .result(id)
        .flatMap(_.goal)
        .flatMap(_.compatibleBindings)
        .isEmpty && System.nanoTime() < deadline
    do Thread.sleep(50)
    assertEquals(
      Some(List("x")),
      service.result(id).flatMap(_.goal).flatMap(_.compatibleBindings)
    )
    val items = Option(myFixture.completeBasic()).getOrElse(
      Array.empty[com.intellij.codeInsight.lookup.LookupElement]
    )
    assertTrue(items.exists(_.getLookupString == "A"))
    val x = items
      .find(_.getLookupString == "x")
      .getOrElse(
        throw new AssertionError("Compatible local missing from completion")
      )
    assertEquals("x", items.head.getLookupString)
    myFixture.getLookup.setCurrentItem(x)
    myFixture.finishLookup('\t')
    assertEquals(source.replace("?need", "x"), editor.getDocument.getText)

  def testComparisonTimeoutKeepsSourceCompletionAvailable(): Unit =
    val _ = RealBendCompilerFixture.inputs.writeStructuredLauncher(
      directory.resolve("bend"),
      comparisonPrelude = "  sleep 6"
    )
    val source = "def choose(A: Type, x: A) -> A:\n  ?need\n"
    myFixture.configureByText("timeout.bend", source)
    val editor = myFixture.getEditor
    editor.getCaretModel.moveToOffset(source.indexOf("?need") + 3)
    val file = myFixture.getFile.getVirtualFile
    val id = new FileId(
      Option(file.getCanonicalPath).getOrElse(file.getPath),
      file.getCanonicalPath != null
    )
    myFixture.performEditorAction("Bend.InspectGoal")
    val service = getProject.getService(classOf[BendCheckService])
    val deadline = System.nanoTime() + 20_000_000_000L
    while service.result(id).flatMap(_.goal).isEmpty &&
      System.nanoTime() < deadline
    do Thread.sleep(50)
    assertTrue(service.result(id).flatMap(_.goal).nonEmpty)
    assertTrue(
      service.result(id).flatMap(_.goal).get.compatibleBindings.isEmpty
    )
    val items = Option(myFixture.completeBasic()).getOrElse(
      Array.empty[com.intellij.codeInsight.lookup.LookupElement]
    )
    assertTrue(items.exists(_.getLookupString == "A"))
    val x = items
      .find(_.getLookupString == "x")
      .getOrElse(
        throw new AssertionError("Source completion lost local x")
      )
    myFixture.getLookup.setCurrentItem(x)
    myFixture.finishLookup('\t')
    assertEquals(source.replace("?need", "x"), editor.getDocument.getText)
