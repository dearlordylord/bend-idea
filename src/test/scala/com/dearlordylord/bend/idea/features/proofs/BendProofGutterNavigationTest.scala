package com.dearlordylord.bend.idea.features.proofs

import com.dearlordylord.bend.idea.symbols.api.BendSourceSymbols
import com.dearlordylord.bend.idea.workspace.api.{
  BendWorkspaceGraph,
  BendLoadingConfiguration
}
import com.dearlordylord.bend.idea.workspace.model.{
  BendLoadedGraph,
  BendSourceRecord
}
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.testFramework.ServiceContainerUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import org.junit.Assert.*
import java.util.concurrent.atomic.AtomicInteger

final class BendProofGutterNavigationTest extends BasePlatformTestCase:
  private def click(file: com.intellij.psi.PsiFile): Unit =
    val name = BendSourceSymbols
      .declarations(file)
      .head
      .declaration
      .getNameIdentifier
      .getFirstChild
    val marker = new BendProofLineMarkerProvider().getLineMarkerInfo(name)
    assertNotNull(marker)
    marker.getNavigationHandler.navigate(null, marker.getElement)

  private def awaitCondition(condition: => Boolean): Unit =
    val deadline =
      System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15)
    while !condition && System.nanoTime() < deadline do
      UIUtil.dispatchAllInvocationEvents()
      Thread.sleep(10)
    assertTrue("Background gutter navigation did not complete", condition)

  def testActualGutterRoundTripAndCancellationDoNotPublishPartialInventory()
      : Unit =
    val laws =
      myFixture.addFileToProject("gutter/LAWS.bend", "law claim:\n  Type\n")
    val proof = myFixture.addFileToProject(
      "gutter/PROOF.bend",
      "import ./LAWS.bend as Laws\ndef Laws.claim():\n  ?TODO\n"
    )
    getProject
      .getService(classOf[BendProofRootStore])
      .select(proof.getVirtualFile.getPath)
    val delegate = getProject.getService(classOf[BendWorkspaceGraph])
    val loads = new AtomicInteger()
    val canceled = new java.util.concurrent.atomic.AtomicBoolean()
    ServiceContainerUtil.replaceService(
      getProject,
      classOf[BendWorkspaceGraph],
      new BendWorkspaceGraph:
        override def load(
            root: BendSourceRecord,
            base: String,
            cache: String,
            cancel: () => Boolean
        ): BendLoadedGraph =
          assertFalse(
            com.intellij.openapi.application.ApplicationManager.getApplication.isDispatchThread
          )
          val progress = ProgressManager.getInstance().getProgressIndicator
          assertNotNull(progress)
          assertFalse(progress.isShowing)
          val graph = delegate.load(root, base, cache, cancel)
          if loads.incrementAndGet() == 1 then
            ProgressManager.getInstance().getProgressIndicator.cancel()
            canceled.set(true)
          graph
        override def current(
            graph: BendLoadedGraph,
            cache: String,
            cancel: () => Boolean
        ): Boolean = delegate.current(graph, cache, cancel)
        override def siblingLaws(path: String): Option[BendSourceRecord] =
          delegate.siblingLaws(path)
      ,
      getTestRootDisposable
    )
    myFixture.openFileInEditor(laws.getVirtualFile)
    click(laws)
    awaitCondition(canceled.get())
    UIUtil.dispatchAllInvocationEvents()
    assertEquals(
      laws.getVirtualFile,
      FileEditorManager
        .getInstance(getProject)
        .getSelectedTextEditor
        .getVirtualFile
    )
    click(laws)
    awaitCondition(
      FileEditorManager
        .getInstance(getProject)
        .getSelectedTextEditor
        .getVirtualFile == proof.getVirtualFile
    )
    assertEquals(
      BendSourceSymbols.declarations(proof).head.handle.nameOffset,
      FileEditorManager
        .getInstance(getProject)
        .getSelectedTextEditor
        .getCaretModel
        .getOffset
    )
    assertEquals("Canceled graph capture must be loaded again", 2, loads.get())
    click(proof)
    awaitCondition(
      FileEditorManager
        .getInstance(getProject)
        .getSelectedTextEditor
        .getVirtualFile == laws.getVirtualFile
    )
    assertEquals(
      BendSourceSymbols.declarations(laws).head.handle.nameOffset,
      FileEditorManager
        .getInstance(getProject)
        .getSelectedTextEditor
        .getCaretModel
        .getOffset
    )
    assertEquals(
      "The return gutter click reuses the completed inventory",
      2,
      loads.get()
    )

  def testCancellationAfterScanAndConfigurationChangeBeforeDelivery(): Unit =
    val laws =
      myFixture.addFileToProject("delivery/LAWS.bend", "law claim:\n  Type\n")
    val proof = myFixture.addFileToProject(
      "delivery/PROOF.bend",
      "import ./LAWS.bend as Laws\ndef Laws.claim():\n  ?TODO\n"
    )
    getProject
      .getService(classOf[BendProofRootStore])
      .select(proof.getVirtualFile.getPath)
    myFixture.openFileInEditor(laws.getVirtualFile)
    val selected = BendSourceSymbols.declarations(laws).head
    val delegate = getProject.getService(classOf[BendWorkspaceGraph])
    val loads = new AtomicInteger()
    val indicator = new com.intellij.openapi.progress.EmptyProgressIndicator
    var cancelValidation = true
    ServiceContainerUtil.replaceService(
      getProject,
      classOf[BendWorkspaceGraph],
      new BendWorkspaceGraph:
        override def load(
            root: BendSourceRecord,
            base: String,
            cache: String,
            cancel: () => Boolean
        ): BendLoadedGraph =
          loads.incrementAndGet()
          delegate.load(root, base, cache, cancel)
        override def current(
            graph: BendLoadedGraph,
            cache: String,
            cancel: () => Boolean
        ): Boolean =
          if cancelValidation then indicator.cancel()
          delegate.current(graph, cache, cancel)
        override def siblingLaws(path: String): Option[BendSourceRecord] =
          delegate.siblingLaws(path)
      ,
      getTestRootDisposable
    )
    val canceledTask = BendProofNavigation.searchTask(laws, selected)
    canceledTask.run(indicator)
    assertTrue(
      "Cancellation reaches the end of the source scan",
      indicator.isCanceled
    )
    cancelValidation = false
    val task = BendProofNavigation.searchTask(laws, selected)
    task.run(new com.intellij.openapi.progress.EmptyProgressIndicator)
    assertEquals(
      "An inventory canceled after scanning must not be cached",
      2,
      loads.get()
    )
    val settings =
      com.intellij.openapi.application.ApplicationManager.getApplication
        .getService(
          classOf[
            com.dearlordylord.bend.idea.toolchain.api.BendToolchainSettings
          ]
        )
    val original = settings.choices
    try
      settings.update(
        original.copy(packageCache = original.packageCache + "/delivery-change")
      )
      task.onSuccess()
      assertEquals(
        "A competing configuration revision prevents delivery",
        laws.getVirtualFile,
        FileEditorManager
          .getInstance(getProject)
          .getSelectedTextEditor
          .getVirtualFile
      )
      val fresh = BendProofNavigation.destinations(
        laws,
        selected,
        List(proof.getVirtualFile.getPath)
      )
      assertEquals(1, fresh.size)
      assertEquals(
        "The new configuration cannot reuse the old inventory",
        3,
        loads.get()
      )
    finally settings.update(original)

  def testCancellationDuringDeclarationTraversalCannotPublishCache(): Unit =
    val laws =
      myFixture.addFileToProject("scan/LAWS.bend", "law claim:\n  Type\n")
    val proof = myFixture.addFileToProject(
      "scan/PROOF.bend",
      "import ./LAWS.bend as Laws\ndef Laws.claim():\n  ?TODO\n" + (0 until 100)
        .map(i => s"def unrelated$i():\n  0\n")
        .mkString
    )
    getProject
      .getService(classOf[BendProofRootStore])
      .select(proof.getVirtualFile.getPath)
    val delegate = getProject.getService(classOf[BendWorkspaceGraph])
    var armed = false
    var checks = 0
    var loads = 0
    var validations = 0
    val indicator =
      new com.intellij.openapi.progress.util.AbstractProgressIndicatorBase:
        override def isCanceled(): Boolean =
          if armed then
            checks += 1
            if checks == 30 then cancel()
          super.isCanceled
    ServiceContainerUtil.replaceService(
      getProject,
      classOf[BendWorkspaceGraph],
      new BendWorkspaceGraph:
        override def load(
            root: BendSourceRecord,
            base: String,
            cache: String,
            cancel: () => Boolean
        ): BendLoadedGraph =
          loads += 1
          val graph = delegate.load(root, base, cache, cancel)
          armed = true
          graph
        override def current(
            graph: BendLoadedGraph,
            cache: String,
            cancel: () => Boolean
        ): Boolean =
          validations += 1
          delegate.current(graph, cache, cancel)
        override def siblingLaws(path: String): Option[BendSourceRecord] =
          delegate.siblingLaws(path)
      ,
      getTestRootDisposable
    )
    val selected = BendSourceSymbols.declarations(laws).head
    BendProofNavigation.searchTask(laws, selected).run(indicator)
    assertTrue(indicator.isCanceled)
    assertEquals(
      "Cancellation stopped traversal before completed-graph validation",
      0,
      validations
    )
    assertEquals(
      1,
      BendProofNavigation
        .destinations(laws, selected, List(proof.getVirtualFile.getPath))
        .size
    )
    assertEquals(
      "Partial declarations must not become a reusable inventory",
      2,
      loads
    )

  def testDisposedProjectRejectsTaskDeliveryAndLateCacheWrites(): Unit =
    val factory = com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
      .getFixtureFactory()
    val projectFixture =
      factory.createFixtureBuilder("proof-navigation-disposal").getFixture
    val fixture = factory.createCodeInsightFixture(projectFixture)
    fixture.setUp()
    var tornDown = false
    try
      val laws = fixture.addFileToProject("LAWS.bend", "law claim:\n  Type\n")
      val proof = fixture.addFileToProject(
        "PROOF.bend",
        "import ./LAWS.bend as Laws\ndef Laws.claim():\n  ?TODO\n"
      )
      val project = fixture.getProject
      project
        .getService(classOf[BendProofRootStore])
        .select(proof.getVirtualFile.getPath)
      val cache = project.getService(classOf[BendProofLinkCache])
      val task = BendProofNavigation.searchTask(
        laws,
        BendSourceSymbols.declarations(laws).head
      )
      task.run(new com.intellij.openapi.progress.EmptyProgressIndicator)
      val revision = project
        .getService(classOf[BendLoadingConfiguration])
        .configurationRevision
      val captured = cache.get(proof.getVirtualFile.getPath, revision)
      assertTrue(
        "The completed task cached its source inventory",
        captured.nonEmpty
      )
      val path = proof.getVirtualFile.getPath
      fixture.tearDown()
      tornDown = true
      assertTrue(project.isDisposed)
      task.onSuccess()
      cache.put(path, revision, captured.get)
      assertTrue(
        "Project disposal also rejects a late capture publication",
        cache.get(path, revision).isEmpty
      )
    finally if !tornDown then fixture.tearDown()

  def testCompetingConfigurationRevisionDuringCaptureRetriesWithoutPublishingOldEntry()
      : Unit =
    val laws =
      myFixture.addFileToProject("race/LAWS.bend", "law claim:\n  Type\n")
    val proof = myFixture.addFileToProject(
      "race/PROOF.bend",
      "import ./LAWS.bend as Laws\ndef Laws.claim():\n  ?TODO\n"
    )
    val selected = BendSourceSymbols.declarations(laws).head
    val delegate = getProject.getService(classOf[BendWorkspaceGraph])
    val settings =
      com.intellij.openapi.application.ApplicationManager.getApplication
        .getService(
          classOf[
            com.dearlordylord.bend.idea.toolchain.api.BendToolchainSettings
          ]
        )
    val original = settings.choices
    var loads = 0
    var changed = false
    ServiceContainerUtil.replaceService(
      getProject,
      classOf[BendWorkspaceGraph],
      new BendWorkspaceGraph:
        override def load(
            root: BendSourceRecord,
            base: String,
            cache: String,
            cancel: () => Boolean
        ): BendLoadedGraph =
          loads += 1
          delegate.load(root, base, cache, cancel)
        override def current(
            graph: BendLoadedGraph,
            cache: String,
            cancel: () => Boolean
        ): Boolean =
          val current = delegate.current(graph, cache, cancel)
          if !changed then
            changed = true
            settings.update(
              original.copy(packageCache =
                original.packageCache + "/capture-race"
              )
            )
          current
        override def siblingLaws(path: String): Option[BendSourceRecord] =
          delegate.siblingLaws(path)
      ,
      getTestRootDisposable
    )
    try
      val paths = List(proof.getVirtualFile.getPath)
      assertEquals(
        1,
        BendProofNavigation.destinations(laws, selected, paths).size
      )
      assertTrue(changed)
      assertEquals("Capture with the old revision must retry", 2, loads)
      assertEquals(
        1,
        BendProofNavigation.destinations(laws, selected, paths).size
      )
      assertEquals(
        "Only the new revision's completed inventory is reusable",
        2,
        loads
      )
    finally settings.update(original)
