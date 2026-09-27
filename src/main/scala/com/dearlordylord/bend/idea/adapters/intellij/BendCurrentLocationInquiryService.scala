package com.dearlordylord.bend.idea.adapters.intellij

import com.dearlordylord.bend.idea.analysis.api.{
  BendCheckService,
  BendExplicitCheckOutcome,
  BendExplicitCheckRunner,
  BendGoalAvailability,
  BendGoalQuery
}
import com.dearlordylord.bend.idea.analysis.model.{
  BendCheckResult,
  BendGoal,
  BendNormalization,
  BendNormalizationRequest
}
import com.dearlordylord.bend.idea.features.semantics.api.{
  BendCurrentLocationInquiry,
  BendInquiryLocation,
  BendInquiryOutcome
}
import com.dearlordylord.bend.idea.model.FileId
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/** Captures an editor location, selects its known root and accepts only a
  * compiler fact that still belongs to that location at delivery.
  */
final class BendCurrentLocationInquiryService(project: Project)
    extends BendCurrentLocationInquiry:
  private val checks = project.getService(classOf[BendCheckService])
  private val runner = project.getService(classOf[BendExplicitCheckRunner])

  override def goal(editor: Editor, file: VirtualFile, taskTitle: String)(
      completed: BendInquiryOutcome[BendGoal] => Unit
  ): Unit =
    inquire(editor, file, completed)(
      (root, _, callback) => runner.checkGoal(root, taskTitle)(callback),
      (result, location) =>
        BendGoalQuery.at(
          result,
          location.source,
          location.text,
          location.revision,
          location.offset
        ) match
          case BendGoalAvailability.Available(goal)     => Right(goal)
          case BendGoalAvailability.Unavailable(reason) => Left(reason)
    )

  override def normalization(
      editor: Editor,
      file: VirtualFile,
      taskTitle: String
  )(completed: BendInquiryOutcome[BendNormalization] => Unit): Unit =
    inquire(editor, file, completed)(
      (root, location, callback) =>
        runner.checkNormalization(
          root,
          BendNormalizationRequest(location.source, location.offset),
          taskTitle
        )(callback),
      (result, location) =>
        result.normalization match
          case Some(normal)
              if normal.source == location.source &&
                normal.range.start <= location.offset &&
                location.offset < normal.range.end =>
            Right(normal)
          case _ =>
            Left(
              "Normalization unavailable here: select a closed, checked expression with an exact compiler span"
            )
    )

  override def isCurrent(
      editor: Editor,
      result: BendCheckResult,
      location: BendInquiryLocation
  ): Boolean =
    currentEditor(editor, location) && checks.isCurrent(result) &&
      result.fresh && result.sources.exists(source =>
        source.id == location.source && source.text == location.text &&
          source.revision == location.revision
      )

  private def currentEditor(
      editor: Editor,
      location: BendInquiryLocation
  ): Boolean =
    !project.isDisposed && !editor.isDisposed &&
      editor.getDocument.getModificationStamp == location.revision &&
      editor.getDocument.getText == location.text &&
      editor.getCaretModel.getOffset == location.offset

  private def inquire[A](
      editor: Editor,
      file: VirtualFile,
      completed: BendInquiryOutcome[A] => Unit
  )(
      start: (
          String,
          BendInquiryLocation,
          BendExplicitCheckOutcome => Unit
      ) => Unit,
      fact: (BendCheckResult, BendInquiryLocation) => Either[String, A]
  ): Unit =
    val canonical = Option(file.getCanonicalPath)
    val id = new FileId(canonical.getOrElse(file.getPath), canonical.isDefined)
    val document = editor.getDocument
    val location = BendInquiryLocation(
      id,
      document.getText,
      document.getModificationStamp,
      editor.getCaretModel.getOffset
    )
    val roots = checks.resultsFor(id).map(_.key.root.value).distinct
    if roots.size > 1 then completed(BendInquiryOutcome.AmbiguousRoots)
    else
      val root = roots.headOption.getOrElse(file.getPath)
      start(
        root,
        location,
        {
          case BendExplicitCheckOutcome.Published(result)
              if isCurrent(editor, result, location) =>
            fact(result, location) match
              case Right(value) =>
                completed(BendInquiryOutcome.Available(result, location, value))
              case Left(reason) =>
                completed(BendInquiryOutcome.Unavailable(reason))
          case BendExplicitCheckOutcome.Rejected(_, reason)
              if currentEditor(editor, location) =>
            completed(BendInquiryOutcome.Unavailable(reason))
          case _ => ()
        }
      )
