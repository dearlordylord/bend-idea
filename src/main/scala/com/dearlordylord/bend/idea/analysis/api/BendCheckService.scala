package com.dearlordylord.bend.idea.analysis.api

import com.dearlordylord.bend.idea.analysis.model.{BendCheckResult, BendCheckSnapshot}
import com.dearlordylord.bend.idea.model.FileId
import com.dearlordylord.bend.idea.analysis.model.BendCheckingStatus

/** Explicit root check and last published root result, consumed by the editor. */
trait BendCheckService:
  /** Reserve the project worker and observe later edits to this root document. */
  def begin(snapshot: BendCheckSnapshot,
      subscribe: (() => Unit) => (() => Unit), isCurrent: () => Boolean,
      background: Boolean = false): Boolean
  def cancel(root: FileId): Unit
  def configurationChanged(): Unit
  def check(snapshot: BendCheckSnapshot, canceled: () => Boolean): Option[BendCheckResult]
  def result(root: FileId): Option[BendCheckResult]
  /** Current root-owned results whose diagnostics may project onto this file. */
  def resultsFor(source: FileId): List[BendCheckResult]
  def busy: Boolean
  def status(root: FileId): BendCheckingStatus
