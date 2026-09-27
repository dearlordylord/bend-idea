package com.dearlordylord.bend.idea.adapters.structured

import com.dearlordylord.bend.idea.analysis.model.{
  BendExpressionType,
  BendNormalization
}
import java.nio.file.Path

/** Requests display-only normalization in a killable helper process. */
object BendStructuredNormalization:
  def closedExpression(
      executable: String,
      directory: Path,
      root: Path,
      environment: Map[String, String],
      expression: BendExpressionType,
      canceled: () => Boolean
  ): Option[BendNormalization] =
    import BendStructuredProtocol.*
    if !supports(
        executable,
        directory,
        environment,
        "normalize-closed-expression",
        canceled
      ) || canceled()
    then None
    else
      request(
        executable,
        directory,
        environment,
        "--idea-structured-normalize",
        Some(root),
        5000L,
        canceled,
        List(
          expression.compilerSourceDigest,
          expression.compilerRange.start.toString,
          expression.compilerRange.end.toString
        )
      ).filter(value =>
        string(value, "kind").contains("normal") &&
          string(value, "digest").contains(
            expression.compilerSourceDigest
          ) && integer(value, "start").contains(
            expression.compilerRange.start
          ) && integer(value, "end").contains(
            expression.compilerRange.end
          )
      ).flatMap(value =>
        string(value, "text")
          .filter(text => text.nonEmpty && text.length <= 2048)
          .map(text =>
            BendNormalization(expression.source, expression.range, text)
          )
      )
