package com.dearlordylord.bend.idea.analysis.model

import com.dearlordylord.bend.idea.model.FileId
import com.dearlordylord.bend.idea.toolchain.api.BendToolchainSelection
import com.dearlordylord.bend.idea.workspace.api.BendImportLines
import com.dearlordylord.bend.idea.workspace.model.{BendLoadedGraph, BendSourceRecord}

/** A root is distinct from a source file imported under that root. */
final case class BendCheckSnapshot(root: FileId, path: String, text: String,
    sourceRevision: Long, toolchain: BendToolchainSelection,
    inputFingerprint: String = "", graph: Option[BendLoadedGraph] = None,
    siblingLaws: Option[BendSourceRecord] = None):
  def selectedBasePath: Option[String] =
    val importsBase = graph.fold(
      BendImportLines.parse(text).exists(_.spelling == "Base"))(
        _.files.exists(_.source.imports.exists(_.spelling == "Base")))
    Option.when(importsBase)(toolchain.baseSource)

/** Snapshot identity retained independently of the backend's derived input fingerprint. */
final case class BendCheckSnapshotProvenance(graphFingerprint: String, baseSource: String)

object BendCheckSnapshotProvenance:
  def from(snapshot: BendCheckSnapshot): BendCheckSnapshotProvenance =
    BendCheckSnapshotProvenance(snapshot.inputFingerprint, snapshot.toolchain.baseSource)

final case class BendAnalysisKey(root: FileId, sourceRevision: Long, sourceFingerprint: String,
    configurationRevision: Long, executable: String, inputFingerprint: String,
    snapshotProvenance: BendCheckSnapshotProvenance,
    externalStamp: String = "", basePath: Option[String] = None)

object BendAnalysisKey:
  def sourceDigest(text: String): String =
    java.security.MessageDigest.getInstance("SHA-256")
      .digest(text.getBytes("UTF-8"))
      .map(b => f"${b & 0xff}%02x").mkString

  def from(snapshot: BendCheckSnapshot): BendAnalysisKey =
    BendAnalysisKey(snapshot.root, snapshot.sourceRevision, sourceDigest(snapshot.text),
      snapshot.toolchain.configurationRevision, snapshot.toolchain.executable,
      snapshot.inputFingerprint, BendCheckSnapshotProvenance.from(snapshot))

enum BendCheckOutcome:
  case Success, Failed, Unavailable, TimedOut

enum BendCompleteness:
  case Complete, Incomplete, Unknown

enum BendReliance:
  case None, UnsafeOrForeign, Unknown

enum BendLocation:
  case RootOnly
  case Line(number: Int)
  case SourceLine(source: FileId, number: Int)

/** Captured source identity and revision for editor projection and later structured spans. */
final case class BendCheckedSource(id: FileId, path: String, text: String, revision: Long,
    namespace: String)

/** A rewritten import has no exact source span. All other text keeps its line. */
final case class BendRewrittenRange(originalStart: Int, originalEnd: Int,
    copiedStart: Int, copiedEnd: Int)

final case class BendSourceMapping(source: FileId, originalPath: String, copiedPath: String,
    namespace: String, originalText: String, copiedText: String,
    rewrittenLines: Set[Int], rewrites: List[BendRewrittenRange],
    compilerText: String, blankedImportRanges: List[(Int, Int)]):
  def originalLine(line: Int): Option[Int] =
    Option.when(line >= 0 && line < originalText.split("\n", -1).length)(line)

  /** Exact copied-to-original offset outside replaced import paths. */
  def originalOffset(copiedOffset: Int): Option[Int] =
    if copiedOffset < 0 || copiedOffset > copiedText.length ||
        rewrites.exists(r => copiedOffset >= r.copiedStart && copiedOffset < r.copiedEnd) then None
    else
      val shift = rewrites.filter(_.copiedEnd <= copiedOffset)
        .map(r => (r.copiedEnd - r.copiedStart) - (r.originalEnd - r.originalStart)).sum
      Some(copiedOffset - shift)

  /** Bend blanks complete import lines before parsing; those offsets are synthetic. */
  def copiedOffset(compilerOffset: Int): Option[Int] =
    if compilerOffset < 0 || compilerOffset > compilerText.length then None
    else
      val before = compilerText.take(compilerOffset)
      val line = before.count(_ == '\n')
      val column = before.length - before.lastIndexOf('\n') - 1
      val copyLines = copiedText.split("\n", -1)
      if line >= copyLines.length ||
          blankedImportRanges.exists { case (start, _) =>
            copiedText.take(start).count(_ == '\n') == line
          } then None
      else Some(copyLines.take(line).map(_.length + 1).sum + column)

final case class BendDiagnostic(message: String, location: BendLocation)

final case class BendCheckResult(key: BendAnalysisKey, outcome: BendCheckOutcome,
    completeness: BendCompleteness, reliance: BendReliance,
    diagnostics: List[BendDiagnostic], details: String, fresh: Boolean = true,
    sources: List[BendCheckedSource] = Nil,
    mappings: List[BendSourceMapping] = Nil):
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
