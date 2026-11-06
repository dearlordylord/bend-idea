package com.dearlordylord.bend.idea.adapters.structured

import com.dearlordylord.bend.idea.analysis.model.*
import com.google.gson.{JsonObject, JsonParser}
import java.nio.file.Path
import scala.jdk.CollectionConverters.*
import scala.util.Try

/** bend-lint reports positions in the materialized file, not compiler-blanked
  * input. Reject malformed replies; retain an explicit count of unmapped sites.
  */
object BendLintProtocol:
  def decode(
      output: String,
      exit: Int,
      mappings: List[BendSourceMapping]
  ): Option[BendLintResult] =
    Try(decodeReply(output, exit, mappings)).toOption

  private def decodeReply(
      output: String,
      exit: Int,
      mappings: List[BendSourceMapping]
  ): BendLintResult =
    val root = JsonParser.parseString(output).getAsJsonObject
    val okNode = root.get("ok")
    require(okNode.isJsonPrimitive && okNode.getAsJsonPrimitive.isBoolean)
    val ok = okNode.getAsBoolean
    if root.has("errors") then
      val errors = root.getAsJsonArray("errors")
      require(!ok && exit == 2 || !ok && exit == 1)
      require(errors.size() > 0 && errors.size() <= 256)
      return BendLintResult(
        BendLintOutcome.CheckRejected,
        Nil,
        errors
          .iterator()
          .asScala
          .map(node => text(node.getAsJsonObject, "message", 4096))
          .mkString("; ")
      )
    require(exit == (if ok then 0 else 1))
    val nodes = root.getAsJsonArray("findings")
    require(nodes.size() <= 1024)
    def canonical(path: String): String =
      val value = Path.of(path).toAbsolutePath.normalize()
      Try(value.toRealPath()).getOrElse(value).toString
    val sources = mappings.map(mapping =>
      (
        canonical(mapping.copiedPath),
        mapping,
        Vector(0) ++ mapping.copiedText.indices
          .filter(mapping.copiedText(_) == '\n')
          .map(_ + 1)
      )
    )
    val decoded = nodes.iterator().asScala.toList.map { node =>
      val value = node.getAsJsonObject
      val code = text(value, "code", 128)
      val message = text(value, "message", 4096)
      val severity = text(value, "severity", 32) match
        case "error"       => BendLintSeverity.Error
        case "warning"     => BendLintSeverity.Warning
        case "information" => BendLintSeverity.Information
        case "hint"        => BendLintSeverity.Hint
        case _ => throw new IllegalArgumentException("Unknown lint severity")
      val located = if !value.has("path") || !value.has("range") then None
      else
        val path = canonical(text(value, "path", 16384))
        val candidates = sources.filter(_._1 == path)
        val range = value.getAsJsonObject("range")
        for
          entry <- Option.when(candidates.size == 1)(candidates.head)
          mapping = entry._2
          start <- offset(
            mapping.copiedText,
            entry._3,
            range.getAsJsonObject("start")
          )
          end <- offset(
            mapping.copiedText,
            entry._3,
            range.getAsJsonObject("end")
          )
          if end > start
          original <- mapping.copiedToOriginalRange(start, end)
        yield BendLintFinding(
          code,
          severity,
          message,
          mapping.source,
          original,
          Option
            .when(value.has("docs"))(text(value, "docs", 4096))
            .filter(link =>
              link.startsWith("https://github.com/MattCozendey/bend-lint/")
            )
        )
      located
    }
    val skipped = Option(root.getAsJsonArray("skipped")).toList.flatMap {
      nodes =>
        require(nodes.size() <= 1024)
        nodes
          .iterator()
          .asScala
          .map { node =>
            val value = node.getAsJsonObject
            val path = canonical(text(value, "path", 16384))
            val reason = text(value, "reason", 32)
            require(Set("unloaded", "unchecked", "unentered")(reason))
            val matched = sources.filter(_._1 == path)
            val rules = Option(value.getAsJsonArray("rules")).toList.flatMap {
              rules =>
                require(rules.size() <= 256)
                rules
                  .iterator()
                  .asScala
                  .map { rule =>
                    require(
                      rule.isJsonPrimitive && rule.getAsJsonPrimitive.isString
                    )
                    val id = rule.getAsString
                    require(id.nonEmpty && id.length <= 128)
                    id
                  }
                  .toList
            }
            BendLintSkipped(
              Option.when(matched.size == 1)(matched.head._2.source),
              reason,
              text(value, "message", 4096),
              rules,
              Option.when(value.has("entry"))(text(value, "entry", 16384))
            )
          }
          .toList
    }
    BendLintResult(
      if skipped.nonEmpty then BendLintOutcome.Partial
      else BendLintOutcome.Completed,
      decoded.flatten.distinct,
      s"bend-lint 0.3.0 (Bend 2.0.39): ${nodes.size()} finding(s), ${skipped.size} skipped file(s).",
      decoded.count(_.isEmpty),
      skipped = skipped
    )

  private def text(value: JsonObject, name: String, max: Int): String =
    val node = value.get(name)
    require(node.isJsonPrimitive && node.getAsJsonPrimitive.isString)
    val result = node.getAsString
    require(result.nonEmpty && result.length <= max)
    result

  private def number(value: JsonObject, name: String): Int =
    val node = value.get(name)
    require(node.isJsonPrimitive && node.getAsJsonPrimitive.isNumber)
    val result = node.getAsBigDecimal.intValueExact()
    require(result >= 0)
    result

  private def offset(
      source: String,
      starts: Vector[Int],
      point: JsonObject
  ): Option[Int] =
    val line = number(point, "line")
    val column = number(point, "character")
    starts.lift(line).flatMap { start =>
      val newline = source.indexOf('\n', start)
      val rawEnd = if newline < 0 then source.length else newline
      val end = if rawEnd > start && source(rawEnd - 1) == '\r' then rawEnd - 1
      else rawEnd
      Option.when(column <= end - start)(start + column)
    }
