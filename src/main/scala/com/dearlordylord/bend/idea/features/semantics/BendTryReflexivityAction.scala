package com.dearlordylord.bend.idea.features.semantics

import com.dearlordylord.bend.idea.analysis.api.{
  BendCheckService,
  BendExplicitCheckOutcome,
  BendExplicitCheckRunner,
  BendGoalAvailability,
  BendGoalQuery,
  BendProofEditPreview,
  BendProofEditPreviewOutcome,
  BendProofEditValidator
}
import com.dearlordylord.bend.idea.analysis.model.BendTextRange
import com.dearlordylord.bend.idea.model.FileId
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
    val document = editor.getDocument
    val revision = document.getModificationStamp
    val offset = editor.getCaretModel.getOffset
    val id = new FileId(
      Option(file.getCanonicalPath).getOrElse(file.getPath),
      file.getCanonicalPath != null
    )
    val checkService = project.getService(classOf[BendCheckService])
    val knownRoots = checkService.resultsFor(id).map(_.key.root.value).distinct
    if knownRoots.size > 1 then
      info(editor, "Select one proof root before trying reflexivity")
      return
    val rootPath = knownRoots.headOption.getOrElse(file.getPath)
    project
      .getService(classOf[BendExplicitCheckRunner])
      .checkGoal(rootPath, "Checking Bend goal for reflexivity") {
        case BendExplicitCheckOutcome.Published(result)
            if current(editor, revision, offset) =>
          BendGoalQuery.at(
            result,
            id,
            document.getText,
            revision,
            offset
          ) match
            case BendGoalAvailability.Available(goal)
                if BendTryReflexivityAction.inDefinitionBody(
                  project,
                  editor,
                  goal.range
                ) =>
              project
                .getService(classOf[BendProofEditValidator])
                .previewReflexivity(result, goal) {
                  case BendProofEditPreviewOutcome.Validated(preview)
                      if current(editor, revision, offset) &&
                        checkService.isCurrent(result) =>
                    offer(project, editor, revision, preview, checkService)
                  case BendProofEditPreviewOutcome.Rejected(reason)
                      if current(editor, revision, offset) =>
                    info(editor, reason)
                  case _ => ()
                }
            case BendGoalAvailability.Available(_) =>
              info(
                editor,
                "Reflexivity edits are offered only inside definition bodies"
              )
            case BendGoalAvailability.Unavailable(reason) =>
              info(editor, reason)
        case BendExplicitCheckOutcome.Rejected(_, reason)
            if current(editor, revision, offset) =>
          info(editor, reason)
        case _ => ()
      }

  private def offer(
      project: Project,
      editor: Editor,
      revision: Long,
      preview: BendProofEditPreview,
      checkService: BendCheckService
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
            if !editor.isDisposed &&
              document.getModificationStamp == revision &&
              document.getText == preview.source.text &&
              checkService.isCurrent(preview.originalCheck) &&
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

  private def current(editor: Editor, revision: Long, offset: Int): Boolean =
    !editor.isDisposed && editor.getDocument.getModificationStamp == revision &&
      editor.getCaretModel.getOffset == offset

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
