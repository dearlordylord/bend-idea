package com.dearlordylord.bend.idea.adapters.structured

import com.dearlordylord.bend.idea.analysis.model.{
  BendAnalysisKey,
  BendExpressionType,
  BendSourceMapping,
  BendTextRange
}
import java.nio.file.Path
import scala.jdk.CollectionConverters.*
import scala.util.Try

/** Reads checked expression types only from a compatible compiler helper.
  * Synthetic, rewritten and ambiguously owned spans remain unavailable.
  */
object BendStructuredTypes:
  def checked(
      executable: String,
      directory: Path,
      root: Path,
      environment: Map[String, String],
      mappings: List[BendSourceMapping],
      canceled: () => Boolean
  ): List[BendExpressionType] =
    import BendStructuredProtocol.*
    if !supports(
        executable,
        directory,
        environment,
        "expression-types-checked",
        canceled
      ) || canceled()
    then Nil
    else
      request(
        executable,
        directory,
        environment,
        "--idea-structured-types",
        Some(root),
        15000L,
        canceled
      ).filter(value => string(value, "kind").contains("types"))
        .flatMap { value =>
          for
            sourceNodes <- Try(value.getAsJsonArray("sources")).toOption
              .flatMap(Option(_))
              .filter(_.size() <= 128)
            entryNodes <- Try(value.getAsJsonArray("entries")).toOption
              .flatMap(Option(_))
              .filter(_.size() <= 256)
          yield
            val sources = sourceNodes
              .iterator()
              .asScala
              .toVector
              .map(node => Try(node.getAsString).toOption)
            val raw = entryNodes.iterator().asScala.toList.flatMap { node =>
              Try(node.getAsJsonObject).toOption.flatMap { entry =>
                for
                  span <- objectField(entry, "span")
                  index <- integer(span, "sourceIndex")
                  source <- sources.lift(index).flatten
                  start <- integer(span, "start")
                  end <- integer(span, "end")
                  typeText <- string(entry, "type")
                    .filter(text => text.nonEmpty && text.length <= 256)
                  if start >= 0 && end > start && end <= source.length
                  matches = mappings.filter(_.compilerText == source)
                  if matches.size == 1
                  mapping = matches.head
                  range <- mapping.compilerToOriginalRange(start, end)
                yield BendExpressionType(
                  mapping.source,
                  range,
                  typeText,
                  BendAnalysisKey.sourceDigest(source),
                  BendTextRange(start, end)
                )
              }
            }
            // Duplicate compiler spans with conflicting facts are unavailable.
            raw
              .groupBy(entry => (entry.source, entry.range))
              .values
              .flatMap(entries =>
                Option.when(entries.map(_.typeText).distinct.size == 1)(
                  entries.head
                )
              )
              .toList
        }
        .getOrElse(Nil)
