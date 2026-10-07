package com.dearlordylord.bend.idea.analysis.model

import com.dearlordylord.bend.idea.model.FileId

enum BendLintSeverity:
  case Error, Warning, Information, Hint

enum BendLintOutcome:
  case Completed, CheckRejected, Unavailable, TimedOut, Canceled

final case class BendLintFinding(
    code: String,
    severity: BendLintSeverity,
    message: String,
    source: FileId,
    range: BendTextRange
)

/** Lint never changes completeness, reliance or the compiler verdict. */
final case class BendLintResult(
    outcome: BendLintOutcome,
    findings: List[BendLintFinding],
    details: String,
    unmapped: Int = 0,
    observedFiles: List[(String, String)] = Nil
)
