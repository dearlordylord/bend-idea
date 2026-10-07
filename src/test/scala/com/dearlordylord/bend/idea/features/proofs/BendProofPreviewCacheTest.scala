package com.dearlordylord.bend.idea.features.proofs

import com.dearlordylord.bend.idea.symbols.api.BendSourceSymbols
import com.dearlordylord.bend.idea.workspace.api.BendWorkspaceGraph
import com.dearlordylord.bend.idea.workspace.model.{
  BendLoadedGraph,
  BendSourceRecord
}
import com.dearlordylord.bend.idea.test.VfsTestRoots
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.testFramework.ServiceContainerUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files
import org.junit.Assert.*

final class BendProofPreviewCacheTest extends BasePlatformTestCase:
  def testOpeningAndClosingUnchangedDiskSourcesReusesInventory(): Unit =
    val directory = Files.createTempDirectory("bend-preview-cache-")
    VfsTestRoots.allowSystemTemporaryDirectory(getTestRootDisposable, directory)
    val lawPath = directory.resolve("LAWS.bend")
    val proofPath = directory.resolve("PROOF.bend")
    try
      Files.writeString(lawPath, "law claim:\n  Type\n")
      Files.writeString(
        proofPath,
        "import ./LAWS.bend as Laws\ndef Laws.claim():\n  ?TODO\n"
      )
      val vfs = LocalFileSystem.getInstance()
      val manager = PsiManager.getInstance(getProject)
      val laws = manager.findFile(vfs.refreshAndFindFileByNioFile(lawPath))
      val proof = manager.findFile(vfs.refreshAndFindFileByNioFile(proofPath))
      val law = BendSourceSymbols.declarations(laws).head
      val fill = BendSourceSymbols.declarations(proof).head
      val delegate = getProject.getService(classOf[BendWorkspaceGraph])
      var loads = 0
      ServiceContainerUtil.replaceService(
        getProject,
        classOf[BendWorkspaceGraph],
        new BendWorkspaceGraph:
          override def load(
              root: BendSourceRecord,
              base: String,
              cache: String,
              canceled: () => Boolean
          ): BendLoadedGraph =
            loads += 1
            delegate.load(root, base, cache, canceled)
          override def current(
              graph: BendLoadedGraph,
              cache: String,
              canceled: () => Boolean
          ): Boolean = delegate.current(graph, cache, canceled)
          override def contentsCurrent(
              graph: BendLoadedGraph,
              cache: String,
              canceled: () => Boolean
          ): Boolean = delegate.contentsCurrent(graph, cache, canceled)
          override def siblingLaws(path: String): Option[BendSourceRecord] =
            delegate.siblingLaws(path)
        ,
        getTestRootDisposable
      )
      val editors = FileEditorManager.getInstance(getProject)
      val paths = List(proofPath.toString)
      myFixture.openFileInEditor(laws.getVirtualFile)
      assertEquals(1, BendProofNavigation.destinations(laws, law, paths).size)
      val coldLoads = loads
      (0 until 3).foreach { _ =>
        myFixture.openFileInEditor(laws.getVirtualFile)
        editors.closeFile(proof.getVirtualFile)
        assertEquals(1, BendProofNavigation.destinations(laws, law, paths).size)
        myFixture.openFileInEditor(proof.getVirtualFile)
        editors.closeFile(laws.getVirtualFile)
        assertEquals(
          1,
          BendProofNavigation.destinations(proof, fill, paths).size
        )
      }
      assertEquals(
        "Preview-style editor switches must not rebuild unchanged law/fill links",
        coldLoads,
        loads
      )
    finally
      FileEditorManager
        .getInstance(getProject)
        .getOpenFiles
        .foreach(file =>
          FileEditorManager.getInstance(getProject).closeFile(file)
        )
      val _ = Files.deleteIfExists(proofPath)
      val _ = Files.deleteIfExists(lawPath)
      val _ = Files.deleteIfExists(directory)
