package com.dearlordylord.bend.idea.analysis.model

import com.dearlordylord.bend.idea.model.FileId

/** Compiler display data associated with one exact captured source range. */
final case class BendGoalContext(
    name: String,
    quantity: String,
    typeText: String
)

final case class BendGoal(
    source: FileId,
    range: BendTextRange,
    holeName: String,
    expectedType: String,
    context: List[BendGoalContext],
    compatibleBindings: Option[List[String]] = None
)
