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
import com.intellij.util.indexing.{FindSymbolParameters, IdFilter}
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.psi.util.PsiTreeUtil
import scala.collection.mutable
import scala.jdk.CollectionConverters.*

/** Global declaration discovery for Navigate | Symbol. This does not define Bend visibility. */
final class BendChooseByNameContributor extends ChooseByNameContributorEx:
  override def processNames(processor: Processor[? >: String], scope: GlobalSearchScope,
      filter: IdFilter): Unit =
    val project = scope.getProject
    if project == null then return
    val searchScope = BendConfiguredSymbolRoots.searchScope(scope, project)
    val openFiles = currentFiles(project).filter(file =>
      Option(file.getVirtualFile).exists(searchScope.contains))
    val openSet = openFiles.flatMap(file => Option(file.getVirtualFile)).toSet
    val emitted = mutable.HashSet.empty[String]
    def emit(name: String): Boolean =
      if name.isEmpty || !emitted.add(name) then true else processor.process(name)

    if !DumbService.getInstance(project).isDumb then
      try
        val indexedNames = mutable.LinkedHashSet.empty[String]
        StubIndex.getInstance.processAllKeys(BendSymbolNameIndex.Key,
          new Processor[String]:
            override def process(name: String): Boolean =
              indexedNames += name
              true,
          searchScope, filter)
        val names = indexedNames.iterator
        var continue = true
        while names.hasNext && continue do
          val name = names.next()
          val existsInScope = StubIndex.getElements(BendSymbolNameIndex.Key, name, project,
            searchScope, filter, classOf[BendDeclaration]).asScala.exists { declaration =>
              val file = declaration.getContainingFile
              val virtual = if file == null then null else file.getVirtualFile
              declaration.isValid && virtual != null && searchScope.contains(virtual) &&
                !openSet.contains(virtual)
            }
          if existsInScope then continue = emit(name)
      catch
        case _: IndexNotReadyException => ()
    openFiles.foreach { file =>
      PsiTreeUtil.findChildrenOfType(file, classOf[BendDeclaration]).asScala
        .foreach(declaration => emit(declaration.getName))
    }

  override def processElementsWithName(name: String,
      processor: Processor[? >: NavigationItem], parameters: FindSymbolParameters): Unit =
    val project = parameters.getProject
    if project == null || name == null || name.isEmpty then return
    val scope = BendConfiguredSymbolRoots.searchScope(parameters.getSearchScope, project)
    val openFiles = currentFiles(project)
    val openVFiles = openFiles.flatMap(file => Option(file.getVirtualFile)).toSet
    val emitted = mutable.HashSet.empty[BendDeclaration]

    def emit(declaration: BendDeclaration): Boolean =
      if declaration == null || !declaration.isValid || declaration.getName != name then true
      else
        val file = declaration.getContainingFile
        val virtual = if file == null then null else file.getVirtualFile
        if virtual == null || !scope.contains(virtual) then true
        else
          if !emitted.add(declaration) then true else processor.process(declaration)

    if !DumbService.getInstance(project).isDumb then
      try
        val indexed = StubIndex.getElements(BendSymbolNameIndex.Key, name, project, scope,
          parameters.getIdFilter, classOf[BendDeclaration]).asScala
          .iterator.filterNot { declaration =>
            Option(declaration.getContainingFile).flatMap(file => Option(file.getVirtualFile))
              .exists(openVFiles.contains)
          }
        var continue = true
        while indexed.hasNext && continue do continue = emit(indexed.next())
      catch
        case _: IndexNotReadyException => ()
    val currentDeclarations = openFiles.iterator.flatMap(file =>
      PsiTreeUtil.findChildrenOfType(file, classOf[BendDeclaration]).asScala.iterator)
    var continue = true
    while currentDeclarations.hasNext && continue do continue = emit(currentDeclarations.next())

  private def currentFiles(project: Project): List[PsiFile] =
    val manager = PsiManager.getInstance(project)
    FileEditorManager.getInstance(project).getOpenFiles.iterator
      .filter(file => file.isValid && file.getName.endsWith(".bend"))
      .flatMap(file => Option(manager.findFile(file)))
      .filter(_.getLanguage == BendLanguage.instance)
      .toList
