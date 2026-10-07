package com.dearlordylord.bend.idea.adapters.intellij

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.fileEditor.{FileDocumentManager, FileEditorManager}
import com.intellij.codeInsight.navigation.actions.GotoDeclarationAction
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.search.LocalSearchScope
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
  def testDiskWatchNotificationPreservesUnsavedImportEdgesAfterCloseAndReopen()
      : Unit =
    com.dearlordylord.bend.idea.test.VfsTestRoots
      .allowSystemTemporaryDirectory(getTestRootDisposable)
    val directory = Files.createTempDirectory("bend-watch-current-buffer-")
    val editors = FileEditorManager.getInstance(getProject)
    var opened = List.empty[com.intellij.openapi.vfs.VirtualFile]
    try
      for name <- List("saved", "live", "external") do
        Files.writeString(
          directory.resolve(s"$name.bend"),
          "def answer():\n  Type\n"
        )
      def bridgeText(name: String): String =
        s"import ./$name.bend as Chosen\ndef item():\n  Chosen.answer()\n"
      val bridgePath = directory.resolve("bridge.bend")
      Files.writeString(bridgePath, bridgeText("saved"))
      val rootPath = directory.resolve("main.bend")
      Files.writeString(
        rootPath,
        "import ./bridge.bend as Bridge\ndef main():\n  Bridge.item()\n"
      )
      val bridge =
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(bridgePath)
      val root =
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(rootPath)
      assertNotNull(bridge)
      assertNotNull(root)
      opened = List(bridge, root)
      myFixture.configureFromExistingVirtualFile(bridge)
      val document = myFixture.getEditor.getDocument
      WriteCommandAction.runWriteCommandAction(
        getProject,
        new Runnable:
          override def run(): Unit = document.setText(bridgeText("live"))
      )
      PsiDocumentManager.getInstance(getProject).commitDocument(document)
      assertTrue(FileDocumentManager.getInstance().isFileModified(bridge))
      val capture = new BendRootSnapshotCapture(getProject)
      val selected = ApplicationManager.getApplication
        .getService(classOf[BendToolchainSettings])
        .selection
      val before = capture.capture(rootPath.toString, selected, () => false).get
      assertEquals(before.graph.toString, 3, before.graph.get.files.size)
      assertTrue(capture.current(before))

      // A watcher reports an external write. Deliver only its notification:
      // IntelliJ's conflict/reload UI is outside this source-capture contract.
      Files.writeString(bridgePath, bridgeText("external"))
      val event = new VFileContentChangeEvent(
        this,
        bridge,
        bridge.getModificationStamp,
        -1L
      )
      WriteCommandAction.runWriteCommandAction(
        getProject,
        new Runnable:
          override def run(): Unit =
            ApplicationManager.getApplication.getMessageBus
              .syncPublisher(VirtualFileManager.VFS_CHANGES)
              .after(java.util.List.of(event))
      )
      editors.closeFile(bridge)
      assertFalse(editors.isFileOpen(bridge))
      assertEquals(bridgeText("external"), Files.readString(bridgePath))
      assertEquals(bridgeText("live"), document.getText)
      assertTrue(
        "Closing an IntelliJ editor does not discard its modified document",
        capture.current(before)
      )
      val closed = capture.capture(rootPath.toString, selected, () => false).get
      val paths = closed.graph.get.files.map(_.source.id.value).toSet
      assertTrue(
        closed.graph.toString,
        paths.contains(directory.resolve("live.bend").toRealPath().toString)
      )
      assertFalse(
        paths.contains(directory.resolve("saved.bend").toRealPath().toString)
      )
      assertFalse(
        paths.contains(directory.resolve("external.bend").toRealPath().toString)
      )
      assertTrue(
        closed.graph.get.files.exists(_.source.text == bridgeText("live"))
      )

      // configureFromExistingVirtualFile rewrites VFS bytes. Use the actual
      // editor open operation to preserve this modified document on reopen.
      myFixture.openFileInEditor(bridge)
      assertEquals(bridgeText("live"), myFixture.getEditor.getDocument.getText)
      myFixture.getEditor.getCaretModel.moveToOffset(
        document.getText.lastIndexOf("answer") + 2
      )
      val target = GotoDeclarationAction.findTargetElement(
        getProject,
        myFixture.getEditor,
        myFixture.getCaretOffset
      )
      assertNotNull(
        "Navigation follows the unsaved import after reopen",
        target
      )
      assertEquals("live.bend", target.getContainingFile.getName)
      val found = ReferencesSearch
        .search(target, new LocalSearchScope(myFixture.getFile), true)
        .findAll()
      assertEquals(1, found.size())
      assertEquals(
        bridge,
        found.iterator().next().getElement.getContainingFile.getVirtualFile
      )
    finally
      opened.foreach(editors.closeFile)
      val paths = Files.walk(directory)
      try
        paths
          .sorted(java.util.Comparator.reverseOrder())
          .forEach(path => { val _ = Files.deleteIfExists(path) })
      finally paths.close()

  def testSharedCaptureRejectsRetargetedNamedPackageAndMissingNameAppearance()
      : Unit =
    val settings = ApplicationManager.getApplication.getService(
      classOf[BendToolchainSettings]
    )
    val original = settings.choices
    com.dearlordylord.bend.idea.test.VfsTestRoots
      .allowSystemTemporaryDirectory(getTestRootDisposable)
    val directory = Files.createTempDirectory("bend-named-capture-")
    try
      val cache = directory.resolve("cache")
      val name = "bend-sample@1.0.0.0"
      val firstHash = "0x" + "a" * 32
      val secondHash = "0x" + "b" * 32
      for hash <- List(firstHash, secondHash) do
        val module = cache.resolve(hash).resolve("item.bend")
        Files.createDirectories(module.getParent)
        Files.writeString(module, "def item() -> Type:\n  Type\n")
      Files.createDirectories(cache.resolve("names"))
      val mapping = cache.resolve("names").resolve(name)
      Files.writeString(mapping, firstHash + "\n")
      val root = directory.resolve("main.bend")
      Files.writeString(
        root,
        s"import $name/item.bend as Named\ndef main() -> Type:\n  Named.item()\n"
      )
      settings.update(original.copy(packageCache = cache.toString))
      val capture = new BendRootSnapshotCapture(getProject)
      val snapshot =
        capture.capture(root.toString, settings.selection, () => false).get
      assertTrue(capture.current(snapshot))
      Files.writeString(mapping, secondHash + "\n")
      assertFalse(capture.current(snapshot))
      Files.delete(mapping)
      val missing =
        capture.capture(root.toString, settings.selection, () => false).get
      assertTrue(capture.current(missing))
      Files.writeString(mapping, firstHash + "\n")
      assertFalse(capture.current(missing))
    finally
      settings.update(original)
      val paths = Files.walk(directory)
      try
        paths
          .sorted(java.util.Comparator.reverseOrder())
          .forEach(path => { val _ = Files.deleteIfExists(path) })
      finally paths.close()

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
