package com.dearlordylord.bend.idea.adapters.intellij

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.vfs.LocalFileSystem
import com.dearlordylord.bend.idea.toolchain.api.BendToolchainSettings
import com.dearlordylord.bend.idea.model.FileId
import com.dearlordylord.bend.idea.workspace.api.{
  BendImportLines,
  BendSourceCatalog,
  BendWorkspaceGraph
}
import com.dearlordylord.bend.idea.workspace.model.{
  BendLoadedGraph,
  BendSourceRecord
}
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.*
import java.nio.file.Files
import java.util.concurrent.{Callable, FutureTask, TimeUnit}

final class BendExplicitCheckRunnerServiceTest extends BasePlatformTestCase:
  def testSharedCaptureRejectsRetargetedLoadedAlias(): Unit =
    def record(path: String, text: String): BendSourceRecord =
      BendSourceRecord(
        new FileId(path, true),
        path,
        text,
        1L,
        BendImportLines.parse(text)
      )
    val root = record(
      "/virtual/root.bend",
      "import ./link.bend as Link\ndef main():\n  Link.answer()\n"
    )
    val first = record("/virtual/target-a.bend", "def answer():\n  1\n")
    val second = record("/virtual/target-b.bend", "def answer():\n  1\n")
    var linked = first
    val catalog = new BendSourceCatalog:
      override def source(path: String): Option[BendSourceRecord] = path match
        case "/virtual/root.bend"     => Some(root)
        case "/virtual/link.bend"     => Some(linked)
        case "/virtual/target-a.bend" => Some(first)
        case "/virtual/target-b.bend" => Some(second)
        case _                        => None
    val loader = new BendWorkspaceGraph:
      override def load(
          source: BendSourceRecord,
          basePath: String,
          packageCache: String,
          canceled: () => Boolean
      ): BendLoadedGraph =
        BendWorkspaceGraph.load(
          source,
          basePath,
          packageCache,
          catalog,
          canceled
        )

      override def siblingLaws(proofPath: String): Option[BendSourceRecord] =
        None
    val selected = ApplicationManager.getApplication
      .getService(classOf[BendToolchainSettings])
      .selection
    val capture = new BendRootSnapshotCapture(getProject, catalog, loader)
    val snapshot = capture
      .capture(root.path, selected, () => false)
      .getOrElse(throw new AssertionError("Alias capture failed"))
    assertTrue(capture.current(snapshot))
    linked = second
    assertFalse(capture.current(snapshot))

  def testSharedCaptureRetriesAppearingMissingImport(): Unit =
    val root = myFixture.configureByText(
      "missing-root.bend",
      "import ./late.bend as Late\ndef main() -> Type:\n  Late.answer()\n"
    )
    val catalog = getProject.getService(classOf[BendSourceCatalog])
    val realLoader = getProject.getService(classOf[BendWorkspaceGraph])
    var loads = 0
    val creatingLoader = new BendWorkspaceGraph:
      override def load(
          source: BendSourceRecord,
          basePath: String,
          packageCache: String,
          canceled: () => Boolean
      ): BendLoadedGraph =
        val graph = realLoader.load(source, basePath, packageCache, canceled)
        loads += 1
        if loads == 1 then
          val _ = myFixture.addFileToProject(
            "late.bend",
            "def answer() -> Type:\n  Type\n"
          )
        graph

      override def siblingLaws(proofPath: String): Option[BendSourceRecord] =
        realLoader.siblingLaws(proofPath)
    val selected = ApplicationManager.getApplication
      .getService(classOf[BendToolchainSettings])
      .selection
    val capture =
      new BendRootSnapshotCapture(getProject, catalog, creatingLoader)
    val snapshot = capture
      .capture(root.getVirtualFile.getPath, selected, () => false)
      .getOrElse(throw new AssertionError("Capture did not retry"))
    assertEquals(2, loads)
    assertTrue(snapshot.graph.exists(_.files.size == 2))
    assertTrue(capture.current(snapshot))

  def testSharedCaptureRetriesDependencyEditDuringGraphLoad(): Unit =
    val dependency = myFixture.addFileToProject(
      "changing.bend",
      "def answer() -> Type:\n  Type\n"
    )
    val root = myFixture.configureByText(
      "capture-root.bend",
      "import ./changing.bend as D\ndef main() -> Type:\n  D.answer()\n"
    )
    val rootPath = root.getVirtualFile.getPath
    myFixture.openFileInEditor(dependency.getVirtualFile)
    val document = myFixture.getEditor.getDocument
    val catalog = getProject.getService(classOf[BendSourceCatalog])
    val realLoader = getProject.getService(classOf[BendWorkspaceGraph])
    var loads = 0
    val changingLoader = new BendWorkspaceGraph:
      override def load(
          source: BendSourceRecord,
          basePath: String,
          packageCache: String,
          canceled: () => Boolean
      ): BendLoadedGraph =
        val graph = realLoader.load(source, basePath, packageCache, canceled)
        loads += 1
        if loads == 1 then
          WriteCommandAction.runWriteCommandAction(
            getProject,
            new Runnable:
              override def run(): Unit = document.setText(
                "def answer() -> Type:\n  NewType\n"
              )
          )
        graph

      override def siblingLaws(proofPath: String): Option[BendSourceRecord] =
        realLoader.siblingLaws(proofPath)
    val selected = ApplicationManager.getApplication
      .getService(classOf[BendToolchainSettings])
      .selection
    val capture = new BendRootSnapshotCapture(
      getProject,
      catalog,
      changingLoader
    )
    val snapshot = capture
      .capture(rootPath, selected, () => false)
      .getOrElse(throw new AssertionError("Capture did not retry"))
    assertEquals(2, loads)
    assertTrue(
      snapshot.graph.exists(_.files.exists(_.source.text.contains("NewType")))
    )
    assertTrue(capture.current(snapshot))

  def testSharedCaptureRejectsUnsavedDependencyRevision(): Unit =
    val dependency = myFixture.addFileToProject(
      "dep.bend",
      "def answer() -> Type:\n  Type\n"
    )
    val root = myFixture.configureByText(
      "root.bend",
      "import ./dep.bend as D\ndef main() -> Type:\n  D.answer()\n"
    )
    val selected = ApplicationManager.getApplication
      .getService(classOf[BendToolchainSettings])
      .selection
    val capture = new BendRootSnapshotCapture(getProject)
    val snapshot = capture
      .capture(root.getVirtualFile.getPath, selected, () => false)
      .getOrElse(throw new AssertionError("Root capture failed"))
    assertTrue(snapshot.graph.exists(_.files.size == 2))
    assertTrue(capture.current(snapshot))
    myFixture.openFileInEditor(dependency.getVirtualFile)
    WriteCommandAction.runWriteCommandAction(
      getProject,
      new Runnable:
        override def run(): Unit = myFixture.getEditor.getDocument.setText(
          "def answer() -> Type:\n  D_changed\n"
        )
    )
    assertFalse(capture.current(snapshot))

  def testDocumentLookupAcquiresReadAccessOnPooledThread(): Unit =
    val path = Files.createTempFile("bend-explicit-root-", ".bend")
    try
      Files.writeString(path, "def main():\n  0\n")
      val virtualFile = LocalFileSystem
        .getInstance()
        .refreshAndFindFileByNioFile(path)
      assertNotNull(
        "Fixture file should be visible in the local VFS",
        virtualFile
      )
      val runner = new BendExplicitCheckRunnerService(getProject)
      val task = new FutureTask[Boolean](new Callable[Boolean]:
        override def call(): Boolean = runner.documentAt(path).nonEmpty)

      ApplicationManager.getApplication.executeOnPooledThread(task)

      assertTrue(task.get(10, TimeUnit.SECONDS))
    finally
      val _ = Files.deleteIfExists(path)
