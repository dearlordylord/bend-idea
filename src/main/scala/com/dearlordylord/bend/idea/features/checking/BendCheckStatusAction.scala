package com.dearlordylord.bend.idea.features.checking

import com.dearlordylord.bend.idea.analysis.api.BendCheckService
import com.dearlordylord.bend.idea.analysis.model.BendCheckingStatus
import com.dearlordylord.bend.idea.model.FileId
import com.intellij.openapi.actionSystem.{AnAction, AnActionEvent, CommonDataKeys}

/** The Tools menu exposes the selected root's actual check state. */
final class BendCheckStatusAction extends AnAction("Bend Check Status"):
  override def update(event: AnActionEvent): Unit =
    val file = event.getData(CommonDataKeys.VIRTUAL_FILE)
    val project = event.getProject
    val eligible = file != null && project != null && file.getName.endsWith(".bend")
    event.getPresentation.setVisible(eligible)
    event.getPresentation.setEnabled(false)
    if eligible then
      val id = new FileId(Option(file.getCanonicalPath).getOrElse(file.getPath),
        file.getCanonicalPath != null)
      val status = project.getService(classOf[BendCheckService]).status(id)
      event.getPresentation.setText("Bend: " + BendCheckingStatus.label(status))

  override def actionPerformed(event: AnActionEvent): Unit = ()
