package com.dearlordylord.bend.idea.analysis.api

import com.dearlordylord.bend.idea.analysis.model.{
  BendCheckOutcome,
  BendCheckResult,
  BendExpressionType
}
import com.dearlordylord.bend.idea.model.FileId

/** Selects a compiler expression fact only for the exact captured source. */
object BendExpressionTypeQuery:
  def at(
      result: BendCheckResult,
      source: FileId,
      text: String,
      revision: Long,
      offset: Int
  ): Option[BendExpressionType] =
    if !result.fresh || result.outcome != BendCheckOutcome.Success ||
      !result.sources.exists(captured =>
        captured.id == source && captured.revision == revision &&
          captured.text == text
      )
    then None
    else
      result.expressionTypes
        .filter(entry =>
          entry.source == source && entry.range.start <= offset &&
            offset < entry.range.end
        )
        .sortBy(entry => entry.range.end - entry.range.start)
        .headOption
