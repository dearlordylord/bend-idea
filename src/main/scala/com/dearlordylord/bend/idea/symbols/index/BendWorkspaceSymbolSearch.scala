package com.dearlordylord.bend.idea.symbols.index

import com.dearlordylord.bend.idea.symbols.api.{
  BendBaseSymbolCatalog,
  BendSourceSymbols,
  BendWorkspaceSymbolCandidate
}
import com.dearlordylord.bend.idea.syntax.BendLanguage
import com.dearlordylord.bend.idea.syntax.psi.BendDeclaration
import com.dearlordylord.bend.idea.workspace.api.{
  BendBaseState,
  BendLibrarySource,
  BendLoadingConfiguration
}
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.{
  DumbService,
  IndexNotReadyException,
  Project
}
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.stubs.StubIndex
import com.intellij.util.Processor
import scala.collection.mutable
import scala.jdk.CollectionConverters.*

/** Workspace discovery for explicit import actions. This does not alter
  * ordinary name completion or declare candidates visible.
  */
object BendWorkspaceSymbolIndexSearch:
  private val MaxCandidates = 256
  private val MaxOpenFiles = 512

  def named(
      project: Project,
      name: String
  ): Either[String, List[BendWorkspaceSymbolCandidate]] =
    if name.isEmpty then Right(Nil)
    else if DumbService.getInstance(project).isDumb then
      Left("Wait for Bend indexes before importing a workspace symbol.")
    else
      val candidates = mutable.LinkedHashMap.empty[
        (
            String,
            com.dearlordylord.bend.idea.symbols.api.BendSymbolCategory,
            Int
        ),
        BendWorkspaceSymbolCandidate
      ]
      val loading =
        project.getService(classOf[BendLoadingConfiguration]).snapshot
      val basePath = loading.baseSource
      def canonical(value: String): String =
        try java.nio.file.Path.of(value).toRealPath().toString
        catch
          case _: Exception =>
            java.nio.file.Path.of(value).toAbsolutePath.normalize().toString
      val configuredBase = if basePath.nonEmpty then Some(canonical(basePath))
      else None
      val openFiles = FileEditorManager
        .getInstance(project)
        .getOpenFiles
        .iterator
        .filter(file =>
          file.isValid && !file.isDirectory && file.getName.endsWith(".bend")
        )
        .take(MaxOpenFiles + 1)
        .toList
      if openFiles.size > MaxOpenFiles then
        return Left("Too many open Bend files to search safely.")
      val openPaths = openFiles.map(_.getPath).toSet
      val manager = PsiManager.getInstance(project)

      def add(declaration: BendDeclaration, path: String): Unit =
        if candidates.size < MaxCandidates then
          val file = declaration.getContainingFile
          Option(declaration.getNameIdentifier).foreach { identifier =>
            BendSourceSymbols
              .declarations(file)
              .find(_.handle.nameOffset == identifier.getTextOffset)
              .filter(_.name == name)
              .foreach { symbol =>
                val identityPath = symbol.handle.file.value
                candidates.getOrElseUpdate(
                  (identityPath, symbol.category, symbol.handle.nameOffset),
                  BendWorkspaceSymbolCandidate(
                    symbol,
                    Some(path),
                    if configuredBase.contains(canonical(path)) then
                      "Configured Bend Base"
                    else path,
                    configuredBase.contains(canonical(path))
                  )
                )
              }
          }

      openFiles.foreach { virtual =>
        Option(manager.findFile(virtual))
          .filter(_.getLanguage == BendLanguage.instance)
          .foreach(file =>
            com.intellij.psi.util.PsiTreeUtil
              .findChildrenOfType(file, classOf[BendDeclaration])
              .asScala
              .filter(_.getName == name)
              .foreach(add(_, virtual.getPath))
          )
      }

      val scope = BendConfiguredSymbolRoots.searchScope(
        GlobalSearchScope.projectScope(project),
        project
      )
      var capped = false
      try
        val _ = StubIndex.getInstance.processElements(
          BendSymbolNameIndex.Key,
          name,
          project,
          scope,
          null,
          classOf[BendDeclaration],
          new Processor[BendDeclaration]:
            override def process(declaration: BendDeclaration): Boolean =
              val file = declaration.getContainingFile
              val virtual = if file == null then null else file.getVirtualFile
              if virtual != null && !openPaths.contains(virtual.getPath) then
                add(declaration, virtual.getPath)
              if candidates.size >= MaxCandidates then
                capped = true
                false
              else true
        )
      catch
        case _: IndexNotReadyException =>
          return Left("Bend indexes became unavailable during symbol search.")

      project
        .getService(classOf[BendLibrarySource])
        .base(
          basePath,
          loading.configurationRevision
        ) match
        case BendBaseState.Available(base) =>
          project
            .getService(classOf[BendBaseSymbolCatalog])
            .declarations(base)
            .filter(_.name == name)
            .foreach { symbol =>
              candidates.getOrElseUpdate(
                (
                  symbol.handle.file.value,
                  symbol.category,
                  symbol.handle.nameOffset
                ),
                BendWorkspaceSymbolCandidate(
                  symbol,
                  Some(base.identity),
                  "Configured Bend Base",
                  true
                )
              )
            }
        case BendBaseState.Missing(_) => ()

      if capped then
        Left(
          "Too many declarations share this name to offer a safe import choice."
        )
      else Right(candidates.values.toList)
