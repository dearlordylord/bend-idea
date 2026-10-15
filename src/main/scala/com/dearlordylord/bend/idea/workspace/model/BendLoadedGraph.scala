package com.dearlordylord.bend.idea.workspace.model

import com.dearlordylord.bend.idea.model.FileId

/** Import spelling and source range belong to the importing revision. */
final case class BendImport(
    spelling: String,
    alias: Option[String],
    offset: Int,
    syntaxProblem: Option[String] = None
)

final case class BendSourceRecord(
    id: FileId,
    path: String,
    text: String,
    revision: Long,
    imports: List[BendImport]
)

final case class BendLoadedFile(source: BendSourceRecord, namespace: String)
final case class BendLoadedEdge(
    from: FileId,
    importLine: BendImport,
    requestedPath: String,
    namespace: String,
    target: Option[FileId]
)

enum BendGraphProblem:
  case InvalidImport(from: FileId, importLine: BendImport, reason: String)
  case Missing(from: FileId, importLine: BendImport, requestedPath: String)
  case Cycle(from: FileId, importLine: BendImport, target: FileId)

/** A name lookup is a loading input even when the cache entry is missing. */
final case class BendCachedPackage(name: String, hash: Option[String])

enum BendGraphLimit:
  case SourceLookups, GraphFiles

/** Root-specific loading facts. Source identity is not a compiler namespace. */
final case class BendLoadedGraph(
    root: FileId,
    files: List[BendLoadedFile],
    edges: List[BendLoadedEdge],
    problems: List[BendGraphProblem],
    sourceInventoryLimits: Set[BendGraphLimit] = Set.empty,
    packageCacheIdentity: String = "",
    cachedPackages: List[BendCachedPackage] = Nil
):
  def sourceInventoryCapped: Boolean = sourceInventoryLimits.nonEmpty

  def source(id: FileId): Option[BendSourceRecord] =
    files.find(_.source.id == id).map(_.source)

  private lazy val invalidImports = problems.map {
    case BendGraphProblem.InvalidImport(file, imp, _) => (file, imp.offset)
    case BendGraphProblem.Missing(file, imp, _)       => (file, imp.offset)
    case BendGraphProblem.Cycle(file, imp, _)         => (file, imp.offset)
  }.toSet

  private lazy val loadedIdentities =
    files.map(file => (file.source.id, file.namespace)).toSet

  private def validatedTarget(edge: BendLoadedEdge): Option[FileId] =
    if invalidImports.contains((edge.from, edge.importLine.offset)) then None
    else
      edge.target.filter(id => loadedIdentities.contains((id, edge.namespace)))

  /** Bulk equivalent of importTarget. Indexes each validation fact once and
    * preserves the first written edge at a source offset, including an invalid
    * first edge. Canonical source identity and namespace must both match.
    */
  def validImportEdges: List[(BendLoadedEdge, FileId)] =
    edges.distinctBy(edge => (edge.from, edge.importLine.offset)).flatMap {
      edge =>
        validatedTarget(edge).map(target => (edge, target))
    }

  /** A written import can navigate only when its own edge passed loader
    * validation.
    */
  def importTarget(from: FileId, offset: Int): Option[FileId] =
    edges
      .find(edge => edge.from == from && edge.importLine.offset == offset)
      .flatMap(validatedTarget)
