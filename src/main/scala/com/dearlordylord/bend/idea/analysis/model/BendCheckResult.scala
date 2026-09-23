package com.dearlordylord.bend.idea.analysis.model

import com.dearlordylord.bend.idea.model.FileId
import com.dearlordylord.bend.idea.toolchain.api.BendToolchainSelection
import com.dearlordylord.bend.idea.workspace.model.{BendLoadedGraph, BendSourceRecord}

/** A root is distinct from a source file imported under that root. */
final case class BendCheckSnapshot(root: FileId, path: String, text: String,
    sourceRevision: Long, toolchain: BendToolchainSelection,
    inputFingerprint: String = "", graph: Option[BendLoadedGraph] = None,
    siblingLaws: Option[BendSourceRecord] = None)

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
  case SourceLine(source: FileId, number: Int)

/** Captured source identity and revision for editor projection and later structured spans. */
final case class BendCheckedSource(id: FileId, path: String, text: String, revision: Long,
    namespace: String)

/** A rewritten import has no exact source span. All other text keeps its line. */
final case class BendRewrittenRange(originalStart: Int, originalEnd: Int,
    copiedStart: Int, copiedEnd: Int)

/** A source line removed by the compiler loader. The line separator, when present,
  * remains in compiler text to preserve following line numbers, but belongs to the
  * synthetic line and is not an attributable code unit.
  */
final case class BendBlankedRange(line: Int, copiedStart: Int, copiedEnd: Int,
    compilerStart: Int, compilerEnd: Int)

/** A half-open UTF-16 source range. */
final case class BendTextRange(start: Int, end: Int)

/** Maps one immutable source version through both CLI materialization steps.
  * Ranges are ordered half-open UTF-16 code-unit offsets, matching IntelliJ
  * documents and Java/Scala String indexes.
  */
