package com.dearlordylord.bend.idea.symbols.api

import com.dearlordylord.bend.idea.model.FileId
import com.dearlordylord.bend.idea.workspace.api.{
  BendImportLines,
  BendWorkspaceGraph
}
import com.dearlordylord.bend.idea.workspace.model.*
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile

/** The source symbol owner consumes one workspace loading policy for completion
  * and later references.
  */
final class BendImportedSymbolCatalog(project: Project):
  private var parsed = Map.empty[FileId, (String, List[BendSourceSymbol])]

  def loaded(
      file: PsiFile,
      basePath: String,
      packageCache: String
  ): BendLoadedGraph =
    val id = BendSourceSymbols.fileId(file)
    val path = Option(file.getVirtualFile)
      .map(_.getPath)
      .orElse(Option(file.getOriginalFile.getVirtualFile).map(_.getPath))
      .getOrElse(file.getName)
    val text = file.getText
    val root = BendSourceRecord(
      id,
      path,
      text,
      file.getModificationStamp,
      BendImportLines.parse(text)
    )
    project
      .getService(classOf[BendWorkspaceGraph])
      .load(root, basePath, packageCache)

  def visible(
      file: PsiFile,
      basePath: String,
      packageCache: String
  ): (BendLoadedGraph, List[(String, BendSourceSymbol)]) =
    val graph = loaded(file, basePath, packageCache)
    (graph, directDeclarations(graph) ++ baseDeclarations(graph))

  private def directDeclarations(
      graph: BendLoadedGraph
  ): List[(String, BendSourceSymbol)] =
    // book_load's alias map is overwritten by each later direct import. Keep all
    // graph edges for diagnostics, but expose only the last edge for each alias.
    val direct = effectiveDirectEdges(graph)
    direct.flatMap(edge =>
      for
        alias <- edge.importLine.alias.toList
        target <- edge.target.toList
        source <- graph.source(target).toList
        symbol <- declarations(source)
      yield (alias + "." + symbol.name, symbol)
    )

  /** Last alias wins, but only if that source actually loaded under the edge's
    * namespace.
    */
  private[api] def effectiveDirectEdges(
      graph: BendLoadedGraph
  ): List[BendLoadedEdge] =
    graph.edges
      .filter(edge => edge.from == graph.root && edge.importLine.alias.nonEmpty)
      .reverse
      .distinctBy(_.importLine.alias)
      .reverse
      .filter(edge =>
        edge.target.exists(target =>
          graph.files
            .exists(f => f.source.id == target && f.namespace == edge.namespace)
        )
      )

  /** Last valid direct alias that points at one canonical loaded source. */
  def aliasForTarget(
      graph: BendLoadedGraph,
      target: FileId
  ): Option[String] =
    effectiveDirectEdges(graph)
      .filter(_.target.contains(target))
      .flatMap(_.importLine.alias)
      .lastOption

  private def baseDeclarations(
      graph: BendLoadedGraph
  ): List[(String, BendSourceSymbol)] =
    graph.files
      .filter(f => f.namespace.isEmpty && f.source.id != graph.root)
      .flatMap(f => declarations(f.source).map(s => (s.name, s)))

  /** Alias expansion wins when the imported target exists; otherwise a literal
    * dotted declaration remains available, matching parse_reso's fallback.
    */
  def visibleWithCurrent(
      file: PsiFile,
      offset: Int,
      basePath: String,
      packageCache: String
  ): (
      BendLoadedGraph,
      List[BendSourceSymbol],
      List[(String, BendSourceSymbol)]
  ) =
    val graph = loaded(file, basePath, packageCache)
    val direct = directDeclarations(graph)
    val expanded = direct.iterator.map(_._1).toSet
    val current = BendSourceSymbols
      .visibleCandidates(file, offset)
      .filterNot(symbol => expanded.contains(symbol.name))
    val currentKeys =
      current.map(symbol => (symbol.name, symbol.category)).toSet
    val eligibleBase = baseDeclarations(graph).filter { case (name, symbol) =>
      !expanded.contains(name) && !currentKeys.contains((name, symbol.category))
    }
    (graph, current, direct ++ eligibleBase)

  def resolve(
      file: PsiFile,
      offset: Int,
      spelling: String,
      basePath: String,
      packageCache: String,
      category: Option[BendSymbolCategory] = None
  ): BendSourceResolution =
    val (_, current, imported) =
      visibleWithCurrent(file, offset, basePath, packageCache)
    val binding = if category.forall(_ == BendSymbolCategory.Binder) then
      BendSourceSymbols.visibleBindings(file, offset).find(_.name == spelling)
    else None
    binding match
      case Some(value) => BendSourceResolution.ResolvedBinder(value)
      case None        =>
        val matches = current.filter(s =>
          s.name == spelling && category.forall(_ == s.category)
        ) ++
          imported.collect {
            case (`spelling`, s) if category.forall(_ == s.category) => s
          }
        matches match
          case Nil        => BendSourceResolution.Unresolved
          case one :: Nil => BendSourceResolution.Resolved(one)
          case many       => BendSourceResolution.Ambiguous(many)

  /** Navigation may expose a later declaration, but its eligibility remains
    * explicit.
    */
  def resolveForNavigation(
      file: PsiFile,
      offset: Int,
      spelling: String,
      basePath: String,
      packageCache: String,
      category: Option[BendSymbolCategory] = None
  ): BendNavigationResolution =
    val eligible =
      resolve(file, offset, spelling, basePath, packageCache, category)
    if eligible != BendSourceResolution.Unresolved then
      BendNavigationResolution(eligible, true)
    else
      val later = BendSourceSymbols
        .declarations(file)
        .filter(s =>
          s.handle.nameOffset >= offset && s.name == spelling && category
            .forall(_ == s.category)
        )
      later match
        case one :: Nil =>
          BendNavigationResolution(BendSourceResolution.Resolved(one), false)
        case many if many.nonEmpty =>
          BendNavigationResolution(BendSourceResolution.Ambiguous(many), false)
        case _ =>
          BendNavigationResolution(BendSourceResolution.Unresolved, false)

  private def declarations(source: BendSourceRecord): List[BendSourceSymbol] =
    synchronized {
      parsed.get(source.id) match
        case Some((text, symbols)) if text == source.text => symbols
        case _                                            =>
          val symbols = BendSourceSymbols.loadedDeclarations(
            project,
            source.id,
            source.text
          )
          // Bounded by the latest 128 source revisions; text equality handles stamp collisions.
          if parsed.size >= 128 then parsed = parsed.drop(1)
          parsed = parsed.updated(source.id, (source.text, symbols))
          symbols
    }
