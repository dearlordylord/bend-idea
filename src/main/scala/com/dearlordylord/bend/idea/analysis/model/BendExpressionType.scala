package com.dearlordylord.bend.idea.analysis.model

import com.dearlordylord.bend.idea.model.FileId

/** A compiler-derived type for one exactly mapped checked expression. */
final case class BendExpressionType(
    source: FileId,
    range: BendTextRange,
    typeText: String,
    compilerSourceDigest: String,
    compilerRange: BendTextRange
)
