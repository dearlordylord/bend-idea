package com.dearlordylord.bend.idea.features.proofs

import com.intellij.openapi.actionSystem.{
  ActionManager,
  ActionGroup,
  ActionUpdateThread
}
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.*
import java.nio.file.Path
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
      BendProofNavigation.roots(law)
    )
    assertFalse(
      BendProofNavigation.roots(law).contains(law.getVirtualFile.getPath)
    )
    assertFalse(
      BendProofNavigation.roots(law).contains(unselected.getVirtualFile.getPath)
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
      "spellchecking descriptor follows Grazie when available",
      xml.contains(
        "config-file=\"com.dearlordylord.bend.idea-spellchecker.xml\">tanvd.grazi"
      )
    )
    List("Bend.CreateModule", "Bend.CreateLawProofPair").foreach { id =>
      val action = xml.split("<action id=\"" + id + "\"", 2).last
      assertTrue(
        id + " has Bend icon",
        action.takeWhile(_ != '>').contains("icon=\"/icons/bend.svg\"")
      )
    }
    assertEquals(1, xml.split("group-id=\"ToolsMenu\"", -1).length - 1)
    assertEquals(9, xml.split("group-id=\"Bend.Tools\"", -1).length - 1)
