package com.dearlordylord.bend.idea.analysis.ports

import com.dearlordylord.bend.idea.analysis.model.{BendCheckResult, BendCheckSnapshot}

/** Check-only operations cannot request run or build modes. */
trait BendCheckBackend:
  def check(snapshot: BendCheckSnapshot, canceled: () => Boolean = () => false): BendCheckResult
