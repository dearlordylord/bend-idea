package com.dearlordylord.bend.idea.features.proofs

import com.dearlordylord.bend.idea.analysis.api.{
  BendExplicitCheckOutcome,
  BendExplicitCheckRunner
}
import com.dearlordylord.bend.idea.analysis.model.BendCheckOutcome
import com.dearlordylord.bend.idea.workspace.api.BendWorkspacePaths
import com.intellij.notification.{NotificationGroupManager, NotificationType}
import com.intellij.openapi.actionSystem.{
  AnAction,
  AnActionEvent,
  CommonDataKeys
}
import com.intellij.openapi.fileChooser.{FileChooser, FileChooserDescriptor}
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import scala.jdk.CollectionConverters.*
import java.nio.file.Path

/** Selects and checks a persisted proof root independently of the active file.
  */
final class BendCheckProofRootAction extends AnAction("Check Bend Proof Root"):
  private val SuggestionLimit = 128

  override def update(event: AnActionEvent): Unit =
    event.getPresentation.setEnabledAndVisible(event.getProject != null)

  override def actionPerformed(event: AnActionEvent): Unit =
    val project = event.getProject
    if project == null then return
    val current = Option(event.getData(CommonDataKeys.VIRTUAL_FILE))
      .filter(file => file.isValid && !file.isDirectory)
      .map(_.getPath)
    val store = project.getService(classOf[BendProofRootStore])
    val conventional = project
      .getService(classOf[BendWorkspacePaths])
      .filesNamed("PROOF.bend", SuggestionLimit)
    val roots = BendProofRootSelection.candidates(
      current,
      store.selectedPaths,
      conventional
    )
    val choices = BendProofRootSelection.choices(roots)
    val popup = JBPopupFactory
      .getInstance()
      .createPopupChooserBuilder(choices.asJava)
      .setTitle("Select Bend Proof Root")
      .setItemChosenCallback { choice =>
        choice.path match
          case Some(path) => check(project, path)
          case None       => browse(project)
      }
      .createPopup()
    Option(event.getData(CommonDataKeys.EDITOR)) match
      case Some(editor) => popup.showInBestPositionFor(editor)
      case None         => popup.showCenteredInCurrentWindow(project)

  private def browse(project: Project): Unit =
    val descriptor = new FileChooserDescriptor(
      true, false, false, false, false, false
    ).withTitle("Choose Bend Proof Root")
    Option(FileChooser.chooseFile(descriptor, project, null))
      .filter(file => !file.isDirectory && file.getName.endsWith(".bend"))
      .foreach(file => check(project, file.getPath))

  private[proofs] def check(project: Project, path: String): Unit =
    check(project, path, _ => ())

  private[proofs] def check(
      project: Project,
      path: String,
      completed: BendExplicitCheckOutcome => Unit
  ): Unit =
    project.getService(classOf[BendProofRootStore]).select(path)
    project
      .getService(classOf[BendExplicitCheckRunner])
      .check(path, "Checking Bend proof root") {
        case outcome @ BendExplicitCheckOutcome.Published(result) =>
          val detail = result.details.trim
          val message =
            if result.outcome == BendCheckOutcome.Unavailable && detail.nonEmpty
            then s"${result.status}\n$detail"
            else result.status
          notify(
            project,
            path,
            message,
            if result.outcome == BendCheckOutcome.Unavailable then
              NotificationType.WARNING
            else NotificationType.INFORMATION
          )
          completed(outcome)
        case outcome @ BendExplicitCheckOutcome.Rejected(_, reason) =>
          notify(project, path, reason, NotificationType.WARNING)
          completed(outcome)
      }

  private def notify(
      project: Project,
      path: String,
      status: String,
      kind: NotificationType
  ): Unit =
    val name = Path.of(path).getFileName.toString
    NotificationGroupManager
      .getInstance()
      .getNotificationGroup("Bend")
      .createNotification(s"$name — $path", status, kind)
      .notify(project)
