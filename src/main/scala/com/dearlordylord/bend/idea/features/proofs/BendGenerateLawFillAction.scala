package com.dearlordylord.bend.idea.features.proofs

import com.dearlordylord.bend.idea.syntax.psi.BendLaw
import com.intellij.notification.{NotificationGroupManager, NotificationType}
import com.intellij.openapi.actionSystem.{
  AnAction,
  AnActionEvent,
  ActionUpdateThread,
  CommonDataKeys
}
import com.intellij.openapi.fileEditor.{FileEditorManager, OpenFileDescriptor}
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import scala.jdk.CollectionConverters.*

/** Explicitly generates a source-level implementation skeleton for a law. */
final class BendGenerateLawFillAction
    extends AnAction("Generate Bend Law Fill"):
  override def getActionUpdateThread: ActionUpdateThread =
    ActionUpdateThread.BGT

  override def update(event: AnActionEvent): Unit =
    val editor = event.getData(CommonDataKeys.EDITOR)
    val file = Option(event.getData(CommonDataKeys.PSI_FILE))
    val enabled = editor != null && file.exists(
      currentLaw(_, editor.getCaretModel.getOffset).nonEmpty
    )
    event.getPresentation.setVisible(true)
    event.getPresentation.setEnabled(enabled)
    event.getPresentation.setDescription(
      if enabled then "Insert an incomplete law fill into a selected proof root"
      else "Place the caret on a law declaration to enable law-fill generation"
    )

  override def actionPerformed(event: AnActionEvent): Unit =
    val project = event.getProject
    val editor = event.getData(CommonDataKeys.EDITOR)
    val file = event.getData(CommonDataKeys.PSI_FILE)
    if project == null || editor == null || file == null then return
    currentLaw(file, editor.getCaretModel.getOffset) match
      case None =>
        notify(
          project,
          "Place the caret on a Bend law declaration",
          NotificationType.WARNING
        )
      case Some(law) =>
        val targets = BendProofFillGenerator.candidates(
          law,
          BendProofNavigation.roots(file)
        )
        if targets.isEmpty then
          notify(
            project,
            "No writable proof root exposes this law without an existing fill",
            NotificationType.WARNING
          )
        else if targets.size == 1 then insert(project, targets.head)
        else
          JBPopupFactory
            .getInstance()
            .createPopupChooserBuilder(targets.asJava)
            .setTitle("Generate law fill in proof root")
            .setItemChosenCallback(target => insert(project, target))
            .createPopup()
            .showInBestPositionFor(editor)

  private def currentLaw(file: PsiFile, offset: Int): Option[BendLaw] =
    Option(
      file.findElementAt(math.max(0, math.min(offset, file.getTextLength - 1)))
    )
      .flatMap(element =>
        Option(PsiTreeUtil.getParentOfType(element, classOf[BendLaw]))
      )

  private def insert(
      project: Project,
      target: BendProofFillTarget
  ): Unit =
    BendProofFillGenerator.insert(project, target) match
      case Left(reason)  => notify(project, reason, NotificationType.WARNING)
      case Right(result) =>
        Option(
          FileEditorManager
            .getInstance(project)
            .openTextEditor(
              new OpenFileDescriptor(
                project,
                result.file,
                result.placeholderStart
              ),
              true
            )
        )
          .foreach { editor =>
            editor.getSelectionModel.setSelection(
              result.placeholderStart,
              result.placeholderEnd
            )
          }
        notify(
          project,
          "Law fill skeleton inserted; ?TODO remains incomplete until replaced",
          NotificationType.INFORMATION
        )

  private def notify(
      project: Project,
      message: String,
      kind: NotificationType
  ): Unit =
    NotificationGroupManager
      .getInstance()
      .getNotificationGroup("Bend")
      .createNotification(message, kind)
      .notify(project)
