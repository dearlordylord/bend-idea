package com.dearlordylord.bend.idea.features.checking

import com.dearlordylord.bend.idea.analysis.api.BendCheckService
import com.intellij.openapi.actionSystem.{
  AnAction,
  AnActionEvent,
  ActionUpdateThread,
  CommonDataKeys
}

/** The Tools menu exposes the selected root's actual check state. */
final class BendCheckStatusAction extends AnAction("Show Bend Check Status"):
  override def getActionUpdateThread: ActionUpdateThread =
    ActionUpdateThread.BGT

  override def update(event: AnActionEvent): Unit =
    val file = event.getData(CommonDataKeys.VIRTUAL_FILE)
    val project = event.getProject
    val eligible =
      file != null && project != null && file.getName.endsWith(".bend")
    event.getPresentation.setVisible(eligible)
    event.getPresentation.setEnabled(eligible)
    if eligible then
      event.getPresentation.setText("Show Bend Check Status")
      event.getPresentation.setDescription(
        BendCheckPresentation.summary(
          project.getService(classOf[BendCheckService]),
          file
        )
      )

  override def actionPerformed(event: AnActionEvent): Unit =
    val file = event.getData(CommonDataKeys.VIRTUAL_FILE)
    val project = event.getProject
    if file != null && project != null then
      BendCheckStatusPopup.show(project, file)
