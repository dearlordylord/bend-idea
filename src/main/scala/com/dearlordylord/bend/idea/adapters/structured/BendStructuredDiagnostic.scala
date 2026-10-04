package com.dearlordylord.bend.idea.adapters.structured

import com.dearlordylord.bend.idea.analysis.model.{
  BendDiagnostic,
  BendLocation,
  BendSourceMapping
}
import java.nio.file.Path

/** Optional first-error span. A malformed or incompatible helper leaves the
  * established CLI diagnostic intact.
  */
object BendStructuredDiagnostic:
  def firstError(
      executable: String,
      directory: Path,
      root: Path,
      environment: Map[String, String],
      cliMessage: String,
      mappings: List[BendSourceMapping],
      canceled: () => Boolean
  ): Option[BendDiagnostic] =
    import BendStructuredProtocol.*
    if !supports(executable, directory, environment, "diagnostic", canceled) ||
      canceled()
    then None
    else
      request(
        executable,
        directory,
        environment,
        "--idea-structured-diagnostic",
        Some(root),
        15000L,
        canceled
      ).filter(value =>
        string(value, "kind").contains("first-error") &&
          matchesDiagnostic(string(value, "message"), cliMessage)
      ).flatMap { value =>
        objectField(value, "span").flatMap { span =>
          for
            source <- string(span, "source")
            start <- integer(span, "start")
            end <- integer(span, "end")
            if start >= 0 && end > start && end <= source.length
            matches = mappings.filter(_.compilerText == source)
            if matches.size == 1
            mapped <- matches.head.compilerToOriginalRange(start, end)
          yield BendDiagnostic(
            cliMessage,
            BendLocation.SourceRange(matches.head.source, mapped)
          )
        }
      }
