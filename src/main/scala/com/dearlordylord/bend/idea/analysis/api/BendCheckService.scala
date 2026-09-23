package com.dearlordylord.bend.idea.analysis.api

import com.dearlordylord.bend.idea.analysis.model.{BendCheckResult, BendCheckSnapshot}
import com.dearlordylord.bend.idea.model.FileId

/** Explicit root check and last published root result, consumed by the editor. */
trait BendCheckService:
  /** Reserve the project worker and observe later edits to this root document. */
  def begin(snapshot: BendCheckSnapshot,
      subscribe: (() => Unit) => (() => Unit), isCurrent: () => Boolean): Boolean
  def cancel(root: FileId): Unit
  def configurationChanged(): Unit
  def check(snapshot: BendCheckSnapshot, canceled: () => Boolean): Option[BendCheckResult]
  def result(root: FileId): Option[BendCheckResult]
  def busy: Boolean
