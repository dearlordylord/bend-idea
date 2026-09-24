package com.dearlordylord.bend.idea.features.editing

import com.intellij.codeInsight.hint.HintManager
import com.intellij.openapi.actionSystem.{
  ActionUpdateThread,
  AnAction,
  AnActionEvent,
  CommonDataKeys
}

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
    BendMatchSkeletonGenerator
      .plan(file, editor.getCaretModel.getOffset)
      .flatMap(plan =>
        BendMatchSkeletonGenerator
          .insert(file.getProject, plan)
          .toOption
      ) match
      case Some(inserted) =>
        editor.getSelectionModel.setSelection(
          inserted.placeholderStart,
          inserted.placeholderEnd
        )
        editor.getCaretModel.moveToOffset(inserted.placeholderEnd)
      case None =>
        HintManager
          .getInstance()
          .showInformationHint(
            editor,
            "No ungenerated cases are available for this match"
          )
