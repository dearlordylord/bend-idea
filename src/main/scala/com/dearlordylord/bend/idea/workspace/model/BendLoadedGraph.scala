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
  case NamespaceConflict(
      from: FileId,
      importLine: BendImport,
      target: FileId,
      previous: String,
      requested: String
  )

enum BendGraphLimit:
  case SourceLookups, GraphFiles

/** Root-specific loading facts. Source identity is not a compiler namespace. */
final case class BendLoadedGraph(
    root: FileId,
    files: List[BendLoadedFile],
    edges: List[BendLoadedEdge],
    problems: List[BendGraphProblem],
    sourceInventoryLimits: Set[BendGraphLimit] = Set.empty
):
  def sourceInventoryCapped: Boolean = sourceInventoryLimits.nonEmpty

  def source(id: FileId): Option[BendSourceRecord] =
    files.find(_.source.id == id).map(_.source)

  /** A written import can navigate only when its own edge passed loader
    * validation.
    */
  def importTarget(from: FileId, offset: Int): Option[FileId] =
    val invalid = problems.exists {
      case BendGraphProblem.InvalidImport(file, imp, _) =>
        file == from && imp.offset == offset
      case BendGraphProblem.Missing(file, imp, _) =>
        file == from && imp.offset == offset
      case BendGraphProblem.Cycle(file, imp, _) =>
        file == from && imp.offset == offset
      case BendGraphProblem.NamespaceConflict(file, imp, _, _, _) =>
        file == from && imp.offset == offset
    }
    if invalid then None
    else
      edges
        .find(edge => edge.from == from && edge.importLine.offset == offset)
        .flatMap(edge =>
          edge.target.filter(id =>
            files.exists(file =>
              file.source.id == id && file.namespace == edge.namespace
            )
          )
        )
