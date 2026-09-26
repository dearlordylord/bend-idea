package com.dearlordylord.bend.idea.adapters.intellij

import com.dearlordylord.bend.idea.test.VfsTestRoots
import com.dearlordylord.bend.idea.workspace.api.{
  BendPathInventoryStatus,
  BendWorkspacePaths
}
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.*
import java.nio.file.Files

final class BendLibrarySourceServiceTest extends BasePlatformTestCase:
  def testNamedProofInventoryExcludesOpenFilesOutsideProjectContent(): Unit =
    VfsTestRoots.allowSystemTemporaryDirectory(getTestRootDisposable)
    val projectRoot = myFixture.addFileToProject(
      "proofs/PROOF.bend",
      "def main(): 0\n"
    )
    val externalDirectory = Files.createTempDirectory("bend-external-proof-")
    val externalPath = externalDirectory.resolve("PROOF.bend")
    Files.writeString(externalPath, "def external(): 0\n")
    val externalFile = LocalFileSystem
      .getInstance()
      .refreshAndFindFileByPath(externalPath.toString)

    val editors = FileEditorManager.getInstance(getProject)
    try
      assertNotNull(externalFile)
      editors.openFile(externalFile, false)
      assertTrue(editors.getOpenFiles.contains(externalFile))
      IndexingTestUtil.waitUntilIndexesAreReady(getProject)

      val inventory = getProject
        .getService(classOf[BendWorkspacePaths])
        .filesNamed("PROOF.bend", 32)

      assertEquals(BendPathInventoryStatus.Complete, inventory.status)
      assertTrue(
        "The indexed project proof root remains discoverable",
        inventory.paths.contains(projectRoot.getVirtualFile.getPath)
      )
      assertFalse(
        "An open external proof file is not a project root suggestion",
        inventory.paths.contains(externalFile.getPath)
      )
    finally
      Option(externalFile).foreach(editors.closeFile)
      val _ = Files.deleteIfExists(externalPath)
      val _ = Files.deleteIfExists(externalDirectory)
