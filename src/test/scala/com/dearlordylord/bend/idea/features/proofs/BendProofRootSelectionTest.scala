package com.dearlordylord.bend.idea.features.proofs

import com.intellij.openapi.actionSystem.{
  ActionManager,
  ActionGroup,
  ActionPlaces,
  ActionUiKind,
  ActionUpdateThread,
  AnActionEvent,
  CommonDataKeys,
  DataContext
}
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.testFramework.ServiceContainerUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.dearlordylord.bend.idea.workspace.api.{
  BendNamedPathInventory,
  BendPathInventoryStatus,
  BendPathEntry,
  BendWorkspacePaths
}
import org.junit.Assert.*
import java.nio.file.Path
import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicBoolean
import scala.io.Source

final class BendProofRootSelectionTest extends BasePlatformTestCase:
  def testConventionalRootsAreSuggestedAndSiblingProofComesFirst(): Unit =
    val directory = Path.of("/workspace/examples").toAbsolutePath.normalize()
    val laws = directory.resolve("LAWS.bend").toString
    val sibling = directory.resolve("PROOF.bend").toString
    val other = directory.resolve("other/PROOF.bend").toString
    val saved = directory.resolve("custom.bend").toString
    assertEquals(
      List(saved, sibling, other),
      BendProofRootSelection.candidates(
        Some(laws),
        List(saved),
        List(other, sibling)
      )
    )

  def testCurrentConventionalRootAndBrowseChoiceAreAvailable(): Unit =
    val root = Path.of("/workspace/project/PROOF.bend").toString
    val choices = BendProofRootSelection.choices(
      BendProofRootSelection.candidates(Some(root), Nil, Nil)
    )
    assertEquals(List(Some(root), None), choices.map(_.path))
    assertEquals("Browse for a Bend proof root…", choices.last.toString)

  def testMenuActionFindsProofRootsOutsideTheUiThread(): Unit =
    val proof = myFixture.configureByText("PROOF.bend", "import Base\n")
    val project = getProject
    val editor = myFixture.getEditor
    val reached = new CountDownLatch(1)
    val searchedOnUiThread = new AtomicBoolean(true)
    val hadTaskIndicator = new AtomicBoolean(false)
    val paths = new BendWorkspacePaths:
      override def children(path: String, limit: Int): List[BendPathEntry] =
        Nil

      override def filesNamed(
          name: String,
          limit: Int
      ): BendNamedPathInventory =
        val onUiThread = ApplicationManager.getApplication.isDispatchThread
        searchedOnUiThread.set(onUiThread)
        if onUiThread then
          reached.countDown()
          throw new AssertionError(
            "Proof root inventory ran in the menu callback"
          )
        val indicator = Option(
          ProgressManager.getInstance().getProgressIndicator
        )
        hadTaskIndicator.set(indicator.nonEmpty)
        indicator.foreach(_.cancel())
        reached.countDown()
        BendNamedPathInventory(Nil, BendPathInventoryStatus.Complete)

      override def filesWithExtension(
          extension: String,
          limit: Int
      ): Either[String, List[String]] = Right(Nil)
    ServiceContainerUtil.replaceService(
      project,
      classOf[BendWorkspacePaths],
      paths,
      getTestRootDisposable
    )
    val context = new DataContext:
      override def getData(id: String): AnyRef =
        if id == CommonDataKeys.PROJECT.getName then project
        else if id == CommonDataKeys.VIRTUAL_FILE.getName then
          proof.getVirtualFile
        else if id == CommonDataKeys.EDITOR.getName then editor
        else null
    val action = new BendCheckProofRootAction
    val event = AnActionEvent.createEvent(
      action,
      context,
      action.getTemplatePresentation.clone(),
      ActionPlaces.MAIN_MENU,
      ActionUiKind.MAIN_MENU,
      null
    )

    assertTrue(
      "Menu action fixture must run on the UI thread",
      ApplicationManager.getApplication.isDispatchThread
    )
    action.actionPerformed(event)

    assertTrue("Root inventory should run", reached.await(5, TimeUnit.SECONDS))
    assertFalse(
      "Root inventory must be outside the UI thread",
      searchedOnUiThread.get
    )
    assertTrue(
      "Root inventory should belong to a cancellable task",
      hadTaskIndicator.get
    )

  def testPartialRootInventoryKeepsSavedAndOpenCandidatesAndStatus(): Unit =
    val inventory = BendProofRootSelection.inventory(
      List("/workspace/saved/PROOF.bend"),
      BendNamedPathInventory(
        List("/workspace/open/PROOF.bend"),
        BendPathInventoryStatus.IndexUnavailable
      ),
      128
    )
    assertEquals(
      List(
        "/workspace/saved/PROOF.bend",
        "/workspace/open/PROOF.bend"
      ),
      inventory.paths
    )
    assertEquals(BendPathInventoryStatus.IndexUnavailable, inventory.status)

  def testRootInventoryReportsCombinedIndexAndLimitConditions(): Unit =
    val inventory = BendProofRootSelection.inventory(
      List("/workspace/saved/PROOF.bend"),
      BendNamedPathInventory(
        List("/workspace/open/PROOF.bend"),
        BendPathInventoryStatus.IndexUnavailable
      ),
      1
    )
    assertEquals(List("/workspace/saved/PROOF.bend"), inventory.paths)
    assertEquals(
      BendPathInventoryStatus.IndexUnavailableAndCapped,
      inventory.status
    )

  def testNavigationUsesSavedRootsAndDoesNotTreatALawAsARoot(): Unit =
    val law =
      myFixture.addFileToProject("laws/core.bend", "law claim:\n  Type\n")
    val saved = myFixture.addFileToProject("dnd/PROOF.bend", "def main(): 0\n")
    val unselected = myFixture.addFileToProject(
      "samples/PROOF.bend",
      "def main(): 0\n"
    )
    val roots = getProject.getService(classOf[BendProofRootStore])
    roots.loadState(new BendProofRootState)
    roots.select(saved.getVirtualFile.getPath)

    assertEquals(
      List(saved.getVirtualFile.getPath),
      BendProofNavigation.roots(law).paths
    )
    assertFalse(
      BendProofNavigation.roots(law).paths.contains(law.getVirtualFile.getPath)
    )
    assertFalse(
      BendProofNavigation
        .roots(law)
        .paths
        .contains(
          unselected.getVirtualFile.getPath
        )
    )

  def testSelectedRootsPersistAsProjectState(): Unit =
    val store = getProject.getService(classOf[BendProofRootStore])
    store.loadState(new BendProofRootState)
    val first = Path.of("/workspace/first/PROOF.bend").toString
    val second = Path.of("/workspace/second/PROOF.bend").toString
    store.select(first)
    store.select(second)
    store.select(first)
    assertEquals(List(first, second), store.selectedPaths)

    val savedState = store.getState
    val restored = new BendProofRootStore(getProject)
    restored.loadState(savedState)
    assertEquals(List(first, second), restored.selectedPaths)
    savedState.paths(0) = "mutated"
    assertEquals(List(first, second), store.selectedPaths)

  def testProofRootActionIsSeparateFromCurrentFileCheck(): Unit =
    val manager = ActionManager.getInstance()
    assertTrue(
      manager
        .getAction("Bend.CheckProofRoot")
        .isInstanceOf[BendCheckProofRootAction]
    )
    assertTrue(
      manager
        .getAction("Bend.CheckCurrentFile")
        .isInstanceOf[
          com.dearlordylord.bend.idea.features.checking.BendCheckCurrentFileAction
        ]
    )

  def testBendToolsGroupAndPsiVfsActionsUseBackgroundUpdates(): Unit =
    val manager = ActionManager.getInstance()
    assertTrue(manager.getAction("Bend.Tools").isInstanceOf[ActionGroup])
    val editorPopup = manager.getAction("Bend.EditorPopup")
    assertTrue(editorPopup.isInstanceOf[ActionGroup])
    val editorPopupIds = editorPopup
      .asInstanceOf[ActionGroup]
      .getChildren(null)
      .flatMap(action => Option(manager.getId(action)))
      .toSet
    assertTrue(
      List(
        "Bend.CheckCurrentFile",
        "Bend.NextProofHole",
        "Bend.PreviousProofHole"
      ).forall(editorPopupIds)
    )
    val ids = List(
      "Bend.CreateModule",
      "Bend.CreateLawProofPair",
      "Bend.InspectDependencies",
      "Bend.GenerateMatchCases",
      "Bend.GenerateLawFill",
      "Bend.ExplicitImport",
      "Bend.CheckCurrentFile",
      "Bend.CheckStatus",
      "Bend.NextProofHole",
      "Bend.PreviousProofHole"
    )
    ids.foreach { id =>
      assertEquals(
        id,
        ActionUpdateThread.BGT,
        manager.getAction(id).getActionUpdateThread
      )
    }

  def testSettingsAndToolsUseBendSpecificPlacement(): Unit =
    val input = Option(getClass.getResourceAsStream("/META-INF/plugin.xml")).get
    val source = Source.fromInputStream(input)
    val xml = try source.mkString
    finally source.close()
    assertTrue(
      "Bend settings are nested under Languages & Frameworks",
      xml.contains(
        "id=\"com.dearlordylord.bend.idea.settings\" displayName=\"Bend\" parentId=\"language\""
      )
    )
    assertTrue(
      xml.contains("group id=\"Bend.Tools\" text=\"Bend\" popup=\"true\"")
    )
    assertTrue(
      "Bend spellchecking strategy is registered in the platform descriptor",
      xml.contains(
        "<spellchecker.support language=\"Bend\""
      )
    )
    assertFalse(xml.contains("com.intellij.modules.spellchecker"))
    List("Bend.CreateModule", "Bend.CreateLawProofPair").foreach { id =>
      val action = xml.split("<action id=\"" + id + "\"", 2).last
      assertTrue(
        id + " has Bend icon",
        action.takeWhile(_ != '>').contains("icon=\"/icons/bend.svg\"")
      )
    }
    assertEquals(1, xml.split("group-id=\"ToolsMenu\"", -1).length - 1)
    assertEquals(9, xml.split("group-id=\"Bend.Tools\"", -1).length - 1)
