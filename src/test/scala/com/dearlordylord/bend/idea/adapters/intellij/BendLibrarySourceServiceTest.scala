package com.dearlordylord.bend.idea.adapters.intellij

import com.dearlordylord.bend.idea.test.VfsTestRoots
import com.dearlordylord.bend.idea.workspace.api.{
  BendSourceCatalog,
  BendPathInventoryStatus,
  BendWorkspacePaths
}
import com.intellij.openapi.fileEditor.{FileDocumentManager, FileEditorManager}
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiDocumentManager
import com.intellij.codeInsight.navigation.actions.GotoDeclarationAction
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.*
import java.nio.file.Files

final class BendLibrarySourceServiceTest extends BasePlatformTestCase:
  def testClosedModifiedSymlinkAliasRemainsCurrentForCanonicalSourceReads()
      : Unit =
    VfsTestRoots.allowSystemTemporaryDirectory(getTestRootDisposable)
    val directory =
      Files.createTempDirectory("bend-closed-source-alias-").toRealPath()
    val target = directory.resolve("module.bend")
    val alias = directory.resolve("alias.bend")
    val persisted = "def saved():\n  Type\n"
    val edited = "def edited():\n  Type\n"
    val editors = FileEditorManager.getInstance(getProject)
    var virtual: com.intellij.openapi.vfs.VirtualFile = null
    try
      Files.writeString(target, persisted)
      Files.createSymbolicLink(alias, target.getFileName)
      virtual = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(alias)
      assertNotNull(virtual)
      myFixture.configureFromExistingVirtualFile(virtual)
      val document = myFixture.getEditor.getDocument
      WriteCommandAction.runWriteCommandAction(
        getProject,
        new Runnable:
          override def run(): Unit = document.setText(edited)
      )
      PsiDocumentManager.getInstance(getProject).commitDocument(document)
      val catalog = getProject.getService(classOf[BendSourceCatalog])
      assertEquals(edited, catalog.source(target.toString).get.text)
      editors.closeFile(virtual)
      assertFalse(editors.isFileOpen(virtual))
      assertTrue(FileDocumentManager.getInstance().isFileModified(virtual))
      assertEquals(persisted, Files.readString(target))
      val source = catalog.source(target.toString).get
      assertEquals(
        "Closed modified aliases still own current source",
        edited,
        source.text
      )
      assertEquals(document.getModificationStamp, source.revision)
      assertEquals(target.toString, source.id.value)
      myFixture.configureByText(
        "closed-source-navigation.bend",
        s"import $target as Closed\ndef main():\n  Closed.<caret>edited()\n"
      )
      val declaration = GotoDeclarationAction.findTargetElement(
        getProject,
        myFixture.getEditor,
        myFixture.getCaretOffset
      )
      assertNotNull(
        "Native navigation reacquires the closed modified alias",
        declaration
      )
      assertEquals("edited", declaration.getText)
      assertEquals(virtual, declaration.getContainingFile.getVirtualFile)
    finally
      Option(virtual).foreach(editors.closeFile)
      val _ = Files.deleteIfExists(alias)
      val _ = Files.deleteIfExists(target)
      val _ = Files.deleteIfExists(directory)

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

  def testCachedPackageMappingRejectsMalformedOversizedAndEscapingNames()
      : Unit =
    val directory = Files.createTempDirectory("bend-cache-mapping-")
    val names = Files.createDirectories(directory.resolve("names"))
    val name = "bend-sample@1.0.0.0"
    val mapping = names.resolve(name)
    val catalog = getProject.getService(classOf[BendSourceCatalog])
    try
      assertTrue(catalog.cachedPackageHash(directory.toString, name).isEmpty)
      Files.writeString(mapping, "0x" + "a" * 32 + "\n")
      assertEquals(
        Some("0x" + "a" * 32),
        catalog.cachedPackageHash(directory.toString, name)
      )
      Files.writeString(mapping, "0x" + "a" * 32 + " " * 128)
      assertTrue(
        "Oversized metadata is not accepted after trimming",
        catalog.cachedPackageHash(directory.toString, name).isEmpty
      )
      Files.writeString(mapping, "0x" + "A" * 32)
      assertTrue(catalog.cachedPackageHash(directory.toString, name).isEmpty)
      assertTrue(
        catalog.cachedPackageHash(directory.toString, "../" + name).isEmpty
      )
      assertTrue(
        catalog
          .cachedPackageHash(directory.toString, "bend-sample@01.0.0.0")
          .isEmpty
      )
    finally
      val _ = Files.deleteIfExists(mapping)
      val _ = Files.deleteIfExists(names)
      val _ = Files.deleteIfExists(directory)
