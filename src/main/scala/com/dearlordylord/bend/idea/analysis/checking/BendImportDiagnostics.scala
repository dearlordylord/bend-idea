package com.dearlordylord.bend.idea.analysis.checking

import com.dearlordylord.bend.idea.analysis.model.*
import com.dearlordylord.bend.idea.model.FileId
import com.dearlordylord.bend.idea.workspace.api.BendImportLines

/** Projects actual compiler failures through the captured loading graph. It
  * adds context; it never invents a compiler verdict or consults live files.
  */
object BendImportDiagnostics:
  private[checking] val MaximumEdges = 8192
  private[checking] val MaximumCauses = 256
  private[checking] val MaximumProjections = 1024

  def project(
      snapshot: BendCheckSnapshot,
      result: BendCheckResult,
      canceled: () => Boolean = () => false
  ): BendCheckResult =
    if result.outcome != BendCheckOutcome.Failed || canceled() then result
    else
      snapshot.graph.fold(result) { graph =>
        // File bounds alone do not bound repeated imports or their fanout. These
        // are presentation limits; every original compiler diagnostic survives.
        if graph.edges.lengthCompare(MaximumEdges) > 0 ||
          graph.problems.lengthCompare(MaximumEdges) > 0 ||
          graph.files.lengthCompare(MaximumEdges) > 0
        then result.copy(importDiagnosticsTruncated = true)
        else
          def source(location: BendLocation): Option[FileId] = location match
            case BendLocation.SourceRange(id, _) => Some(id)
            case BendLocation.SourceLine(id, _)  => Some(id)
            case BendLocation.Line(_)            => Some(result.key.root)
            case BendLocation.RootOnly           => None

          val validEdges = graph.validImportEdges
          val sources =
            graph.files.map(file => file.source.id -> file.source).toMap
          val importers = validEdges.groupMap(_._2)(_._1.from)
          def affectedImporters(target: FileId): Set[FileId] =
            @scala.annotation.tailrec
            def loop(frontier: Set[FileId], seen: Set[FileId]): Set[FileId] =
              if frontier.isEmpty || canceled() then seen
              else
                val next = frontier
                  .flatMap(id => importers.getOrElse(id, Nil))
                  .diff(seen)
                loop(next, seen ++ next)
            loop(Set(target), Set(target))

          val causes = result.diagnostics.iterator
            .filter(_.related.isEmpty)
            .take(MaximumCauses + 1)
            .toList
          val projected = causes
            .take(MaximumCauses)
            .iterator
            .takeWhile(_ => !canceled())
            .flatMap { cause =>
              source(cause.location).iterator.flatMap { target =>
                val affected = affectedImporters(target)
                validEdges.iterator.takeWhile(_ => !canceled()).flatMap {
                  case (edge, imported) =>
                    for
                      _ <- Option.when(
                        edge.from != target && affected.contains(imported)
                      )(())
                      importer <- sources.get(edge.from)
                      range <- BendImportLines.pathRange(
                        importer.text,
                        edge.importLine
                      )
                    yield BendDiagnostic(
                      s"Imported dependency failed: ${cause.message}",
                      BendLocation.SourceRange(
                        edge.from,
                        BendTextRange(range._1, range._2)
                      ),
                      Some(BendRelatedDiagnostic(cause.message, cause.location))
                    )
                }
              }
            }
            .distinct
            .take(MaximumProjections + 1)
            .toList
          if canceled() then result
          else
            result.copy(
              diagnostics = (result.diagnostics ++ projected.take(
                MaximumProjections
              )).distinct,
              importDiagnosticsTruncated = result.importDiagnosticsTruncated ||
                causes.size > MaximumCauses || projected.size > MaximumProjections
            )
      }
