package com.dearlordylord.bend.idea.symbols.index

import com.dearlordylord.bend.idea.syntax.BendLanguage
import com.dearlordylord.bend.idea.syntax.psi.BendDeclaration
import com.intellij.navigation.{ChooseByNameContributorEx, NavigationItem}
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.psi.{PsiFile, PsiManager}
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.stubs.StubIndex
import com.intellij.util.Processor
import com.intellij.util.indexing.{
  FileBasedIndex,
  FindSymbolParameters,
  IdFilter
}
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.psi.util.PsiTreeUtil
import scala.collection.mutable
import scala.jdk.CollectionConverters.*

/** Global declaration discovery for Navigate | Symbol. This does not define
  * Bend visibility.
  */
final class BendChooseByNameContributor extends ChooseByNameContributorEx:
  override def processNames(
      processor: Processor[? >: String],
      scope: GlobalSearchScope,
      filter: IdFilter
  ): Unit =
    val project = scope.getProject
    if project == null then return
    val searchScope = BendConfiguredSymbolRoots.searchScope(scope, project)
    val openFiles = currentFiles(project).filter(file =>
      isIncluded(file, searchScope, filter)
    )
    val indexFilter = excludingOpenFiles(filter, openFiles)
    val emitted = mutable.HashSet.empty[String]
    def emit(name: String): Boolean =
      if name.isEmpty || !emitted.add(name) then true
      else processor.process(name)

    var continue = true
    if !DumbService.getInstance(project).isDumb then
      try
        val indexedNames = mutable.ArrayBuffer.empty[String]
        val _ = StubIndex.getInstance.processAllKeys(
          BendSymbolNameIndex.Key,
          new Processor[String]:
            override def process(name: String): Boolean =
              indexedNames += name
              true
          ,
          searchScope,
          indexFilter
        )
        // A per-name stub lookup can repair a stale index. Do it after key
        // enumeration releases its index read lock, or the repair can wait on
        // the write lock held behind that same enumeration.
        val namesToCheck = indexedNames.iterator
        while namesToCheck.hasNext && continue do
          val name = namesToCheck.next()
          val existsInScope = !StubIndex.getInstance.processElements(
            BendSymbolNameIndex.Key,
            name,
            project,
            searchScope,
            indexFilter,
            classOf[BendDeclaration],
            new Processor[BendDeclaration]:
              override def process(declaration: BendDeclaration): Boolean =
                val file = declaration.getContainingFile
                val virtual = if file == null then null else file.getVirtualFile
                val isInScope = declaration.isValid && virtual != null &&
                  searchScope.contains(virtual)
                !isInScope
          )
          if existsInScope then continue = emit(name)
      catch case _: IndexNotReadyException => ()
    if continue then
      val openDeclarations = openFiles.iterator.flatMap(file =>
        PsiTreeUtil
          .findChildrenOfType(file, classOf[BendDeclaration])
          .asScala
          .iterator
      )
      while openDeclarations.hasNext && continue do
        continue = emit(openDeclarations.next().getName)

  override def processElementsWithName(
      name: String,
      processor: Processor[? >: NavigationItem],
      parameters: FindSymbolParameters
  ): Unit =
    val project = parameters.getProject
    if project == null || name == null || name.isEmpty then return
    val scope =
      BendConfiguredSymbolRoots.searchScope(parameters.getSearchScope, project)
    val openFiles = currentFiles(project).filter(file =>
      isIncluded(file, scope, parameters.getIdFilter)
    )
    val indexFilter = excludingOpenFiles(parameters.getIdFilter, openFiles)
    val emitted = mutable.HashSet.empty[BendDeclaration]

    def emit(declaration: BendDeclaration): Boolean =
      if declaration == null || !declaration.isValid || declaration.getName != name
      then true
      else
        val file = declaration.getContainingFile
        val virtual = if file == null then null else file.getVirtualFile
        if virtual == null || !scope.contains(virtual) then true
        else if !emitted.add(declaration) then true
        else processor.process(declaration)

    var continue = true
    if !DumbService.getInstance(project).isDumb then
      try
        continue = StubIndex.getInstance.processElements(
          BendSymbolNameIndex.Key,
          name,
          project,
          scope,
          indexFilter,
          classOf[BendDeclaration],
          new Processor[BendDeclaration]:
            override def process(declaration: BendDeclaration): Boolean =
              emit(declaration)
        )
      catch case _: IndexNotReadyException => ()
    if continue then
      val currentDeclarations = openFiles.iterator.flatMap(file =>
        PsiTreeUtil
          .findChildrenOfType(file, classOf[BendDeclaration])
          .asScala
          .iterator
      )
      while currentDeclarations.hasNext && continue do
        continue = emit(currentDeclarations.next())

  private def excludingOpenFiles(
      filter: IdFilter,
      openFiles: List[PsiFile]
  ): IdFilter =
    val openFileIds = openFiles
      .flatMap(file => Option(file.getVirtualFile))
      .map(FileBasedIndex.getFileId)
      .toSet
    if openFileIds.isEmpty then filter
    else
      new IdFilter:
        override def containsFileId(fileId: Int): Boolean =
          !openFileIds.contains(fileId) && (filter == null || filter
            .containsFileId(fileId))

  private def isIncluded(
      file: PsiFile,
      scope: GlobalSearchScope,
      filter: IdFilter
  ): Boolean =
    Option(file.getVirtualFile).exists { virtualFile =>
      scope.contains(virtualFile) &&
      (filter == null || filter.containsFileId(
        FileBasedIndex.getFileId(virtualFile)
      ))
    }

  private def currentFiles(project: Project): List[PsiFile] =
    val manager = PsiManager.getInstance(project)
    FileEditorManager
      .getInstance(project)
      .getOpenFiles
      .iterator
      .filter(file => file.isValid && file.getName.endsWith(".bend"))
      .flatMap(file => Option(manager.findFile(file)))
      .filter(_.getLanguage == BendLanguage.instance)
      .toList
