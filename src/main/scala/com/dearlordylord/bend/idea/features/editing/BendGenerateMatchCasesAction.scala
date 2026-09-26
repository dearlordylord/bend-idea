package com.dearlordylord.bend.idea.features.editing

import com.intellij.codeInsight.hint.HintManager
import com.intellij.openapi.actionSystem.{
  ActionUpdateThread,
  AnAction,
  AnActionEvent,
  CommonDataKeys
}
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile

/** Adds only source-resolved constructor arms to a named-binder match. */
final class BendGenerateMatchCasesAction
    extends AnAction("Generate Match Cases"):
  override def getActionUpdateThread: ActionUpdateThread =
    ActionUpdateThread.BGT

  override def update(event: AnActionEvent): Unit =
    val editor = event.getData(CommonDataKeys.EDITOR)
    val file = event.getData(CommonDataKeys.PSI_FILE)
    val available = editor != null && file != null &&
      file.getName.endsWith(".bend") &&
      BendMatchSkeletonGenerator
        .plan(file, editor.getCaretModel.getOffset)
        .nonEmpty
    event.getPresentation.setEnabledAndVisible(available)

  override def actionPerformed(event: AnActionEvent): Unit =
    val editor = event.getData(CommonDataKeys.EDITOR)
    val file = event.getData(CommonDataKeys.PSI_FILE)
    if editor == null || file == null then return
    BendGenerateMatchCases.invoke(event.getProject, editor, file) match
      case Some(_) => ()
      case None    =>
        HintManager
          .getInstance()
          .showInformationHint(
            editor,
            "No ungenerated cases are available for this match"
          )

private[editing] object BendGenerateMatchCases:
  def invoke(
      project: Project,
      editor: Editor,
      file: PsiFile
  ): Option[BendMatchSkeletonInsertion] =
    if project == null || editor == null || file == null then return None
    BendMatchSkeletonGenerator
      .plan(file, editor.getCaretModel.getOffset)
      .flatMap(plan =>
        BendMatchSkeletonGenerator.insert(project, plan).toOption
      )
      .map { inserted =>
        editor.getCaretModel.moveToOffset(inserted.placeholderEnd)
        editor.getSelectionModel.setSelection(
          inserted.placeholderStart,
          inserted.placeholderEnd
        )
        inserted
      }
