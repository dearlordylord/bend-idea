package com.dearlordylord.bend.idea.analysis.api

import com.dearlordylord.bend.idea.analysis.model.{BendCheckResult, BendGoal}
import com.dearlordylord.bend.idea.model.FileId

enum BendGoalAvailability:
  case Available(goal: BendGoal)
  case Unavailable(reason: String)

/** Selects only a compiler goal tied to this exact current source version. */
object BendGoalQuery:
  def at(
      result: BendCheckResult,
      source: FileId,
      text: String,
      revision: Long,
      offset: Int
  ): BendGoalAvailability =
    val current = result.sources
      .find(_.id == source)
      .exists(captured =>
        captured.text == text && captured.revision == revision
      )
    if !result.fresh || !current then
      BendGoalAvailability.Unavailable(
        "Bend source changed; check the root again"
      )
    else
      result.goal match
        case Some(goal)
            if goal.source == source && offset >= goal.range.start &&
              offset < goal.range.end =>
          BendGoalAvailability.Available(goal)
        case _ =>
          BendGoalAvailability.Unavailable(
            "Goal unavailable here. This compiler pairing supports only its first reachable named hole."
          )
