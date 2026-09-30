package com.dearlordylord.bend.idea.features.checking

import com.dearlordylord.bend.idea.analysis.api.{
  BendCheckService,
  BendExplicitCheckOutcome,
  BendExplicitCheckRunner
}
import com.intellij.codeInsight.hint.HintManager
import com.intellij.openapi.actionSystem.{
  AnAction,
  AnActionEvent,
  ActionUpdateThread,
  CommonDataKeys
}

/** Checks the current document explicitly; no editor pass invokes Bend. */
final class BendCheckCurrentFileAction
    extends AnAction("Check Current Bend File"):
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
    val project = event.getData(CommonDataKeys.PROJECT)
    if editor == null || file == null || project == null then return
    val document = editor.getDocument
    val revision = document.getModificationStamp
    project
      .getService(classOf[BendExplicitCheckRunner])
      .check(file.getPath, "Checking Bend") {
        case BendExplicitCheckOutcome.Published(result)
            if document.getModificationStamp == revision =>
          HintManager
            .getInstance()
            .showInformationHint(
              editor,
              BendCheckPresentation.summary(
                project.getService(
                  classOf[BendCheckService]
                ),
                file
              )
            )
        case BendExplicitCheckOutcome.Rejected(_, reason)
            if document.getModificationStamp == revision =>
          HintManager.getInstance().showInformationHint(editor, reason)
        case _ => ()
      }
