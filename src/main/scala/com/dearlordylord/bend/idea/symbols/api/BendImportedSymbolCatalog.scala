package com.dearlordylord.bend.idea.symbols.api

import com.dearlordylord.bend.idea.model.FileId
import com.dearlordylord.bend.idea.workspace.api.{
  BendImportLines,
  BendWorkspaceGraph
}
import com.dearlordylord.bend.idea.workspace.model.*
import com.dearlordylord.bend.idea.symbols.declarations.BendLogicalLaw
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile

/** Short-lived source resolution input for a bounded batch of lookups. PSI
  * entries must be consumed in read actions; the captured graph is immutable
  * and is never cached across source revisions.
  */
final case class BendSourceNavigationSnapshot private[api] (
    file: PsiFile,
    revision: Long,
    sourceLength: Int,
    graph: BendLoadedGraph,
    direct: List[(String, BendSourceSymbol)],
    base: List[(String, BendSourceSymbol)],
    declarations: List[BendSourceSymbol],
    logicalLaws: List[BendLogicalLaw[BendSourceSymbol]]
)

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

  private def directDeclarations(
      graph: BendLoadedGraph,
      declarationsBySource: Map[FileId, List[BendSourceSymbol]]
  ): List[(String, BendSourceSymbol)] =
    effectiveDirectEdges(graph).flatMap(edge =>
      for
        alias <- edge.importLine.alias.toList
        target <- edge.target.toList
        symbols <- declarationsBySource.get(target).toList
        symbol <- symbols
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

  private def baseDeclarations(
      graph: BendLoadedGraph,
      declarationsBySource: Map[FileId, List[BendSourceSymbol]]
  ): List[(String, BendSourceSymbol)] =
    graph.files
      .filter(f => f.namespace.isEmpty && f.source.id != graph.root)
      .flatMap(f =>
        declarationsBySource
          .getOrElse(f.source.id, Nil)
          .map(symbol => (symbol.name, symbol))
      )

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
    resolveForNavigation(
      navigationSnapshot(file, basePath, packageCache),
      offset,
      spelling,
      category
    )

  /** Capture the graph and source declarations once for a batch of related
    * lookups. This avoids reloading every import graph for each call site.
    */
  def navigationSnapshot(
      file: PsiFile,
      basePath: String,
      packageCache: String,
      canceled: () => Boolean = () => false
  ): BendSourceNavigationSnapshot =
    val root = ReadAction.compute(() => {
      val id = BendSourceSymbols.fileId(file)
      val path = Option(file.getVirtualFile)
        .map(_.getPath)
        .orElse(Option(file.getOriginalFile.getVirtualFile).map(_.getPath))
        .getOrElse(file.getName)
      val text = file.getText
      BendSourceRecord(
        id,
        path,
        text,
        file.getModificationStamp,
        BendImportLines.parse(text)
      )
    })
    val graph = project
      .getService(classOf[BendWorkspaceGraph])
      .load(root, basePath, packageCache, canceled)
    val declarationsBySource = scala.collection.mutable.Map.empty[
      FileId,
      List[BendSourceSymbol]
    ]
    val sources = graph.files.iterator
    while sources.hasNext && !canceled() do
      val source = sources.next().source
      if !declarationsBySource.contains(source.id) then
        val sourceDeclarations = ReadAction.compute(() => declarations(source))
        declarationsBySource.update(source.id, sourceDeclarations)
    val (direct, base) = ReadAction.compute(() =>
      (
        directDeclarations(graph, declarationsBySource.toMap),
        baseDeclarations(graph, declarationsBySource.toMap)
      )
    )
    val currentDeclarations =
      ReadAction.compute(() => BendSourceSymbols.declarations(file))
    BendSourceNavigationSnapshot(
      file,
      root.revision,
      root.text.length,
      graph,
      direct,
      base,
      currentDeclarations,
      BendSourceSymbols.logicalLaws(currentDeclarations)
    )

  /** Visible source declarations from one captured root graph. PSI-backed
    * declarations are consumed by callers inside a read action; graph I/O is
    * never repeated here.
    */
  def visibleWithNavigationSnapshot(
      snapshot: BendSourceNavigationSnapshot,
      offset: Int
  ): (List[BendSourceSymbol], List[(String, BendSourceSymbol)]) =
    val expandedAliases = snapshot.direct.iterator.map(_._1).toSet
    val current = BendSourceSymbols
      .visibleCandidates(
        snapshot.declarations,
        offset,
        snapshot.logicalLaws
      )
      .filterNot(symbol => expandedAliases.contains(symbol.name))
    val currentKeys =
      current.map(symbol => symbol.name -> symbol.category).toSet
    val eligibleBase = snapshot.base.filter { case (name, symbol) =>
      !expandedAliases.contains(name) &&
      !currentKeys.contains(name -> symbol.category)
    }
    (current, snapshot.direct ++ eligibleBase)

  /** Source-order navigation against a short-lived captured graph. */
  def resolveForNavigation(
      snapshot: BendSourceNavigationSnapshot,
      offset: Int,
      spelling: String,
      category: Option[BendSymbolCategory]
  ): BendNavigationResolution =
    val expanded = snapshot.direct.iterator.map(_._1).toSet
    val current = BendSourceSymbols
      .visibleCandidates(
        snapshot.declarations,
        offset,
        snapshot.logicalLaws
      )
      .filterNot(symbol => expanded.contains(symbol.name))
    val currentKeys = current.map(s => (s.name, s.category)).toSet
    val eligibleBase = snapshot.base.filter { case (name, symbol) =>
      !expanded.contains(name) && !currentKeys.contains((name, symbol.category))
    }
    val binding =
      if category.forall(_ == BendSymbolCategory.Binder) then
        BendSourceSymbols
          .visibleBindings(
            snapshot.file,
            offset,
            snapshot.declarations,
            snapshot.logicalLaws
          )
          .find(_.name == spelling)
      else None
    val target = binding match
      case Some(value) => BendSourceResolution.ResolvedBinder(value)
      case None        =>
        val matches = current.filter(s =>
          s.name == spelling && category.forall(_ == s.category)
        ) ++ snapshot.direct.collect {
          case (`spelling`, symbol) if category.forall(_ == symbol.category) =>
            symbol
        } ++ eligibleBase.collect {
          case (`spelling`, symbol) if category.forall(_ == symbol.category) =>
            symbol
        }
        matches match
          case Nil        => BendSourceResolution.Unresolved
          case one :: Nil => BendSourceResolution.Resolved(one)
          case many       => BendSourceResolution.Ambiguous(many)
    val eligible = target
    if eligible != BendSourceResolution.Unresolved then
      BendNavigationResolution(eligible, true)
    else
      val later = snapshot.declarations
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
