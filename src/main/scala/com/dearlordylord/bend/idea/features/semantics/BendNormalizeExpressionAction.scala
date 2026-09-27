package com.dearlordylord.bend.idea.features.semantics

import com.dearlordylord.bend.idea.features.semantics.api.{
  BendCurrentLocationInquiry,
  BendInquiryOutcome
}
import com.intellij.codeInsight.hint.HintManager
import com.intellij.openapi.actionSystem.{
  ActionUpdateThread,
  AnAction,
  AnActionEvent,
  CommonDataKeys
}

/** Displays a bounded compiler normalization for a closed checked expression.
  */
final class BendNormalizeExpressionAction
    extends AnAction("Normalize Bend Expression"):
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
    project
      .getService(classOf[BendCurrentLocationInquiry])
      .normalization(editor, file, "Normalizing Bend expression") {
        case BendInquiryOutcome.Available(_, _, normal) =>
          val escaped = normal.text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
          info(
            editor,
            s"<html><b>Normalized by Bend</b><br><pre>$escaped</pre></html>"
          )
        case BendInquiryOutcome.Unavailable(reason) =>
          info(editor, reason)
        case BendInquiryOutcome.AmbiguousRoots =>
          info(editor, "Select one Bend root before normalizing")
      }

  private def info(
      editor: com.intellij.openapi.editor.Editor,
      text: String
  ): Unit =
    HintManager.getInstance().showInformationHint(editor, text)
