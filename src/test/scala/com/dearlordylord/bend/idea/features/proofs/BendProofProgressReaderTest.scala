package com.dearlordylord.bend.idea.features.proofs

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.dearlordylord.bend.idea.model.FileId
import com.dearlordylord.bend.idea.symbols.api.{
  BendSourceSymbols,
  BendSymbolCategory
}
import org.junit.Assert.*

final class BendProofProgressReaderTest extends BasePlatformTestCase:
  def testMultipleRootsUnsavedInventoryNavigationAndCancellation(): Unit =
    val _ = myFixture.addFileToProject(
      "shared/LAWS.bend",
      "law claim:\n  Type\ntype Unit is Data:\n  Unit{}\n"
    )
    val first = myFixture.addFileToProject(
      "proof-one/PROOF.bend",
      "import ../shared/LAWS.bend as Laws\ndef Laws.claim():\n  ?TODO\n"
    )
    val second = myFixture.addFileToProject(
      "proof-two/PROOF.bend",
      "import ../shared/LAWS.bend as Laws\ndef Laws.claim():\n  ?unfinished\n"
    )
    val firstPath = first.getVirtualFile.getPath
    val secondPath = second.getVirtualFile.getPath
    val store = getProject.getService(classOf[BendProofRootStore])
    store.select(firstPath)
    store.select(secondPath)
    val reader = new BendProofProgressReader(getProject)
    assertEquals(List(secondPath, firstPath), reader.roots().take(2))

    val firstSnapshot = reader.read(firstPath, () => false).get
    assertEquals("Not checked", firstSnapshot.checkedStatus)
    assertTrue(
      firstSnapshot.entries.exists(_.kind == BendProofInventoryKind.Law)
    )
    assertFalse(
      "Imported datatypes and constructors are not proof inventory definitions",
      firstSnapshot.entries.exists(entry => entry.name == "Unit")
    )
    assertTrue(
      firstSnapshot.entries.exists(
        _.kind == BendProofInventoryKind.CandidateFill
      )
    )
    assertTrue(firstSnapshot.entries.exists(_.name == "?TODO"))
    val secondSnapshot = reader.read(secondPath, () => false).get
    assertTrue(secondSnapshot.entries.exists(_.name == "?unfinished"))
    assertFalse(secondSnapshot.entries.exists(_.name == "?TODO"))
    assertTrue(
      "Canceled graph reads must not publish partial inventory",
      reader.read(firstPath, () => true).isEmpty
    )

    val boundedText = (1 to 20).map(i => s"def item$i():\n  0\n").mkString
    val bounded = BendSourceSymbols.sourceDeclarationsBounded(
      getProject,
      new FileId(firstPath, true),
      boundedText,
      1,
      100000,
      Set(BendSymbolCategory.Definition),
      () => false
    )
    assertEquals(1, bounded.get.symbols.size)
    var cancellationChecks = 0
    val canceledTraversal = BendSourceSymbols.sourceDeclarationsBounded(
      getProject,
      new FileId(firstPath, true),
      boundedText,
      100,
      100000,
      Set(BendSymbolCategory.Definition),
      () =>
        cancellationChecks += 1
        cancellationChecks > 5
    )
    assertTrue(
      "Declaration traversal checks cancellation while walking PSI",
      canceledTraversal.isEmpty
    )

    myFixture.openFileInEditor(first.getVirtualFile)
    WriteCommandAction.runWriteCommandAction(
      getProject,
      new Runnable:
        override def run(): Unit =
          myFixture.getEditor.getDocument.setText(
            "import ../shared/LAWS.bend as Laws\ndef Laws.claim():\n  ?fresh\n"
          )
    )
    PsiDocumentManager.getInstance(getProject).commitAllDocuments()
    val edited = reader.read(firstPath, () => false).get
    assertTrue(
      "Inventory must follow the unsaved editor buffer",
      edited.entries.exists(_.name == "?fresh")
    )
    assertFalse(edited.entries.exists(_.name == "?TODO"))

    val candidate =
      edited.entries.find(_.kind == BendProofInventoryKind.CandidateFill).get
    val panel = new BendProofProgressPanel(getProject)
    try
      panel.openEntry(candidate)
      assertEquals(
        "Progress rows navigate to their physical source declaration",
        firstPath,
        FileEditorManager.getInstance(getProject).getSelectedFiles.head.getPath
      )
      panel.dispose()
      panel.refresh()
    finally panel.dispose()

  def testReaderSurfacesEntryCapForPartialInventory(): Unit =
    val root = myFixture.addFileToProject(
      "proof-capped/PROOF.bend",
      "def main():\n" + List.fill(2100)("  ?TODO\n").mkString
    )
    val snapshot = new BendProofProgressReader(getProject)
      .read(root.getVirtualFile.getPath, () => false)
      .get
    assertTrue(snapshot.checkedStatus.contains("inventory capped"))
    assertEquals(2048, snapshot.entries.size)
