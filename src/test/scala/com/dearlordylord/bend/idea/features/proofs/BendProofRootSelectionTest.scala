package com.dearlordylord.bend.idea.features.proofs

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.*
import java.nio.file.Path

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
