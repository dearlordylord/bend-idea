package com.dearlordylord.bend.idea.features.proofs

import com.dearlordylord.bend.idea.analysis.api.BendCheckService
import com.dearlordylord.bend.idea.symbols.api.{
  BendPhysicalTargets,
  BendSourceDocumentation,
  BendSourceHandle,
  BendSourceSymbols,
  BendSourceSymbol,
  BendSymbolCategory
}
import com.dearlordylord.bend.idea.syntax.psi.{BendProofForm, BendProofSurface}
import com.dearlordylord.bend.idea.workspace.api.{
  BendSourceCatalog,
  BendLoadingConfiguration,
  BendWorkspaceGraph,
  BendWorkspacePaths
}
import com.intellij.openapi.project.Project
import com.intellij.openapi.application.ReadAction
import scala.util.control.NonFatal

/** Reads a root and its current imports through the shared workspace loader. */
class BendProofProgressReader(project: Project):
  private val RootLimit = 128
  private val EntryLimit = 2048
  private val SourceFileLimit = 256
  private val SourceCharacterLimit = 250000
  private val TokenLimitPerFile = 25000

  def roots(): List[String] =
    val saved = project.getService(classOf[BendProofRootStore]).selectedPaths
    val suggested = project
      .getService(classOf[BendWorkspacePaths])
      .filesNamed("PROOF.bend", RootLimit)
    (saved ++ suggested).distinct.take(RootLimit)

  def read(
      rootPath: String,
      canceled: () => Boolean
  ): Option[BendProofProgressSnapshot] =
    if canceled() then return None
    val catalog = project.getService(classOf[BendSourceCatalog])
    catalog.source(rootPath) match
      case None =>
        Some(
          BendProofProgressSnapshot(
            rootPath,
            "Root source unavailable",
            Nil
          )
        )
      case Some(root) =>
        val (basePath, packageCache) = project
          .getService(classOf[BendLoadingConfiguration])
          .paths
        val graph = project
          .getService(classOf[BendWorkspaceGraph])
          .load(
            root,
            basePath,
            packageCache,
            canceled
          )
        if canceled() then return None
        val checkService = project.getService(classOf[BendCheckService])
        val checked = BendProofProgressModel.checkedStatus(
          checkService.status(root.id),
          checkService.result(root.id)
        )
        val inventory = ReadAction.compute(() => {
          val symbols = scala.collection.mutable.ListBuffer.empty[
            BendSourceSymbol
          ]
          var limited = graph.files.size > SourceFileLimit
          val sourceFiles = graph.files.take(SourceFileLimit)
          val accepted =
            Set(BendSymbolCategory.Law, BendSymbolCategory.Definition)
          val sourceIterator = sourceFiles.iterator
          while sourceIterator.hasNext && symbols.size < EntryLimit &&
            !canceled()
          do
            val loaded = sourceIterator.next()
            val remaining = EntryLimit - symbols.size
            BendSourceSymbols.sourceDeclarationsBounded(
              project,
              loaded.source.id,
              loaded.source.text,
              remaining,
              SourceCharacterLimit,
              accepted,
              canceled
            ) match
              case None       => ()
              case Some(scan) =>
                symbols ++= scan.symbols
                if scan.truncated then limited = true
          if symbols.size == EntryLimit then limited = true
          val declarationsById =
            graph.files.map(f => f.source.id -> f.source).toMap
          val laws = symbols.filter(_.category == BendSymbolCategory.Law).toList
          val related = scala.collection.mutable.Map.empty[
            BendSourceHandle,
            String
          ]
          BendPhysicalTargets.file(project, root.id).foreach { rootFile =>
            laws.iterator.take(EntryLimit).takeWhile(_ => !canceled()).foreach {
              law =>
                try
                  BendSourceDocumentation
                    .site(rootFile, law, graph)
                    .fills
                    .foreach(fill => related(fill.handle) = law.name)
                catch case NonFatal(_) => ()
            }
          }
          val declarations = symbols.toList.map { symbol =>
            val kind = if symbol.category == BendSymbolCategory.Law then
              BendProofInventoryKind.Law
            else if related.contains(symbol.handle) then
              BendProofInventoryKind.CandidateFill
            else BendProofInventoryKind.Definition
            val label = related.get(symbol.handle) match
              case Some(lawName) => s"${symbol.name} → $lawName"
              case None          => symbol.name
            BendProofInventoryEntry(
              kind,
              label,
              declarationsById.get(symbol.handle.file).fold(rootPath)(_.path),
              symbol.handle.nameOffset
            )
          }
          val holes = scala.collection.mutable.ListBuffer.empty[
            BendProofInventoryEntry
          ]
          val fileIterator = sourceFiles.iterator
          while fileIterator.hasNext && declarations.size + holes.size < EntryLimit &&
            !canceled()
          do
            val loaded = fileIterator.next()
            val remaining = EntryLimit - declarations.size - holes.size
            BendProofSurface.holesBounded(
              loaded.source.text,
              0,
              TokenLimitPerFile,
              remaining,
              canceled
            ) match
              case None       => ()
              case Some(scan) =>
                if scan.truncated then limited = true
                scan.forms
                  .collect { case hole: BendProofForm.Hole => hole }
                  .foreach { hole =>
                    if holes.size < remaining then
                      holes += BendProofInventoryEntry(
                        BendProofInventoryKind.Hole,
                        if hole.name.isEmpty then "?" else s"?${hole.name}",
                        loaded.source.path,
                        hole.from
                      )
                  }
          if declarations.size + holes.size == EntryLimit then limited = true
          val entries = (declarations ++ holes)
            .sortBy(entry => (entry.path, entry.offset, entry.kind.ordinal))
            .take(EntryLimit)
          (entries, limited)
        })
        if canceled() then return None
        val (entries, inventoryLimited) = inventory
        val graphState =
          if graph.problems.isEmpty then ""
          else s"; ${graph.problems.size} loading issue(s)"
        Some(
          BendProofProgressSnapshot(
            rootPath,
            checked + graphState +
              (if inventoryLimited then "; inventory capped" else ""),
            entries
          )
        )
