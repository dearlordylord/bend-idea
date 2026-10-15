package com.dearlordylord.bend.idea.features.proofs

import com.dearlordylord.bend.idea.symbols.api.BendSourceSymbols
import com.dearlordylord.bend.idea.workspace.api.{
  BendWorkspaceGraph,
  BendSourceCatalog,
  BendImportLines
}
import com.dearlordylord.bend.idea.workspace.model.{
  BendLoadedGraph,
  BendSourceRecord
}
import com.dearlordylord.bend.idea.test.VfsTestRoots
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiManager
import com.intellij.testFramework.ServiceContainerUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files
import org.junit.Assert.*

final class BendProofColdCaptureTest extends BasePlatformTestCase:
  def testColdCaptureDoesNotReturnFillAfterClosedRootRetargetsItsLawImport()
      : Unit =
    val directory = Files.createTempDirectory("bend-cache-race-review-")
    val lawPath = directory.resolve("LAWS.bend")
    val nextPath = directory.resolve("NEXT.bend")
    val proofPath = directory.resolve("PROOF.bend")
    VfsTestRoots.allowSystemTemporaryDirectory(getTestRootDisposable, directory)
    val proofText = "import ./LAWS.bend as Laws\ndef Laws.claim():\n  ?TODO\n"
    try
      val _ = Files.writeString(lawPath, "law claim:\n  Type\n")
      val _ = Files.writeString(nextPath, "law claim:\n  Type\n")
      val _ = Files.writeString(proofPath, proofText)
      val vfs = LocalFileSystem.getInstance()
      val manager = PsiManager.getInstance(getProject)
      val laws = manager.findFile(vfs.refreshAndFindFileByNioFile(lawPath))
      val _ =
        manager.findFile(vfs.refreshAndFindFileByNioFile(nextPath)).getText
      val _ =
        manager.findFile(vfs.refreshAndFindFileByNioFile(proofPath)).getText
      val selected = BendSourceSymbols.declarations(laws).head
      assertEquals(
        "Positive control: initial law/fill relationship exists",
        1,
        BendProofNavigation
          .destinations(laws, selected, List(proofPath.toString))
          .size
      )
      val _ =
        myFixture.addFileToProject("invalidate.bend", "def unrelated():\n  0\n")
      ServiceContainerUtil.replaceService(
        getProject,
        classOf[BendProofLinkCache],
        new BendProofLinkCache,
        getTestRootDisposable
      )
      val delegate = getProject.getService(classOf[BendWorkspaceGraph])
      val originalCatalog = getProject.getService(classOf[BendSourceCatalog])
      var changed = false
      ServiceContainerUtil.replaceService(
        getProject,
        classOf[BendSourceCatalog],
        new BendSourceCatalog:
          override def source(path: String): Option[BendSourceRecord] =
            originalCatalog.source(path).map { record =>
              if changed && path == proofPath.toString then
                val text = proofText.replace("LAWS", "NEXT")
                record.copy(
                  text = text,
                  revision = record.revision + 1,
                  imports = BendImportLines.parse(text)
                )
              else record
            }
          override def canonicalPath(path: String): String =
            originalCatalog.canonicalPath(path)
          override def cachedPackageHash(
              cache: String,
              name: String
          ): Option[String] = originalCatalog.cachedPackageHash(cache, name)
        ,
        getTestRootDisposable
      )
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
            val graph = delegate.load(root, base, cache, canceled)
            if !changed then
              changed = true
              ()
            graph
          override def current(
              graph: BendLoadedGraph,
              cache: String,
              canceled: () => Boolean
          ): Boolean = delegate.current(graph, cache, canceled)
          override def siblingLaws(path: String): Option[BendSourceRecord] =
            delegate.siblingLaws(path)
        ,
        getTestRootDisposable
      )
      val destinations = BendProofNavigation.destinations(
        laws,
        selected,
        List(proofPath.toString)
      )
      assertTrue(
        "The controlled source change occurred during the cold graph load",
        changed
      )
      assertTrue(
        "The closed proof now imports NEXT, so it cannot fill the selected LAWS declaration",
        destinations.isEmpty
      )
    finally
      val _ = Files.deleteIfExists(proofPath)
      val _ = Files.deleteIfExists(nextPath)
      val _ = Files.deleteIfExists(lawPath)
      val _ = Files.deleteIfExists(directory)
