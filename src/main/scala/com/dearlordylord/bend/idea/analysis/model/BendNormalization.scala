package com.dearlordylord.bend.idea.analysis.model

import com.dearlordylord.bend.idea.model.FileId

/** Display-only result for one closed, exactly mapped checked expression. */
final case class BendNormalization(
    source: FileId,
    range: BendTextRange,
    text: String
)

final case class BendNormalizationRequest(source: FileId, offset: Int)
