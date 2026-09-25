package com.dearlordylord.bend.idea.features.proofs

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.{PlatformTestUtil, ServiceContainerUtil}
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.dearlordylord.bend.idea.model.FileId
import com.dearlordylord.bend.idea.symbols.api.{
  BendSourceSymbols,
  BendSymbolCategory
}
import com.dearlordylord.bend.idea.toolchain.api.BendToolchainSettings
import com.dearlordylord.bend.idea.workspace.api.{
  BendLoadingConfiguration,
  BendSourceCatalog,
  BendWorkspaceGraph
}
import com.dearlordylord.bend.idea.workspace.loading.BendGraphLoader
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
    assertEquals(List(secondPath, firstPath), reader.roots().paths.take(2))

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
    assertTrue(
      "Current source rows remain navigable",
      reader.navigationTarget(candidate).nonEmpty
    )
    WriteCommandAction.runWriteCommandAction(
      getProject,
      new Runnable:
        override def run(): Unit =
          myFixture.getEditor.getDocument.setText(
            "import ../shared/LAWS.bend as Laws\ndef Laws.claim():\n  ?changed\n"
          )
    )
    PsiDocumentManager.getInstance(getProject).commitAllDocuments()
    assertTrue(
      "Rows captured before a source edit must not navigate at stale offsets",
      reader.navigationTarget(candidate).isEmpty
    )
    val refreshed = reader
      .read(firstPath, () => false)
      .get
      .entries
      .find(_.kind == BendProofInventoryKind.CandidateFill)
      .get
    val panel = new BendProofProgressPanel(getProject)
    try
      myFixture.getEditor.getCaretModel.moveToOffset(0)
      panel.openEntry(refreshed)
      PlatformTestUtil.waitWithEventsDispatching(
        "Progress rows navigate to the current declaration location",
        () =>
          Option(
            FileEditorManager.getInstance(getProject).getSelectedTextEditor
          )
            .exists(_.getCaretModel.getOffset == refreshed.offset),
        5000
      )
      assertEquals(
        refreshed.offset,
        FileEditorManager
          .getInstance(getProject)
          .getSelectedTextEditor
          .getCaretModel
          .getOffset
      )
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

  def testReaderSurfacesWorkspaceGraphInventoryCap(): Unit =
    val root = myFixture.addFileToProject(
      "proof-graph-cap/PROOF.bend",
      "import ./dependency.bend as Dependency\n"
    )
    val _ = myFixture.addFileToProject(
      "proof-graph-cap/dependency.bend",
      "def dependency():\n  0\n"
    )
    val catalog = getProject.getService(classOf[BendSourceCatalog])
    val graph = new BendWorkspaceGraph:
      override def load(
          source: com.dearlordylord.bend.idea.workspace.model.BendSourceRecord,
          basePath: String,
          packageCache: String,
          canceled: () => Boolean
      ) =
        BendGraphLoader.load(
          source,
          BendGraphLoader.Config(basePath, packageCache),
          catalog,
          maxFiles = 1,
          canceled = canceled
        )

      override def siblingLaws(proofPath: String) = None
    ServiceContainerUtil.replaceService(
      getProject,
      classOf[BendWorkspaceGraph],
      graph,
      getTestRootDisposable
    )
    val snapshot = new BendProofProgressReader(getProject)
      .read(root.getVirtualFile.getPath, () => false)
      .get
    assertTrue(
      snapshot.checkedStatus,
      snapshot.checkedStatus.contains("inventory capped")
    )

  def testProgressRowRejectsChangedLoadingConfiguration(): Unit =
    val _ = myFixture.addFileToProject(
      "shared/LAWS.bend",
      "law claim:\n  Type\n"
    )
    val root = myFixture.addFileToProject(
      "proof/PROOF.bend",
      "import ../shared/LAWS.bend as Laws\ndef Laws.claim():\n  ?TODO\n"
    )
    val settings =
      com.intellij.openapi.application.ApplicationManager.getApplication
        .getService(classOf[BendToolchainSettings])
    val original = settings.choices
    val reader = new BendProofProgressReader(getProject)
    val row = reader
      .read(root.getVirtualFile.getPath, () => false)
      .get
      .entries
      .find(_.kind == BendProofInventoryKind.CandidateFill)
      .get
    val revision = getProject
      .getService(classOf[BendLoadingConfiguration])
      .configurationRevision
    try
      assertTrue(reader.navigationTarget(row).nonEmpty)
      settings.update(
        original.copy(packageCache = original.packageCache + "/changed")
      )
      assertTrue(
        "Rows captured with old loading settings must not navigate",
        reader.navigationTarget(row).isEmpty
      )
      assertTrue(
        revision != getProject
          .getService(classOf[BendLoadingConfiguration])
          .configurationRevision
      )
    finally settings.update(original)
