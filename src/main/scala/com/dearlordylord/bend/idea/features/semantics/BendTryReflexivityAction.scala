package com.dearlordylord.bend.idea.features.semantics

import com.dearlordylord.bend.idea.analysis.api.{
  BendProofEditPreview,
  BendProofEditPreviewOutcome,
  BendProofEditValidator
}
import com.dearlordylord.bend.idea.analysis.model.BendTextRange
import com.dearlordylord.bend.idea.features.semantics.api.{
  BendCurrentLocationInquiry,
  BendInquiryLocation,
  BendInquiryOutcome
}
import com.dearlordylord.bend.idea.syntax.psi.{BendDefinition, BendHeader}
import com.intellij.codeInsight.hint.HintManager
import com.intellij.openapi.actionSystem.{
  ActionUpdateThread,
  AnAction,
  AnActionEvent,
  CommonDataKeys
}
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.util.PsiTreeUtil

/** Checks a candidate {==} in a derived snapshot before offering one undoable
  * replacement of a named hole inside a definition body.
  */
final class BendTryReflexivityAction extends AnAction("Try Bend Reflexivity"):
  override def getActionUpdateThread: ActionUpdateThread =
    ActionUpdateThread.BGT

  override def update(event: AnActionEvent): Unit =
    val file = event.getData(CommonDataKeys.VIRTUAL_FILE)
    event.getPresentation.setEnabledAndVisible(
      file != null && file.getName.endsWith(".bend") &&
        event.getData(CommonDataKeys.EDITOR) != null
    )

  override def actionPerformed(event: AnActionEvent): Unit =
    val editor = event.getData(CommonDataKeys.EDITOR)
    val file = event.getData(CommonDataKeys.VIRTUAL_FILE)
    val project = event.getProject
    if editor == null || file == null || project == null then return
    val inquiry = project.getService(classOf[BendCurrentLocationInquiry])
    inquiry.goal(editor, file, "Checking Bend goal for reflexivity") {
      case BendInquiryOutcome.Available(result, location, goal) =>
        if BendTryReflexivityAction.inDefinitionBody(
            project,
            editor,
            goal.range
          )
        then
          project
            .getService(classOf[BendProofEditValidator])
            .previewReflexivity(result, goal) {
              case BendProofEditPreviewOutcome.Validated(preview)
                  if inquiry.isCurrent(editor, result, location) =>
                offer(project, editor, location, preview, inquiry)
              case BendProofEditPreviewOutcome.Rejected(reason)
                  if inquiry.isCurrent(editor, result, location) =>
                info(editor, reason)
              case _ => ()
            }
        else
          info(
            editor,
            "Reflexivity edits are offered only inside definition bodies"
          )
      case BendInquiryOutcome.Unavailable(reason) =>
        info(editor, reason)
      case BendInquiryOutcome.AmbiguousRoots =>
        info(editor, "Select one proof root before trying reflexivity")
    }

  private def offer(
      project: Project,
      editor: Editor,
      location: BendInquiryLocation,
      preview: BendProofEditPreview,
      inquiry: BendCurrentLocationInquiry
  ): Unit =
    val status = if preview.completeProof then
      "The complete selected root checks successfully."
    else "This local step checks, but the selected root still has TODO holes."
    val message =
      s"Replace ?${preview.goal.holeName} with ${preview.replacement}?\n$status"
    if Messages.showYesNoDialog(
        project,
        message,
        "Validated Bend Reflexivity",
        Messages.getQuestionIcon
      ) == Messages.YES
    then
      val document = editor.getDocument
      var applied = false
      WriteCommandAction.runWriteCommandAction(
        project,
        "Apply Bend reflexivity",
        null,
        new Runnable:
          override def run(): Unit =
            val range = preview.goal.range
            if inquiry.isCurrent(editor, preview.originalCheck, location) &&
              document.getText == preview.source.text &&
              BendTryReflexivityAction
                .inDefinitionBody(project, editor, range) &&
              document.getText.substring(range.start, range.end) ==
                s"?${preview.goal.holeName}"
            then
              document.replaceString(
                range.start,
                range.end,
                preview.replacement
              )
              applied = true
      )
      if !applied then
        info(editor, "Source changed before reflexivity could be applied")

  private def info(editor: Editor, message: String): Unit =
    HintManager.getInstance().showInformationHint(editor, message)

object BendTryReflexivityAction:
  /** PSI establishes that this is a proof body, not a law or type header. */
  private[semantics] def inDefinitionBody(
      project: Project,
      editor: Editor,
      range: BendTextRange
  ): Boolean =
    val document = editor.getDocument
    if range.start < 0 || range.end > document.getTextLength then false
    else
      val manager = PsiDocumentManager.getInstance(project)
      manager.commitDocument(document)
      val file = manager.getPsiFile(document)
      if file == null then false
      else
        val element = file.findElementAt(range.start)
        val definition = PsiTreeUtil.getParentOfType(
          element,
          classOf[BendDefinition]
        )
        if definition == null then false
        else
          val header = PsiTreeUtil.getChildOfType(
            definition,
            classOf[BendHeader]
          )
          header != null && range.start >= header.getTextRange.getEndOffset &&
          range.end <= definition.getTextRange.getEndOffset
