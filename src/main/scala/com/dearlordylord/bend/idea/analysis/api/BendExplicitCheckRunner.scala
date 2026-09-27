package com.dearlordylord.bend.idea.analysis.api

import com.dearlordylord.bend.idea.analysis.model.{
  BendCheckResult,
  BendNormalizationRequest
}
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

  /** Performs the same complete root check and also requests an independently
    * negotiated goal for the first reachable named hole.
    */
  def checkGoal(
      path: String,
      taskTitle: String
  )(completed: BendExplicitCheckOutcome => Unit): Unit

  /** Rechecks the complete root and requests normalization at one captured
    * source location if a compatible helper supports it.
    */
  def checkNormalization(
      path: String,
      request: BendNormalizationRequest,
      taskTitle: String
  )(completed: BendExplicitCheckOutcome => Unit): Unit
