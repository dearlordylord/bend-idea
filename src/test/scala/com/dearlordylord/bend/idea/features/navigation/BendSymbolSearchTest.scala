package com.dearlordylord.bend.idea.features.navigation

import com.dearlordylord.bend.idea.toolchain.api.{
  BendToolchainChoices,
  BendToolchainSettings
}
import com.dearlordylord.bend.idea.symbols.index.{
  BendChooseByNameContributor,
  BendDottedComponentIndex
}
import com.dearlordylord.bend.idea.symbols.index.BendConfiguredSymbolRoots
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.navigation.NavigationItem
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.util.ThrowableComputable
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.DumbModeTestUtils
import com.intellij.util.AstLoadingFilter
import com.intellij.util.Processor
import com.intellij.util.indexing.{
  FileBasedIndex,
  FindSymbolParameters,
  IdFilter
}
import com.intellij.psi.stubs.StubIndex
import com.intellij.openapi.project.DumbService
import org.junit.Assert.*
import java.nio.file.{Files, Path}
import scala.collection.mutable
import scala.jdk.CollectionConverters.*
import scala.annotation.nowarn

final class BendSymbolSearchTest extends BasePlatformTestCase:
  private var original: BendToolchainChoices = null
  private var temporary: Path = null
  private var contributor: BendChooseByNameContributor = null

  override def setUp(): Unit =
    super.setUp()
    val settings = ApplicationManager.getApplication.getService(
      classOf[BendToolchainSettings]
    )
    original = settings.choices
    temporary = Files.createTempDirectory("bend-symbol-search")
    val base = Files.writeString(
      temporary.resolve("base.bend"),
      "def baseOnly():\n  0\n"
    )
    val cache = Files.createDirectories(temporary.resolve("cache/0xabc"))
    val cachedFile = Files.writeString(
      cache.resolve("module.bend"),
      "def cachedOnly():\n  0\n"
    )
    LocalFileSystem.getInstance().refreshAndFindFileByPath(base.toString)
    LocalFileSystem.getInstance().refreshAndFindFileByPath(cachedFile.toString)
    settings.update(
      BendToolchainChoices(
        baseSource = base.toString,
        packageCache = cache.toString
      )
    )
    IndexingTestUtil.waitUntilIndexesAreReady(getProject)
    contributor = new BendChooseByNameContributor

  override def tearDown(): Unit =
    try
      ApplicationManager.getApplication
        .getService(classOf[BendToolchainSettings])
        .update(original)
      val walk = Files.walk(temporary)
      try
        walk
          .sorted(java.util.Comparator.reverseOrder())
          .forEach(path => Files.deleteIfExists(path))
      finally walk.close()
    finally super.tearDown()

  private def names(): Set[String] =
    names(GlobalSearchScope.allScope(getProject))

  private def names(scope: GlobalSearchScope): Set[String] =
    names(scope, null)

  private def names(scope: GlobalSearchScope, filter: IdFilter): Set[String] =
    val found = mutable.LinkedHashSet.empty[String]
    contributor.processNames(
      new Processor[String]:
        override def process(value: String): Boolean = found.add(value)
      ,
      scope,
      filter
    )
    found.toSet

  private def items(name: String): List[NavigationItem] =
    val found = mutable.ListBuffer.empty[NavigationItem]
    val scope = GlobalSearchScope.allScope(getProject)
    contributor.processElementsWithName(
      name,
      new Processor[NavigationItem]:
        override def process(item: NavigationItem): Boolean =
          found += item
          true
      ,
      new FindSymbolParameters(name, name, scope)
    )
    found.toList

  // IntelliJ exposes no nondeprecated FindSymbolParameters API that accepts a custom IdFilter.
  @nowarn("cat=deprecation")
  private def parametersWithFilter(
      name: String,
      scope: GlobalSearchScope,
      filter: IdFilter
  ): FindSymbolParameters =
    new FindSymbolParameters(name, name, scope, filter)

  def testGlobalSearchFindsUnimportedBendDeclarationsByName(): Unit =
    myFixture.addFileToProject(
      "library.bend",
      "def U32.add():\n  0\nlaw Laws.claim:\n  Type\ntype Shape is Data:\n  Circle{}\n"
    )

    assertTrue(names().contains("U32.add"))
    assertTrue(names().contains("Laws.claim"))
    assertTrue(names().contains("Shape"))
    assertTrue(names().contains("Circle"))
    assertEquals("U32.add", items("U32.add").head.getName)
    val dotted =
      myFixture.addFileToProject("alias.bend", "def M.U32.add():\n  0\n")
    assertTrue(
      StubIndex
        .getElements(
          BendDottedComponentIndex.Key,
          "U32.add",
          getProject,
          GlobalSearchScope.allScope(getProject),
          classOf[com.dearlordylord.bend.idea.syntax.psi.BendDeclaration]
        )
        .asScala
        .exists(_.getContainingFile.getVirtualFile == dotted.getVirtualFile)
    )

  def testDuplicateNamesRemainDistinctAndShowTheirSourceFiles(): Unit =
    val first = myFixture.addFileToProject("first.bend", "def same():\n  0\n")
    val second =
      myFixture.addFileToProject("second.bend", "law same:\n  Type\n")

    val found = items("same")
    assertEquals(2, found.size)
    assertEquals(
      Set(first.getVirtualFile, second.getVirtualFile),
      found
        .map(
          _.asInstanceOf[
            com.intellij.psi.PsiElement
          ].getContainingFile.getVirtualFile
        )
        .toSet
    )
    assertEquals(
      Set("def same", "law same"),
      found.map(_.getPresentation.getPresentableText).toSet
    )
    found.head.navigate(false)
    assertTrue(
      FileEditorManager
        .getInstance(getProject)
        .isFileOpen(
          found.head
            .asInstanceOf[com.intellij.psi.PsiElement]
            .getContainingFile
            .getVirtualFile
        )
    )

  def testOpenFilesOutsideTheSearchScopeDoNotContributeNames(): Unit =
    val included =
      myFixture.addFileToProject("included.bend", "def inScopeName():\n  0\n")
    val excluded = myFixture.addFileToProject(
      "excluded.bend",
      "def outOfScopeName():\n  0\n"
    )
    myFixture.openFileInEditor(excluded.getVirtualFile)

    val requestedScope = GlobalSearchScope.fileScope(included)
    val effectiveScope =
      BendConfiguredSymbolRoots.searchScope(requestedScope, getProject)
    assertFalse(requestedScope.contains(excluded.getVirtualFile))
    assertFalse(effectiveScope.contains(excluded.getVirtualFile))
    val scopedNames = names(requestedScope)
    assertTrue(scopedNames.contains("inScopeName"))
    assertFalse(
      "Leaked names: " + scopedNames,
      scopedNames.contains("outOfScopeName")
    )
    assertFalse(
      "Leaked names: " + scopedNames,
      scopedNames.contains("baseOnly")
    )
    assertFalse(
      "Leaked names: " + scopedNames,
      scopedNames.contains("cachedOnly")
    )

  def testOpenFilesExcludedByIdFilterDoNotContributeNamesOrElements(): Unit =
    val open = myFixture.addFileToProject(
      "filtered-open.bend",
      "def filteredOpenName():\n  0\n"
    )
    myFixture.openFileInEditor(open.getVirtualFile)
    val excludedFileId = FileBasedIndex.getFileId(open.getVirtualFile)
    val filter = new IdFilter:
      override def containsFileId(fileId: Int): Boolean =
        fileId != excludedFileId
    val scope = GlobalSearchScope.allScope(getProject)

    assertFalse(names(scope, filter).contains("filteredOpenName"))

    val found = mutable.ListBuffer.empty[NavigationItem]
    contributor.processElementsWithName(
      "filteredOpenName",
      new Processor[NavigationItem]:
        override def process(item: NavigationItem): Boolean =
          found += item
          true
      ,
      parametersWithFilter("filteredOpenName", scope, filter)
    )
    assertTrue(found.isEmpty)

  def testNameEnumerationStopsWhenTheProcessorStops(): Unit =
    val open = myFixture.addFileToProject(
      "open-cancel.bend",
      "def openCancelOne():\n  0\ndef openCancelTwo():\n  0\n"
    )
    myFixture.openFileInEditor(open.getVirtualFile)
    val received = mutable.ListBuffer.empty[String]
    contributor.processNames(
      new Processor[String]:
        override def process(value: String): Boolean =
          received += value
          false
      ,
      GlobalSearchScope.allScope(getProject),
      null
    )

    assertEquals(1, received.size)

  def testElementEnumerationStopsWhenTheProcessorStops(): Unit =
    myFixture.addFileToProject(
      "closed-cancel.bend",
      "def cancelResult():\n  0\n"
    )
    val open = myFixture.addFileToProject(
      "open-cancel-result.bend",
      "law cancelResult:\n  Type\n"
    )
    myFixture.openFileInEditor(open.getVirtualFile)
    var received = 0
    contributor.processElementsWithName(
      "cancelResult",
      new Processor[NavigationItem]:
        override def process(item: NavigationItem): Boolean =
          received += 1
          false
      ,
      new FindSymbolParameters(
        "cancelResult",
        "cancelResult",
        GlobalSearchScope.allScope(getProject)
      )
    )

    assertEquals(1, received)

  def testDumbModeOpenFileFallbackStopsWhenTheProcessorStops(): Unit =
    val open = myFixture.addFileToProject(
      "dumb-cancel.bend",
      "def dumbCancelOne():\n  0\ndef dumbCancelTwo():\n  0\n"
    )
    myFixture.openFileInEditor(open.getVirtualFile)
    val received = mutable.ListBuffer.empty[String]
    val dumb = DumbService.getInstance(getProject)
    DumbModeTestUtils.computeInDumbModeSynchronously(
      getProject,
      new ThrowableComputable[Unit, Throwable]:
        override def compute(): Unit =
          assertTrue(dumb.isDumb)
          contributor.processNames(
            new Processor[String]:
              override def process(value: String): Boolean =
                received += value
                false
            ,
            GlobalSearchScope.allScope(getProject),
            null
          )
    )

    assertEquals(1, received.size)

  def testOpenUnsavedBufferReplacesItsIndexedDeclarations(): Unit =
    val file = myFixture.addFileToProject("edited.bend", "def before():\n  0\n")
    myFixture.openFileInEditor(file.getVirtualFile)
    val editor = myFixture.getEditor
    WriteCommandAction.runWriteCommandAction(
      getProject,
      new Runnable:
        override def run(): Unit =
          editor.getDocument.setText("def after():\n  1\n")
    )
    com.intellij.psi.PsiDocumentManager
      .getInstance(getProject)
      .commitAllDocuments()

    assertTrue(names().contains("after"))
    assertFalse(items("before").exists(_.getName == "before"))
    assertEquals(
      file.getVirtualFile,
      items("after").head
        .asInstanceOf[com.intellij.psi.PsiElement]
        .getContainingFile
        .getVirtualFile
    )

  def testGlobalSearchDoesNotExposeUnimportedSymbolsToCompletion(): Unit =
    myFixture.addFileToProject("unimported.bend", "def globallyKnown():\n  0\n")
    myFixture.configureByText("main.bend", "def main():\n  globally<caret>\n")

    assertTrue(names().contains("globallyKnown"))
    val completions =
      Option(myFixture.completeBasic()).getOrElse(Array.empty[LookupElement])
    assertFalse(completions.exists(_.getLookupString == "globallyKnown"))

  def testBaseAndCachedBendSourcesAreSearchable(): Unit =
    assertTrue(names().contains("baseOnly"))
    assertTrue(names().contains("cachedOnly"))
    val projectScopeNames = names(GlobalSearchScope.projectScope(getProject))
    assertTrue(projectScopeNames.contains("baseOnly"))
    assertTrue(projectScopeNames.contains("cachedOnly"))

  def testChangingConfiguredLibraryRootsChangesSearchResults(): Unit =
    val replacement =
      Files.createDirectories(temporary.resolve("replacement/cache/0xdef"))
    val nextBase = Files.writeString(
      temporary.resolve("replacement/base.bend"),
      "def replacementBase():\n  0\n"
    )
    val nextCached = Files.writeString(
      replacement.resolve("module.bend"),
      "def replacementCached():\n  0\n"
    )
    LocalFileSystem.getInstance().refreshAndFindFileByPath(nextBase.toString)
    LocalFileSystem.getInstance().refreshAndFindFileByPath(nextCached.toString)
    ApplicationManager.getApplication
      .getService(classOf[BendToolchainSettings])
      .update(
        BendToolchainChoices(
          baseSource = nextBase.toString,
          packageCache = temporary.resolve("replacement/cache").toString
        )
      )
    assertTrue(
      BendConfiguredSymbolRoots
        .roots(getProject)
        .exists(_.getPath == nextBase.toString)
    )
    assertTrue(
      com.intellij.openapi.roots.ProjectFileIndex
        .getInstance(getProject)
        .isInLibrarySource(
          LocalFileSystem.getInstance().findFileByPath(nextBase.toString)
        )
    )
    assertTrue(
      com.intellij.openapi.roots.ProjectFileIndex
        .getInstance(getProject)
        .isInLibrarySource(
          LocalFileSystem.getInstance().findFileByPath(nextCached.toString)
        )
    )
    IndexingTestUtil.waitUntilIndexesAreReady(getProject)

    val updated = names()
    assertTrue(updated.contains("replacementBase"))
    assertTrue(updated.contains("replacementCached"))
    assertFalse("Unexpected names: " + updated, updated.contains("baseOnly"))
    assertFalse("Unexpected names: " + updated, updated.contains("cachedOnly"))

  def testRepeatedWorkspaceQueriesUseStubsWithoutLoadingProjectTrees(): Unit =
    myFixture.addFileToProject("closed.bend", "def indexedOnly():\n  0\n")
    assertTrue(names().contains("indexedOnly"))

    val repeated = AstLoadingFilter.disallowTreeLoading(
      new ThrowableComputable[Set[String], RuntimeException]:
        override def compute(): Set[String] = names()
    )
    assertTrue(repeated.contains("indexedOnly"))

  def testOpenBufferRemainsSearchableWhileIndexesAreUnavailable(): Unit =
    val file =
      myFixture.addFileToProject("open.bend", "def localWhileDumb():\n  0\n")
    myFixture.openFileInEditor(file.getVirtualFile)
    val dumb = DumbService.getInstance(getProject)
    DumbModeTestUtils.computeInDumbModeSynchronously(
      getProject,
      new ThrowableComputable[Unit, Throwable]:
        override def compute(): Unit =
          assertTrue(dumb.isDumb)
          assertTrue(names().contains("localWhileDumb"))
          assertEquals(
            file.getVirtualFile,
            items("localWhileDumb").head
              .asInstanceOf[com.intellij.psi.PsiElement]
              .getContainingFile
              .getVirtualFile
          )
    )
