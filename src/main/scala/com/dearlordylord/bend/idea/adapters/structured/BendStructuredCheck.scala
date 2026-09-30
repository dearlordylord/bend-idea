package com.dearlordylord.bend.idea.adapters.structured

import com.dearlordylord.bend.idea.adapters.process.{
  BendBoundedProcess,
  BendProcessOutcome
}
import com.dearlordylord.bend.idea.analysis.model.*
import com.google.gson.{JsonObject, JsonParser}
import java.nio.file.Path
import scala.jdk.CollectionConverters.*
import scala.util.Try

/** Versioned check transport; optional semantic operations remain independent.
  */
object BendStructuredCheck:
  enum Negotiation:
    case Supported(identity: String)
    case Legacy
    case Unavailable(details: String)
    case TimedOut

  final case class Verdict(
      outcome: BendCheckOutcome,
      completeness: BendCompleteness,
      reliance: BendReliance,
      details: String,
      diagnostics: List[BendDiagnostic],
      incompleteKind: Option[BendIncompleteKind]
  )

  private def string(value: JsonObject, name: String): Option[String] =
    Option(value.get(name))
      .filter(node => node.isJsonPrimitive && node.getAsJsonPrimitive.isString)
      .map(_.getAsString)

  private def exact(
      value: JsonObject,
      name: String,
      expected: String
  ): Boolean =
    Option(value.get(name)).exists(_.toString == expected)

  private def json(output: String): Option[JsonObject] =
    Try(JsonParser.parseString(output).getAsJsonObject).toOption

  def negotiate(
      executable: String,
      directory: Path,
      environment: Map[String, String],
      canceled: () => Boolean
  ): Negotiation =
    BendBoundedProcess.run(
      List(executable, "--idea-check-capabilities"),
      directory,
      environment,
      timeoutMillis = 3000L,
      maxOutputBytes = 262144,
      canceled = canceled
    ) match
      case BendProcessOutcome.Exited(code, output) =>
        if !output.trim.startsWith("{") && !output.trim.startsWith("[") then
          Negotiation.Legacy
        else
          json(output) match
            case Some(value)
                if code == 0 && exact(value, "checkProtocol", "1") &&
                  exact(value, "checkOnly", "true") && string(value, "compiler")
                    .exists(_.nonEmpty) &&
                  string(value, "locations").contains(
                    "compiler-source-utf16"
                  ) &&
                  Try(value.getAsJsonArray("operations")).toOption
                    .flatMap(Option(_))
                    .exists(array =>
                      array
                        .iterator()
                        .asScala
                        .exists(item => item.toString == "\"check\"")
                    ) =>
              Negotiation.Supported(output)
            case _ =>
              Negotiation.Unavailable(
                "Incompatible Bend check protocol; no source was checked."
              )
      case BendProcessOutcome.TimedOut(_) => Negotiation.TimedOut
      case _                              =>
        Negotiation.Unavailable(
          "Bend check protocol probe failed or was canceled; no source was checked."
        )

  def check(
      executable: String,
      directory: Path,
      root: Path,
      environment: Map[String, String],
      mappings: List[BendSourceMapping],
      canceled: () => Boolean
  ): Verdict =
    val process = BendBoundedProcess.run(
      List(executable, "--idea-check", root.toString),
      directory,
      environment,
      maxOutputBytes = 262144,
      canceled = canceled
    )
    process match
      case BendProcessOutcome.Exited(0, output) =>
        decode(output, mappings).getOrElse(
          unavailable("Invalid Bend structured check response.")
        )
      case BendProcessOutcome.TimedOut(_) =>
        unavailable("Bend structured check timed out.").copy(outcome =
          BendCheckOutcome.TimedOut
        )
      case _ =>
        unavailable(
          "Bend structured check failed, exceeded its output limit or was canceled."
        )

  private def unavailable(details: String): Verdict =
    Verdict(
      BendCheckOutcome.Unavailable,
      BendCompleteness.Unknown,
      BendReliance.Unknown,
      details,
      Nil,
      None
    )

  private def decode(
      output: String,
      mappings: List[BendSourceMapping]
  ): Option[Verdict] =
    Try {
      json(output).flatMap { value =>
        for
          _ <- Option.when(exact(value, "checkProtocol", "1"))(())
          outcome <- string(value, "outcome").flatMap {
            case "success"     => Some(BendCheckOutcome.Success)
            case "failed"      => Some(BendCheckOutcome.Failed)
            case "unavailable" => Some(BendCheckOutcome.Unavailable)
            case _             => None
          }
          completeness <- string(value, "completeness").flatMap {
            case "complete"   => Some(BendCompleteness.Complete)
            case "incomplete" => Some(BendCompleteness.Incomplete)
            case "unknown"    => Some(BendCompleteness.Unknown)
            case _            => None
          }
          reliance <- string(value, "reliance").flatMap {
            case "none"              => Some(BendReliance.None)
            case "unsafe-or-foreign" => Some(BendReliance.UnsafeOrForeign)
            case "unknown"           => Some(BendReliance.Unknown)
            case _                   => None
          }
          details <- string(value, "details")
          entries <- Option(value.getAsJsonArray("diagnostics"))
          if entries.size <= 256
          diagnostics = entries.iterator().asScala.toList.map { entry =>
            val item = entry.getAsJsonObject
            val message = string(item, "message").getOrElse(
              throw new IllegalArgumentException("Missing diagnostic message")
            )
            val location = Option(item.getAsJsonObject("span"))
              .flatMap { span =>
                for
                  source <- string(span, "source")
                  start <- offset(span, "start")
                  end <- offset(span, "end")
                  if start >= 0 && end > start && end <= source.length
                  matches = mappings.filter(_.compilerText == source)
                  if matches.size == 1
                  range <- matches.head.compilerToOriginalRange(start, end)
                yield BendLocation.SourceRange(matches.head.source, range)
              }
              .getOrElse(BendLocation.RootOnly)
            BendDiagnostic(message, location)
          }
          if !value.has("incompleteKind") || string(value, "incompleteKind")
            .exists(Set("todo", "named-hole"))
          kind = string(value, "incompleteKind").flatMap {
            case "todo"       => Some(BendIncompleteKind.TodoHoles)
            case "named-hole" => Some(BendIncompleteKind.NamedHole)
            case _            => None
          }
          if outcome != BendCheckOutcome.Success || (completeness == BendCompleteness.Complete && reliance != BendReliance.Unknown && diagnostics.isEmpty && kind.isEmpty)
          if completeness != BendCompleteness.Incomplete || (outcome == BendCheckOutcome.Failed && kind.nonEmpty)
          if outcome != BendCheckOutcome.Failed || diagnostics.nonEmpty
          if kind.isEmpty || completeness == BendCompleteness.Incomplete
          if outcome != BendCheckOutcome.Unavailable || (completeness == BendCompleteness.Unknown && reliance == BendReliance.Unknown && kind.isEmpty)
        yield Verdict(
          outcome,
          completeness,
          reliance,
          details,
          diagnostics,
          kind
        )
      }
    }.toOption.flatten

  private def offset(value: JsonObject, name: String): Option[Int] =
    Option(value.get(name))
      .filter(node => node.isJsonPrimitive && node.getAsJsonPrimitive.isNumber)
      .flatMap(node => Try(node.getAsBigDecimal.intValueExact()).toOption)
