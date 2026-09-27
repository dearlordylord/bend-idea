package com.dearlordylord.bend.idea.analysis.api

import com.dearlordylord.bend.idea.analysis.model.{
  BendCheckResult,
  BendCheckedSource,
  BendGoal
}

final case class BendProofEditPreview(
    originalCheck: BendCheckResult,
    source: BendCheckedSource,
    goal: BendGoal,
    replacement: String,
    candidateCheck: BendCheckResult,
    completeProof: Boolean
)

enum BendProofEditPreviewOutcome:
  case Validated(preview: BendProofEditPreview)
  case Rejected(reason: String)

/** A whole-root compiler probe after replacing one current named hole with a
  * context binder. Rejection may have causes other than resource use, so the UI
  * must not relabel it as consumption.
  */
enum BendResourceCandidateStatus:
  case AcceptedComplete(
      reliance: com.dearlordylord.bend.idea.analysis.model.BendReliance
  )
  case AcceptedUntilTodo
  case Rejected(details: String)
  case NotTypeCompatible
  case NotChecked

final case class BendResourceBinding(
    name: String,
    quantity: String,
    status: BendResourceCandidateStatus
)

final case class BendResourceReport(
    originalCheck: BendCheckResult,
    goal: BendGoal,
    bindings: List[BendResourceBinding],
    capped: Boolean
)

enum BendResourceReportOutcome:
  case Available(report: BendResourceReport)
  case Unavailable(reason: String)

/** Validates an immutable candidate root snapshot before a feature offers an
  * undoable source edit. Implementations never mutate the original document.
  */
trait BendProofEditValidator:
  def previewReflexivity(
      check: BendCheckResult,
      goal: BendGoal
  )(completed: BendProofEditPreviewOutcome => Unit): Unit

  /** Serially probes a bounded set of compiler-context binders on derived
    * complete-root snapshots. No original document is mutated.
    */
  def probeGoalResources(
      check: BendCheckResult,
      goal: BendGoal
  )(completed: BendResourceReportOutcome => Unit): Unit
