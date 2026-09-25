package com.dearlordylord.bend.idea.features.completion

import com.dearlordylord.bend.idea.syntax.BendLanguage
import com.intellij.openapi.actionSystem.{
  ActionUpdateThread,
  AnAction,
  AnActionEvent,
  CommonDataKeys
}

/** Deliberate workspace import; ordinary completion remains name-only. */
final class BendExplicitImportAction
    extends AnAction("Import Bend Symbol at Caret"):
  private val flow = new BendExplicitImportFlow

  override def getActionUpdateThread: ActionUpdateThread =
    ActionUpdateThread.BGT

  override def update(event: AnActionEvent): Unit =
    val editor = event.getData(CommonDataKeys.EDITOR)
    val file = event.getData(CommonDataKeys.PSI_FILE)
    val reference =
      if editor == null || file == null ||
        file.getLanguage != BendLanguage.instance
      then None
      else flow.referenceAt(file, editor.getCaretModel.getOffset)
    event.getPresentation.setEnabledAndVisible(
      reference.nonEmpty
    )

  override def actionPerformed(event: AnActionEvent): Unit =
    val project = event.getProject
    val editor = event.getData(CommonDataKeys.EDITOR)
    val file = event.getData(CommonDataKeys.PSI_FILE)
    if project == null || editor == null || file == null then return
    flow.referenceAt(file, editor.getCaretModel.getOffset) match
      case Some(reference) if flow.isUnresolved(reference) =>
        flow.importAt(project, editor, file, reference)
      case _ => ()
