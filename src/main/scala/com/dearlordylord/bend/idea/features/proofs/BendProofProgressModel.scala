package com.dearlordylord.bend.idea.features.proofs

import com.dearlordylord.bend.idea.analysis.model.{
  BendCheckOutcome,
  BendCheckResult,
  BendCheckingStatus,
  BendCompleteness,
  BendReliance
}
import com.dearlordylord.bend.idea.model.FileId

enum BendProofInventoryKind:
  case Law, CandidateFill, Hole

final case class BendProofInventoryEntry(
    kind: BendProofInventoryKind,
    name: String,
    path: String,
    offset: Int,
    sourceId: FileId,
    sourceRevision: Long,
    loadingConfigurationRevision: Long
):
  def label: String = s"${kind.toString}: $name — $path"

final case class BendProofProgressSnapshot(
    rootPath: String,
    checkedStatus: String,
    entries: List[BendProofInventoryEntry]
)

object BendProofProgressModel:
  /** Source inventory remains independent from the compiler's root verdict. */
  def checkedStatus(
      status: BendCheckingStatus,
      result: Option[BendCheckResult]
  ): String = if status == BendCheckingStatus.Checking then "Checking"
  else
    val verdict = result match
      case Some(value) if !value.fresh => "Check stale"
      case Some(value)                 =>
        value.outcome match
          case BendCheckOutcome.Success =>
            value.completeness match
              case BendCompleteness.Complete   => "Compiler success: complete"
              case BendCompleteness.Incomplete => "Compiler result: incomplete"
              case BendCompleteness.Unknown    =>
                "Compiler result: completeness unknown"
          case BendCheckOutcome.Failed
              if value.completeness == BendCompleteness.Incomplete =>
            "Compiler result: incomplete"
          case BendCheckOutcome.Failed      => "Compiler check failed"
          case BendCheckOutcome.Unavailable => "Checker unavailable"
          case BendCheckOutcome.TimedOut    => "Compiler check timed out"
      case None => BendCheckingStatus.label(status)
    val reliance = result.map(_.reliance) match
      case Some(BendReliance.UnsafeOrForeign) => "; unsafe or foreign reliance"
      case Some(BendReliance.Unknown)         => "; reliance unknown"
      case _                                  => ""
    verdict + reliance

  def filtered(
      entries: List[BendProofInventoryEntry],
      query: String
  ): List[BendProofInventoryEntry] =
    val needle = query.trim.toLowerCase(java.util.Locale.ROOT)
    if needle.isEmpty then entries
    else
      entries.filter(
        _.label.toLowerCase(java.util.Locale.ROOT).contains(needle)
      )
