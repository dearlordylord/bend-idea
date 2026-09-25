package com.dearlordylord.bend.idea.analysis.api

import com.dearlordylord.bend.idea.analysis.model.{
  BendCheckReservation,
  BendCheckResult,
  BendCheckSnapshot
}
import com.dearlordylord.bend.idea.model.FileId
import com.dearlordylord.bend.idea.analysis.model.BendCheckingStatus

/** Narrow scheduler control; policy decisions stay behind this analysis
  * boundary.
  */
final case class BendBackgroundCheckTicket(
    root: FileId,
    token: Long,
    attempt: Int
)

trait BendBackgroundCheckControl:
  def requestBackground(root: FileId): Option[BendBackgroundCheckTicket]

  /** True only while this exact debounce token remains scheduled by policy. */
  def backgroundScheduleCurrent(root: FileId, token: Long): Boolean
  def backgroundScheduleFailed(root: FileId, token: Long): Unit
  def backgroundTimerFired(
      root: FileId,
      token: Long
  ): Option[BendBackgroundCheckTicket]
  def backgroundCurrent(ticket: BendBackgroundCheckTicket): Boolean
  def beginBackground(
      ticket: BendBackgroundCheckTicket,
      snapshot: BendCheckSnapshot,
      subscribe: (() => Unit) => (() => Unit),
      isCurrent: () => Boolean
  ): Option[BendCheckReservation]
  def backgroundAttemptFailed(ticket: BendBackgroundCheckTicket): Unit
  def backgroundStaleObserved(root: FileId): Unit
  def backgroundConfigurationChanged(enabled: Boolean): Unit
  def backgroundSchedulerDisposed(): Unit
  def backgroundAffectedRoots(source: FileId): Set[FileId]

/** Explicit root check and last published root result, consumed by the editor.
  */
trait BendCheckService extends BendBackgroundCheckControl:
  /** Reserve the project worker and observe later edits to this root document.
    */
  def begin(
      snapshot: BendCheckSnapshot,
      subscribe: (() => Unit) => (() => Unit),
      isCurrent: () => Boolean
  ): Option[BendCheckReservation]
  def cancel(root: FileId): Unit
  def configurationChanged(): Unit
  def check(
      snapshot: BendCheckSnapshot,
      reservation: BendCheckReservation,
      canceled: () => Boolean
  ): Option[BendCheckResult]
  def result(root: FileId): Option[BendCheckResult]

  /** Subscribe to root-owned status/result transitions. Callbacks receive only
    * the root identity and must unsubscribe when their UI owner is disposed.
    */
  def addStatusListener(listener: FileId => Unit): () => Unit

  /** In-memory publication guard for UI callbacks. This must not traverse
    * sources or the filesystem.
    */
  def isCurrent(result: BendCheckResult): Boolean

  /** Current root-owned results whose diagnostics may project onto this file.
    */
  def resultsFor(source: FileId): List[BendCheckResult]
  def busy: Boolean
  def status(root: FileId): BendCheckingStatus
