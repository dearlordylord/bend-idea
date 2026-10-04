package com.dearlordylord.bend.idea.adapters.structured

import com.dearlordylord.bend.idea.analysis.model.{
  BendGoal,
  BendGoalContext,
  BendSourceMapping
}
import com.google.gson.JsonObject
import java.nio.file.Path
import scala.jdk.CollectionConverters.*
import scala.util.Try

/** The pinned helper currently supports only the first compiler-reachable named
  * hole. TODO holes and later holes are explicitly unavailable.
  */
object BendStructuredGoal:
  def firstGoal(
      executable: String,
      directory: Path,
      root: Path,
      environment: Map[String, String],
      cliMessage: String,
      mappings: List[BendSourceMapping],
      canceled: () => Boolean
  ): Option[BendGoal] =
    import BendStructuredProtocol.*
    if !supports(
        executable,
        directory,
        environment,
        "goal-first-named-hole",
        canceled
      ) || canceled()
    then None
    else
      request(
        executable,
        directory,
        environment,
        "--idea-structured-goal",
        Some(root),
        15000L,
        canceled
      ).filter(value =>
        string(value, "kind").contains("goal") &&
          matchesDiagnostic(string(value, "message"), cliMessage)
      ).flatMap { value =>
        for
          hole <- string(value, "hole").filter(_.nonEmpty)
          expected <- string(value, "expected").filter(_.nonEmpty)
          context <- readContext(value)
          span <- objectField(value, "span")
          source <- string(span, "source")
          start <- integer(span, "start")
          end <- integer(span, "end")
          if start >= 0 && end > start && end <= source.length
          matches = mappings.filter(_.compilerText == source)
          if matches.size == 1
          mapping = matches.head
          mapped <- mapping.compilerToOriginalRange(start, end)
          if mapping.originalText.substring(
            mapped.start,
            mapped.end
          ) == s"?$hole"
        yield BendGoal(
          mapping.source,
          mapped,
          hole,
          expected,
          context,
          if context.map(_.name).distinct.size != context.size then None
          else
            compatibleBindings(
              executable,
              directory,
              root,
              environment,
              cliMessage,
              source,
              start,
              end,
              hole,
              context.map(_.name).toSet,
              canceled
            )
        )
      }

  private def compatibleBindings(
      executable: String,
      directory: Path,
      root: Path,
      environment: Map[String, String],
      cliMessage: String,
      source: String,
      start: Int,
      end: Int,
      hole: String,
      contextNames: Set[String],
      canceled: () => Boolean
  ): Option[List[String]] =
    import BendStructuredProtocol.*
    if !supports(
        executable,
        directory,
        environment,
        "goal-binder-comparison",
        canceled
      ) || canceled()
    then None
    else
      request(
        executable,
        directory,
        environment,
        "--idea-structured-compare",
        Some(root),
        3000L,
        canceled
      ).filter(value =>
        string(value, "kind").contains("comparison") &&
          string(value, "hole").contains(hole) &&
          matchesDiagnostic(string(value, "message"), cliMessage)
      ).flatMap { value =>
        for
          span <- objectField(value, "span")
          if string(span, "source").contains(source) &&
            integer(span, "start").contains(start) &&
            integer(span, "end").contains(end)
          array <- Try(value.getAsJsonArray("compatible")).toOption
            .flatMap(Option(_))
            .filter(_.size() <= 32)
          names = array
            .iterator()
            .asScala
            .toList
            .map(item => Try(item.getAsString).toOption.filter(contextNames))
          if names.forall(_.nonEmpty)
        yield names.flatten.distinct
      }

  private def readContext(value: JsonObject): Option[List[BendGoalContext]] =
    import BendStructuredProtocol.*
    Try(value.getAsJsonArray("context")).toOption
      .flatMap(Option(_))
      .filter(_.size() <= 128)
      .flatMap { array =>
        val values = array.iterator().asScala.toList.map { item =>
          Try(item.getAsJsonObject).toOption.flatMap { binding =>
            for
              name <- string(binding, "name").filter(_.nonEmpty)
              quantity <- string(binding, "quantity")
              typeText <- string(binding, "type").filter(_.nonEmpty)
            yield BendGoalContext(name, quantity, typeText)
          }
        }
        Option.when(values.forall(_.nonEmpty))(values.flatten)
      }
