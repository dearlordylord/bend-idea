package com.dearlordylord.bend.idea.analysis.api

import com.dearlordylord.bend.idea.analysis.model.BendCheckResult
import com.dearlordylord.bend.idea.model.FileId

/** Result of an explicit editor-triggered check request. */
enum BendExplicitCheckOutcome:
  case Published(result: BendCheckResult)
  case Rejected(root: FileId, reason: String)

/** Shared check-only entry point for current-file and selected-root actions.
  * Snapshot capture, graph loading and worker ownership stay in the adapter.
  */
trait BendExplicitCheckRunner:
  def check(
      path: String,
      taskTitle: String
  )(completed: BendExplicitCheckOutcome => Unit): Unit
