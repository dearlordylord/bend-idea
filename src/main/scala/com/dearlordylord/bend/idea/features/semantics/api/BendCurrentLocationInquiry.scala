package com.dearlordylord.bend.idea.features.semantics.api

import com.dearlordylord.bend.idea.analysis.model.{
  BendCheckResult,
  BendGoal,
  BendNormalization
}
import com.dearlordylord.bend.idea.model.FileId
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.vfs.VirtualFile

/** Immutable editor location used to associate one explicit compiler fact with
  * the source that requested it. This value carries no platform handles.
  */
final case class BendInquiryLocation(
    source: FileId,
    text: String,
    revision: Long,
    offset: Int
)

sealed trait BendInquiryOutcome[+A]

object BendInquiryOutcome:
  final case class Available[A](
      result: BendCheckResult,
      location: BendInquiryLocation,
      value: A
  ) extends BendInquiryOutcome[A]
  final case class Unavailable(reason: String)
      extends BendInquiryOutcome[Nothing]
  case object AmbiguousRoots extends BendInquiryOutcome[Nothing]

/** Explicit current-location operations for semantic editor actions. */
trait BendCurrentLocationInquiry:
  def goal(editor: Editor, file: VirtualFile, taskTitle: String)(
      completed: BendInquiryOutcome[BendGoal] => Unit
  ): Unit

  def normalization(editor: Editor, file: VirtualFile, taskTitle: String)(
      completed: BendInquiryOutcome[BendNormalization] => Unit
  ): Unit

  /** Recheck after an additional asynchronous candidate operation. */
  def isCurrent(
      editor: Editor,
      result: BendCheckResult,
      location: BendInquiryLocation
  ): Boolean