final case class BendSourceMapping(source: FileId, revision: Long,
    originalPath: String, copiedPath: String, namespace: String,
    originalText: String, copiedText: String, rewrittenLines: Set[Int],
    rewrites: List[BendRewrittenRange], compilerText: String,
    blankedImportRanges: List[BendBlankedRange]):
  def originalLine(line: Int): Option[Int] =
    Option.when(line >= 0 && line < originalText.split("\n", -1).length)(line)

  private final case class EditRange(fromStart: Int, fromEnd: Int, toStart: Int, toEnd: Int)

  private def rewriteEdits: List[EditRange] = rewrites.map(r =>
    EditRange(r.originalStart, r.originalEnd, r.copiedStart, r.copiedEnd))

  private def blankingEdits: List[EditRange] = blankedImportRanges.map(r =>
    EditRange(r.copiedStart, r.copiedEnd, r.compilerStart, r.compilerEnd))

  private def inverse(edits: List[EditRange]): List[EditRange] = edits.map(edit =>
    EditRange(edit.toStart, edit.toEnd, edit.fromStart, edit.fromEnd))

  /** Validate that every code unit outside declared edits is actually preserved. */
  private def preservedGaps(from: String, to: String, edits: List[EditRange]): Boolean =
    var fromCursor = 0
    var toCursor = 0
    var valid = true
    edits.foreach { edit =>
      if valid then
        valid = edit.fromStart >= fromCursor && edit.fromStart <= edit.fromEnd &&
          edit.fromEnd <= from.length && edit.toStart >= toCursor &&
          edit.toStart <= edit.toEnd && edit.toEnd <= to.length &&
          edit.fromStart - fromCursor == edit.toStart - toCursor &&
          from.regionMatches(fromCursor, to, toCursor, edit.fromStart - fromCursor)
        fromCursor = edit.fromEnd
        toCursor = edit.toEnd
    }
    valid && from.length - fromCursor == to.length - toCursor &&
      from.regionMatches(fromCursor, to, toCursor, from.length - fromCursor)

  private def validRewrites: Boolean =
    preservedGaps(originalText, copiedText, rewriteEdits)

  private def validBlanks: Boolean =
    val lineRangesValid = blankedImportRanges.forall { range =>
      range.line >= 0 && range.copiedStart >= 0 && range.copiedStart < range.copiedEnd &&
        range.copiedEnd <= copiedText.length && range.compilerStart >= 0 &&
        range.compilerStart <= range.compilerEnd && range.compilerEnd <= compilerText.length &&
        (range.copiedStart == 0 || copiedText.charAt(range.copiedStart - 1) == '\n') &&
        (range.copiedEnd == copiedText.length || copiedText.charAt(range.copiedEnd - 1) == '\n') &&
        copiedText.take(range.copiedStart).count(_ == '\n') == range.line &&
        (range.compilerEnd - range.compilerStart == 0 ||
          (range.compilerEnd - range.compilerStart == 1 &&
            compilerText.charAt(range.compilerStart) == '\n' &&
            copiedText.charAt(range.copiedEnd - 1) == '\n'))
    }
    lineRangesValid && preservedGaps(copiedText, compilerText, blankingEdits)

  private def mapOffset(offset: Int, from: String, to: String,
      edits: List[EditRange], rejectEditStart: Boolean): Option[Int] =
    if offset < 0 || offset > from.length then None
    else if !preservedGaps(from, to, edits) then None
    else if edits.exists(edit =>
        (offset > edit.fromStart && offset < edit.fromEnd) ||
          (rejectEditStart && edit.fromStart < edit.fromEnd && offset == edit.fromStart)) then None
    else
      // Edit edges are explicit correspondences even when the replaced text
      // starts or ends with different code units (for example `/abs/path` ->
      // `./path`). Unchanged offsets still require matching adjacent text.
      val boundary = edits.find(_.fromEnd == offset).map(_.toEnd)
        .orElse(edits.find(_.fromStart == offset).map(_.toStart))
      boundary.orElse {
        val shift = edits.filter(_.fromEnd <= offset)
          .map(edit => (edit.toEnd - edit.toStart) - (edit.fromEnd - edit.fromStart)).sum
        val mapped = offset + shift
        Option.when(mapped >= 0 && mapped <= to.length &&
          (offset == from.length || mapped == to.length || from.charAt(offset) == to.charAt(mapped)))(mapped)
      }

  private def mapRange(start: Int, end: Int, from: String, to: String,
      edits: List[EditRange], rejectEditStart: Boolean): Option[BendTextRange] =
    if start < 0 || end < start || end > from.length then None
    else if start == end then mapOffset(start, from, to, edits, rejectEditStart)
      .map(offset => BendTextRange(offset, offset))
    else if !preservedGaps(from, to, edits) ||
        edits.exists(edit => edit.fromStart < end && start < edit.fromEnd) then None
    else for
      mappedStart <- mapOffset(start, from, to, edits, rejectEditStart)
      mappedEnd <- mapOffset(end, from, to, edits, rejectEditStart)
      if mappedEnd - mappedStart == end - start
      if from.regionMatches(start, to, mappedStart, end - start)
    yield BendTextRange(mappedStart, mappedEnd)

  /** Exact copied-to-original offset; rewrite edges remain valid boundaries. */
  def originalOffset(copiedOffset: Int): Option[Int] =
    if !validRewrites then None
    else mapOffset(copiedOffset, copiedText, originalText, inverse(rewriteEdits),
      rejectEditStart = false)

  /** Exact original-to-copied offset outside a rewritten import path. */
  def originalToCopiedOffset(originalOffset: Int): Option[Int] =
    if !validRewrites then None
    else mapOffset(originalOffset, originalText, copiedText, rewriteEdits,
      rejectEditStart = false)

  /** Exact compiler-input-to-copied offset; blanked line content and its retained
    * separator are synthetic. At an empty final blanked line, the shared EOF
    * boundary maps to the end of the removed source line.
    */
  def compilerToCopiedOffset(compilerOffset: Int): Option[Int] =
    if !validBlanks then None
    else mapOffset(compilerOffset, compilerText, copiedText, inverse(blankingEdits),
      rejectEditStart = true)

  /** Compatibility name for the original compiler-input-to-copied operation. */
  def copiedOffset(compilerOffset: Int): Option[Int] = compilerToCopiedOffset(compilerOffset)

  def compilerToCopiedRange(start: Int, end: Int): Option[BendTextRange] =
    if !validBlanks then None
    else mapRange(start, end, compilerText, copiedText, inverse(blankingEdits),
      rejectEditStart = true)

  /** Compatibility name for compiler-input-to-copied range mapping. */
  def copiedRange(start: Int, end: Int): Option[BendTextRange] =
    compilerToCopiedRange(start, end)

  def copiedToOriginalRange(start: Int, end: Int): Option[BendTextRange] =
    if !validRewrites then None
    else mapRange(start, end, copiedText, originalText, inverse(rewriteEdits),
      rejectEditStart = false)

  /** Exact copied-source-to-original-source range. */
  def originalRange(start: Int, end: Int): Option[BendTextRange] =
    copiedToOriginalRange(start, end)

  /** Compose compiler-input -> copied-source -> original-source for #41 adapters. */
  def compilerToOriginalOffset(compilerOffset: Int): Option[Int] =
    compilerToCopiedOffset(compilerOffset).flatMap(originalOffset)

  /** Compatibility name for compiler-input-to-original offset mapping. */
  def originalCompilerOffset(compilerOffset: Int): Option[Int] =
    compilerToOriginalOffset(compilerOffset)

  /** Exact structured diagnostic span mapping. Any rewritten or synthetic code unit
    * makes the full range unavailable, even when both endpoints happen to map.
    */
  def compilerToOriginalRange(start: Int, end: Int): Option[BendTextRange] =
    compilerToCopiedRange(start, end).flatMap(range =>
      copiedToOriginalRange(range.start, range.end))

  /** Compatibility name for compiler-input-to-original range mapping. */
  def originalCompilerRange(start: Int, end: Int): Option[BendTextRange] =
    compilerToOriginalRange(start, end)

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
