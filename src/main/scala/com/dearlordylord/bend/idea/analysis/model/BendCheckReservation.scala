package com.dearlordylord.bend.idea.analysis.model

import com.dearlordylord.bend.idea.model.FileId

/** Identifies the exact policy-issued worker reservation associated with a capture. */
enum BendCheckOrigin:
  case Explicit, Background

final case class BendCheckReservation(root: FileId, generation: Long, origin: BendCheckOrigin,
    backgroundToken: Option[Long])
