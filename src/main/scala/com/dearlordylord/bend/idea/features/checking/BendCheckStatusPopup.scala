package com.dearlordylord.bend.idea.features.checking

import com.dearlordylord.bend.idea.analysis.api.{
  BendCheckService,
  BendExplicitCheckRunner
}
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.ui.popup.JBPopupFactory
import java.awt.BorderLayout
import javax.swing.{JButton, JLabel, JPanel, JScrollPane, JTextArea}

private[checking] object BendCheckStatusPopup:
  def show(project: Project, file: VirtualFile): Unit =
    if project.isDisposed || !file.isValid then return
    val service = project.getService(classOf[BendCheckService])
    val result = service.result(BendCheckPresentation.id(file))
    val panel = new JPanel(new BorderLayout(8, 8))
    panel.add(
      new JLabel(BendCheckPresentation.summary(service, file)),
      BorderLayout.NORTH
    )
    val details = result
      .map(value => s"${value.status}\n\n${value.details}")
      .getOrElse(
        "No compiler result for this file. Use Check again to check it."
      )
    val output = new JTextArea(details, 12, 64)
    output.setEditable(false)
    output.setLineWrap(true)
    output.setWrapStyleWord(true)
    output.setCaretPosition(0)
    panel.add(new JScrollPane(output), BorderLayout.CENTER)
    val check = new JButton("Check again")
    panel.add(check, BorderLayout.SOUTH)
    val popup = JBPopupFactory
      .getInstance()
      .createComponentPopupBuilder(panel, check)
      .setTitle(file.getName)
      .setResizable(true)
      .setMovable(true)
      .setRequestFocus(true)
      .createPopup()
    check.addActionListener(_ =>
      popup.cancel()
      project
        .getService(classOf[BendExplicitCheckRunner])
        .check(file.getPath, "Checking Bend")(_ => ())
    )
    popup.showInFocusCenter()
