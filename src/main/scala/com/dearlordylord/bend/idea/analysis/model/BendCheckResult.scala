package com.dearlordylord.bend.idea.analysis.model

import com.dearlordylord.bend.idea.model.FileId
import com.dearlordylord.bend.idea.toolchain.api.BendToolchainSelection

/** A root is distinct from a source file imported under that root. */
final case class BendCheckSnapshot(root: FileId, path: String, text: String,
    sourceRevision: Long, toolchain: BendToolchainSelection,
    inputFingerprint: String = "")

final case class BendAnalysisKey(root: FileId, sourceRevision: Long, sourceFingerprint: String,
    configurationRevision: Long, executable: String, inputFingerprint: String,
    externalStamp: String = "", basePath: Option[String] = None)

object BendAnalysisKey:
  def sourceDigest(text: String): String =
    java.security.MessageDigest.getInstance("SHA-256")
      .digest(text.getBytes("UTF-8"))
      .map(b => f"${b & 0xff}%02x").mkString

  def from(snapshot: BendCheckSnapshot): BendAnalysisKey =
    BendAnalysisKey(snapshot.root, snapshot.sourceRevision, sourceDigest(snapshot.text),
      snapshot.toolchain.configurationRevision, snapshot.toolchain.executable,
      snapshot.inputFingerprint)

enum BendCheckOutcome:
  case Success, Failed, Unavailable, TimedOut

enum BendCompleteness:
  case Complete, Incomplete, Unknown

enum BendReliance:
  case None, UnsafeOrForeign, Unknown

enum BendLocation:
  case RootOnly
  case Line(number: Int)

final case class BendDiagnostic(message: String, location: BendLocation)

final case class BendCheckResult(key: BendAnalysisKey, outcome: BendCheckOutcome,
    completeness: BendCompleteness, reliance: BendReliance,
    diagnostics: List[BendDiagnostic], details: String, fresh: Boolean = true):
  def status: String =
    val verdict = outcome match
      case BendCheckOutcome.Success => completeness match
        case BendCompleteness.Complete => "Check succeeded"
        case BendCompleteness.Incomplete => "Check incomplete"
        case BendCompleteness.Unknown => "Check finished"
      case BendCheckOutcome.Failed =>
        if completeness == BendCompleteness.Incomplete then "Check incomplete" else "Check failed"
      case BendCheckOutcome.Unavailable => "Check unavailable"
      case BendCheckOutcome.TimedOut => "Check timed out"
    val relied = if reliance == BendReliance.UnsafeOrForeign then "; relies on unsafe or foreign code" else ""
    verdict + relied + (if fresh then "" else "; stale")
