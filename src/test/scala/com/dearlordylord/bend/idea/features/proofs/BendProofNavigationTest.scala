package com.dearlordylord.bend.idea.features.proofs

import com.dearlordylord.bend.idea.symbols.api.{
  BendSourceSymbols,
  BendSymbolCategory
}
import com.dearlordylord.bend.idea.analysis.model.BendCheckingStatus
import com.dearlordylord.bend.idea.toolchain.api.{
  BendToolchainChoices,
  BendToolchainSettings
}
import com.dearlordylord.bend.idea.workspace.api.BendPathInventoryStatus
import com.dearlordylord.bend.idea.workspace.api.BendLoadingConfiguration
import com.dearlordylord.bend.idea.syntax.psi.{
  BendLaw,
  BendProofForm,
  BendProofSurface
}
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.ServiceContainerUtil
import com.dearlordylord.bend.idea.workspace.api.BendWorkspaceGraph
import com.dearlordylord.bend.idea.workspace.model.BendSourceRecord
import org.junit.Assert.*

final class BendProofNavigationTest extends BasePlatformTestCase:
  def testLinkCacheBoundsRootsAndSourceTextAndDropsOldConfigurations(): Unit =
    val cache = new BendProofLinkCache
    val revision = 1L
    def inventory(path: String, text: String): BendProofLinkInventory =
      val id = new com.dearlordylord.bend.idea.model.FileId(path, true)
      val source = BendSourceRecord(id, path, text, 1, Nil)
      val graph = com.dearlordylord.bend.idea.workspace.model.BendLoadedGraph(
        id,
        List(
          com.dearlordylord.bend.idea.workspace.model.BendLoadedFile(source, "")
        ),
        Nil,
        Nil
      )
      BendProofLinkInventory(
        graph,
        id,
        Map.empty,
        Map.empty,
        Map.empty,
        Set.empty,
        false
      )
    (0 until 32).foreach(i =>
      cache.put(s"root$i", revision, inventory(s"root$i", ""))
    )
    assertTrue(cache.get("root0", revision).nonEmpty)
    cache.put("root32", revision, inventory("root32", ""))
    assertTrue(
      "The least recently used root is evicted",
      cache.get("root1", revision).isEmpty
    )
    assertTrue(cache.get("root0", revision).nonEmpty)
    cache.put("oversized", revision, inventory("oversized", "x" * 4000001))
    assertTrue(
      "An oversized graph cannot enter the cache",
      cache.get("oversized", revision).isEmpty
    )
    assertTrue(cache.get("root0", revision).nonEmpty)
    assertTrue(
      "A new loading configuration drops previous root entries",
      cache.get("root0", revision + 1).isEmpty
    )

  def testRoundTripReusesRootInventory(): Unit =
    val lawFile = myFixture.addFileToProject(
      "roundtrip/LAWS.bend",
      "law claim:\n  Type\n"
    )
    val proof = myFixture.addFileToProject(
      "roundtrip/PROOF.bend",
      "import ./LAWS.bend as Laws\ndef Laws.claim():\n  ?TODO\n"
    )
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
        ) =
          loads += 1
          delegate.load(root, base, cache, canceled)
        override def current(
            graph: com.dearlordylord.bend.idea.workspace.model.BendLoadedGraph,
            cache: String,
            canceled: () => Boolean
        ) = delegate.current(graph, cache, canceled)
        override def siblingLaws(path: String) = delegate.siblingLaws(path)
      ,
      getTestRootDisposable
    )
    val law = BendSourceSymbols.declarations(lawFile).head
    val fill = BendSourceSymbols.declarations(proof).head
    val paths = List(proof.getVirtualFile.getPath)
    val cold = System.nanoTime()
    assertEquals(1, BendProofNavigation.destinations(lawFile, law, paths).size)
    val warm = System.nanoTime()
    (1 to 5).foreach { _ =>
      assertEquals(
        law.handle,
        BendProofNavigation.destinations(proof, fill, paths).head.symbol.handle
      )
      assertEquals(
        fill.handle,
        BendProofNavigation.destinations(lawFile, law, paths).head.symbol.handle
      )
    }
    println(
      s"Proof navigation: cold=${(warm - cold) / 1000000}ms, ten warm searches=${(System.nanoTime() - warm) / 1000000}ms, graph loads=$loads"
    )
    assertEquals(
      "Switching back and forth must not reload and reparse the same root",
      1,
      loads
    )
    val settings =
      com.intellij.openapi.application.ApplicationManager.getApplication
        .getService(classOf[BendToolchainSettings])
    val original = settings.choices
    try
      settings.update(
        original.copy(packageCache =
          original.packageCache + "/navigation-change"
        )
      )
      assertEquals(
        1,
        BendProofNavigation.destinations(lawFile, law, paths).size
      )
      assertEquals(
        "Loading settings must invalidate cached inventories",
        2,
        loads
      )
    finally settings.update(original)

  def testCachedLinksRejectUncommittedAndCommittedImportEdits(): Unit =
    val laws =
      myFixture.addFileToProject("edit/LAWS.bend", "law claim:\n  Type\n")
    val other =
      myFixture.addFileToProject("edit/other.bend", "law other:\n  Type\n")
    val proof = myFixture.addFileToProject(
      "edit/PROOF.bend",
      "import ./LAWS.bend as Laws\ndef Laws.claim():\n  ?TODO\n"
    )
    val selected = BendSourceSymbols.declarations(laws).head
    val paths = List(proof.getVirtualFile.getPath)
    assertEquals(
      1,
      BendProofNavigation.destinations(laws, selected, paths).size
    )
    val manager = PsiDocumentManager.getInstance(getProject)
    val document = manager.getDocument(proof)
    WriteCommandAction.runWriteCommandAction(
      getProject,
      new Runnable:
        override def run(): Unit = document.setText(
          "import ./other.bend as Laws\ndef Laws.claim():\n  ?TODO\n"
        )
    )
    assertTrue(
      "Dirty imports cannot reuse links from committed PSI",
      BendProofNavigation.destinations(laws, selected, paths).isEmpty
    )
    manager.commitAllDocuments()
    assertTrue(
      "Unsaved committed imports invalidate cached relationships",
      BendProofNavigation.destinations(laws, selected, paths).isEmpty
    )
    assertTrue(
      BendProofNavigation
        .destinations(other, BendSourceSymbols.declarations(other).head, paths)
        .isEmpty
    )

  def testCachedLinksObserveClosedFileChangesBeforeVfsRefresh(): Unit =
    val directory =
      java.nio.file.Files.createTempDirectory("bend-proof-link-input-")
    val path = directory.resolve("laws.bend")
    com.dearlordylord.bend.idea.test.VfsTestRoots
      .allowSystemTemporaryDirectory(getTestRootDisposable, directory)
    try
      val _ = java.nio.file.Files.writeString(path, "law claim:\n  Type\n")
      val proof = myFixture.addFileToProject(
        "external/PROOF.bend",
        s"import $path as Laws\ndef Laws.claim():\n  ?TODO\n"
      )
      val fill = BendSourceSymbols.declarations(proof).head
      val paths = List(proof.getVirtualFile.getPath)
      assertEquals(1, BendProofNavigation.destinations(proof, fill, paths).size)
      assertEquals(1, BendProofNavigation.destinations(proof, fill, paths).size)
      val psi =
        PsiModificationTracker.getInstance(getProject).getModificationCount
      val vfs = com.intellij.openapi.vfs.VirtualFileManager
        .getInstance()
        .getModificationCount
      val _ = java.nio.file.Files.writeString(path, "law other:\n  Type\n")
      assertEquals(
        psi,
        PsiModificationTracker.getInstance(getProject).getModificationCount
      )
      assertEquals(
        vfs,
        com.intellij.openapi.vfs.VirtualFileManager
          .getInstance()
          .getModificationCount
      )
      assertTrue(
        "Disk observations invalidate links even without a PSI/VFS event",
        BendProofNavigation.destinations(proof, fill, paths).isEmpty
      )
    finally
      val _ = java.nio.file.Files.deleteIfExists(path)
      val _ = java.nio.file.Files.deleteIfExists(directory)

  def testLawAndFillLinksStayInsideEachSelectedRoot(): Unit =
    val lawFile = myFixture.addFileToProject(
      "shared/LAWS.bend",
      "law claim:\n  Type\n"
    )
    val firstRoot = myFixture.addFileToProject(
      "proof-one/PROOF.bend",
      "import ../shared/LAWS.bend as Laws\ndef Laws.claim():\n  ?TODO\n"
    )
    val secondRoot = myFixture.addFileToProject(
      "proof-two/PROOF.bend",
      "import ../shared/LAWS.bend as Claim\ndef Claim.claim():\n  ?TODO\n"
    )
    val law = BendSourceSymbols
      .declarations(lawFile)
      .find(_.category == BendSymbolCategory.Law)
      .get
    val fills = BendProofNavigation.destinations(
      lawFile,
      law,
      List(
        firstRoot.getVirtualFile.getPath,
        secondRoot.getVirtualFile.getPath
      )
    )
    assertEquals(2, fills.size)
    assertEquals(
      Set(
        firstRoot.getVirtualFile.getPath,
        secondRoot.getVirtualFile.getPath
      ),
      fills.map(_.rootPath).toSet
    )
    assertTrue(fills.forall(_.symbol.category == BendSymbolCategory.Definition))
    assertTrue(fills.forall(_.status == BendCheckingStatus.Unchecked))

    val firstFill = BendSourceSymbols
      .declarations(firstRoot)
      .find(_.name == "Laws.claim")
      .get
    val lawBack = BendProofNavigation.destinations(
      firstRoot,
      firstFill,
      List(firstRoot.getVirtualFile.getPath)
    )
    assertEquals(1, lawBack.size)
    assertEquals(
      lawFile.getVirtualFile.getPath,
      lawBack.head.target.getContainingFile.getVirtualFile.getPath
    )

  def testLinksFollowNestedLawAndProofModulesInTheSelectedRootGraph(): Unit =
    val lawFile = myFixture.addFileToProject(
      "laws/core.bend",
      "law d20_all_to_nat:\n  Type\n"
    )
    val _ = myFixture.addFileToProject(
      "LAWS.bend",
      "import ./laws/core.bend as Core\n"
    )
    val proofModule = myFixture.addFileToProject(
      "proofs/core.bend",
      "import ../laws/core.bend as L\ndef L.d20_all_to_nat():\n  ?TODO\n"
    )
    val root = myFixture.addFileToProject(
      "PROOF.bend",
      "import ./LAWS.bend as Laws\nimport ./proofs/core.bend as ProofCore\n"
    )
    val law = BendSourceSymbols
      .declarations(lawFile)
      .find(_.category == BendSymbolCategory.Law)
      .get
    val fill = BendSourceSymbols
      .declarations(proofModule)
      .find(_.category == BendSymbolCategory.Definition)
      .get
    val rootPath = root.getVirtualFile.getPath

    val lawToFill = BendProofNavigation.destinations(
      lawFile,
      law,
      List(rootPath)
    )
    assertEquals(1, lawToFill.size)
    assertEquals(fill.handle, lawToFill.head.symbol.handle)

    val fillToLaw = BendProofNavigation.destinations(
      proofModule,
      fill,
      List(rootPath)
    )
    assertEquals(1, fillToLaw.size)
    assertEquals(law.handle, fillToLaw.head.symbol.handle)

  def testUnfilledLawHasNoCandidateTarget(): Unit =
    val lawFile = myFixture.addFileToProject(
      "shared/LAWS.bend",
      "law unfinished:\n  Type\n"
    )
    val root = myFixture.addFileToProject(
      "PROOF.bend",
      "import ./shared/LAWS.bend as Laws\ndef main() -> Type:\n  Type\n"
    )
    val law = BendSourceSymbols
      .declarations(lawFile)
      .find(_.category == BendSymbolCategory.Law)
      .get
    assertTrue(
      BendProofNavigation
        .destinations(lawFile, law, List(root.getVirtualFile.getPath))
        .isEmpty
    )

  def testSingleStaleRootCandidateRequiresVisibleStatusChoice(): Unit =
    val _ = myFixture.addFileToProject(
      "shared/LAWS.bend",
      "law claim:\n  Type\n"
    )
    val root = myFixture.addFileToProject(
      "proof/PROOF.bend",
      "import ../shared/LAWS.bend as Laws\ndef Laws.claim():\n  ?TODO\n"
    )
    val fill = BendSourceSymbols
      .declarations(root)
      .find(_.name == "Laws.claim")
      .get
    val destination = BendProofDestination(
      root.getVirtualFile.getPath,
      BendSourceSymbols.fileId(root),
      BendSourceSymbols.declarationFact(fill),
      fill.declaration,
      BendCheckingStatus.Stale
    )
    myFixture.openFileInEditor(root.getVirtualFile)
    val sourceModificationCount =
      PsiModificationTracker.getInstance(getProject).getModificationCount
    val configurationRevision = getProject
      .getService(classOf[BendLoadingConfiguration])
      .configurationRevision
    assertEquals(
      Some(
        BendNavigationTarget(
          root.getVirtualFile,
          fill.declaration.getTextOffset
        )
      ),
      BendProofNavigation.navigationTarget(
        getProject,
        destination,
        sourceModificationCount,
        configurationRevision
      )
    )

    WriteCommandAction.runWriteCommandAction(
      getProject,
      new Runnable:
        override def run(): Unit =
          myFixture.getEditor.getDocument.setText(
            "import ../shared/LAWS.bend as Laws\ndef Laws.claim():\n  ?TODO\n# edited\n"
          )
    )
    PsiDocumentManager.getInstance(getProject).commitAllDocuments()
    assertTrue(
      "An edit invalidates the PSI target captured before the chooser",
      BendProofNavigation
        .navigationTarget(
          getProject,
          destination,
          sourceModificationCount,
          configurationRevision
        )
        .isEmpty
    )

    assertTrue(
      "A single stale candidate must show its root/status in the chooser",
      BendProofNavigation.requiresStatusChoice(List(destination))
    )
    assertTrue(
      "A single result from an incomplete root inventory must not auto-navigate",
      BendProofNavigation.requiresStatusChoice(
        List(destination),
        BendPathInventoryStatus.IndexUnavailable
      )
    )
    assertTrue(
      BendProofNavigation.requiresStatusChoice(
        List(destination),
        BendPathInventoryStatus.Capped
      )
    )
    assertTrue(
      "A capped source declaration inventory must show its limit in the chooser",
      BendProofNavigation.requiresStatusChoice(
        List(destination),
        BendPathInventoryStatus.Complete,
        sourceInventoryCapped = true
      )
    )
    val currentDestination = destination.copy(
      status = BendCheckingStatus.Unchecked
    )
    assertFalse(
      BendProofNavigation.requiresStatusChoice(
        List(currentDestination),
        BendPathInventoryStatus.Complete
      )
    )
    assertTrue(
      BendProofNavigation
        .candidateChooserTitle(BendPathInventoryStatus.IndexUnavailable)
        .contains("Project indexing is unavailable")
    )
    assertTrue(
      BendProofNavigation
        .candidateChooserTitle(
          BendPathInventoryStatus.Complete,
          sourceInventoryCapped = true
        )
        .contains("source inventory cap")
    )
    assertTrue(destination.toString.contains("Check stale"))

  def testLoadingConfigurationChangeInvalidatesCapturedProofTarget(): Unit =
    val _ = myFixture.addFileToProject(
      "shared/LAWS.bend",
      "law claim:\n  Type\n"
    )
    val root = myFixture.addFileToProject(
      "proof/PROOF.bend",
      "import ../shared/LAWS.bend as Laws\ndef Laws.claim():\n  ?TODO\n"
    )
    val fill = BendSourceSymbols
      .declarations(root)
      .find(_.name == "Laws.claim")
      .get
    val destination = BendProofDestination(
      root.getVirtualFile.getPath,
      BendSourceSymbols.fileId(root),
      BendSourceSymbols.declarationFact(fill),
      fill.declaration,
      BendCheckingStatus.Unchecked
    )
    val settings =
      com.intellij.openapi.application.ApplicationManager.getApplication
        .getService(classOf[BendToolchainSettings])
    val original: BendToolchainChoices = settings.choices
    val sourceModificationCount =
      PsiModificationTracker.getInstance(getProject).getModificationCount
    val configurationRevision = getProject
      .getService(classOf[BendLoadingConfiguration])
      .configurationRevision
    try
      assertTrue(
        BendProofNavigation
          .navigationTarget(
            getProject,
            destination,
            sourceModificationCount,
            configurationRevision
          )
          .nonEmpty
      )
      settings.update(
        original.copy(packageCache = original.packageCache + "/changed")
      )
      assertTrue(
        "A proof chooser target must not survive a loading configuration change",
        BendProofNavigation
          .navigationTarget(
            getProject,
            destination,
            sourceModificationCount,
            configurationRevision
          )
          .isEmpty
      )
    finally settings.update(original)

  def testLawAndCandidateFillDeclarationsHaveGutterLinks(): Unit =
    val lawFile = myFixture.addFileToProject(
      "law.bend",
      "# preceding declaration\nlaw claim:\n  Type\n"
    )
    val proofFile = myFixture.addFileToProject(
      "PROOF.bend",
      "def main():\n  0\ndef Laws.claim():\n  ?TODO\n"
    )
    val provider = new BendProofLineMarkerProvider
    val law = PsiTreeUtil.findChildOfType(lawFile, classOf[BendLaw])
    val fill = BendSourceSymbols
      .declarations(proofFile)
      .find(symbol =>
        symbol.category == BendSymbolCategory.Definition &&
          symbol.name == "Laws.claim"
      )
      .map(_.declaration)
      .orNull
    assertNotNull(law)
    assertNotNull(fill)
    val lawMarker = provider.getLineMarkerInfo(
      law.getNameIdentifier.getFirstChild
    )
    val fillMarker = provider.getLineMarkerInfo(
      fill.getNameIdentifier.getFirstChild
    )
    assertNotNull(lawMarker)
    assertNotNull(fillMarker)
    assertTrue(lawMarker.getLineMarkerTooltip.contains("candidate fills"))
    assertTrue(fillMarker.getLineMarkerTooltip.contains("matching law"))
    assertEquals(
      law.getNameIdentifier.getFirstChild.getTextRange.getStartOffset,
      lawMarker.startOffset
    )
    assertEquals(
      fill.getNameIdentifier.getFirstChild.getTextRange.getStartOffset,
      fillMarker.startOffset
    )

  def testNextAndPreviousActionsNavigateOnlyToSourceHoles(): Unit =
    val source =
      "def main():\n  \"?\"\n  \"?TODO\"\n  # ?comment\n  ?named\n  ?TODO\n"
    myFixture.configureByText("holes.bend", source)
    val forms = BendProofSurface.scan(myFixture.getFile.getText)
    assertEquals(
      List("named", "TODO"),
      forms.collect { case hole: BendProofForm.Hole => hole.name }
    )
    val bounded = BendProofSurface
      .scanBounded(
        myFixture.getFile.getText,
        0,
        8,
        1,
        () => false
      )
      .get
    assertTrue(
      "The token budget truncates a large source projection",
      bounded.truncated
    )
    assertTrue(bounded.forms.size <= 1)
    var cancellationChecks = 0
    assertTrue(
      "Proof scans check cancellation during tokenization",
      BendProofSurface
        .scanBounded(
          myFixture.getFile.getText,
          0,
          10000,
          100,
          () =>
            cancellationChecks += 1
            cancellationChecks > 3
        )
        .isEmpty
    )
    val first = source.indexOf("?named")
    val second = source.indexOf("?TODO", source.indexOf("?named"))
    myFixture.getEditor.getCaretModel.moveToOffset(0)
    myFixture.performEditorAction("Bend.NextProofHole")
    assertEquals(first, myFixture.getEditor.getCaretModel.getOffset)
    myFixture.performEditorAction("Bend.NextProofHole")
    assertEquals(second, myFixture.getEditor.getCaretModel.getOffset)
    myFixture.performEditorAction("Bend.PreviousProofHole")
    assertEquals(first, myFixture.getEditor.getCaretModel.getOffset)

    WriteCommandAction.runWriteCommandAction(
      getProject,
      new Runnable:
        override def run(): Unit = myFixture.getEditor.getDocument.setText(
          "def main():\n  ?fresh_name\n"
        )
    )
    PsiDocumentManager.getInstance(getProject).commitAllDocuments()
    myFixture.getEditor.getCaretModel.moveToOffset(0)
    myFixture.performEditorAction("Bend.NextProofHole")
    assertEquals(
      "Navigation must use the current unsaved hole map after an edit",
      "def main():\n  ?fresh_name\n".indexOf("?fresh_name"),
      myFixture.getEditor.getCaretModel.getOffset
    )

  def testNamedAndTodoHoleOffsetsWrap(): Unit =
    val source = "?alpha\n?TODO\n"
    val first = source.indexOf("?alpha")
    val last = source.indexOf("?TODO")
    assertEquals(
      Some(first),
      BendHoleNavigation.nextOffset(source, source.length)
    )
    assertEquals(Some(last), BendHoleNavigation.previousOffset(source, 0))

  def testExplicitProofRootsSkipUnneededProjectInventory(): Unit =
    val laws =
      myFixture.addFileToProject("selected/LAWS.bend", "law claim:\n  Type\n")
    val proof = myFixture.addFileToProject(
      "selected/PROOF.bend",
      "import ./LAWS.bend as Laws\ndef Laws.claim():\n  ?TODO\n"
    )
    val roots = getProject.getService(classOf[BendProofRootStore])
    val previous = roots.getState
    roots.loadState(new BendProofRootState)
    roots.select(proof.getVirtualFile.getPath)
    com.intellij.openapi.util.Disposer
      .register(getTestRootDisposable, () => roots.loadState(previous))
    ServiceContainerUtil.replaceService(
      getProject,
      classOf[com.dearlordylord.bend.idea.workspace.api.BendWorkspacePaths],
      new com.dearlordylord.bend.idea.workspace.api.BendWorkspacePaths:
        override def children(path: String, limit: Int) = Nil
        override def filesNamed(name: String, limit: Int) =
          throw new AssertionError(
            "Explicit proof roots must not trigger project-wide discovery on every law click"
          )
        override def filesWithExtension(extension: String, limit: Int) =
          Right(Nil)
      ,
      getTestRootDisposable
    )
    val inventory = BendProofNavigation.roots(laws)
    assertEquals(List(proof.getVirtualFile.getPath), inventory.paths)
    assertEquals(BendPathInventoryStatus.Complete, inventory.status)
