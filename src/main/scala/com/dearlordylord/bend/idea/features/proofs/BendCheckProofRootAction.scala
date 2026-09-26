package com.dearlordylord.bend.idea.features.proofs

import com.dearlordylord.bend.idea.analysis.api.{
  BendExplicitCheckOutcome,
  BendExplicitCheckRunner
}
import com.dearlordylord.bend.idea.analysis.model.{
  BendCheckOutcome,
  BendCompleteness
}
import com.dearlordylord.bend.idea.workspace.api.{
  BendNamedPathInventory,
  BendPathInventoryStatus,
  BendWorkspacePaths
}
import com.intellij.notification.{NotificationGroupManager, NotificationType}
import com.intellij.openapi.actionSystem.{
  AnAction,
  AnActionEvent,
  CommonDataKeys
}
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileChooser.{FileChooser, FileChooserDescriptor}
import com.intellij.openapi.progress.{ProgressIndicator, Task}
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
    val editor = Option(event.getData(CommonDataKeys.EDITOR))
    val selected = project
      .getService(classOf[BendProofRootStore])
      .selectedPaths
    new Task.Backgroundable(project, "Finding Bend proof roots", true):
      private var discovered: Option[BendNamedPathInventory] = None

      override def run(indicator: ProgressIndicator): Unit =
        val inventory = project
          .getService(classOf[BendWorkspacePaths])
          .filesNamed("PROOF.bend", SuggestionLimit)
        if !indicator.isCanceled then discovered = Some(inventory)

      override def onSuccess(): Unit =
        if !project.isDisposed then
          discovered.foreach(conventional =>
            showChooser(project, current, selected, conventional, editor)
          )
    .queue()

  private def showChooser(
      project: Project,
      current: Option[String],
      selected: List[String],
      conventional: BendNamedPathInventory,
      editor: Option[Editor]
  ): Unit =
    val roots = BendProofRootSelection.candidates(
      current,
      selected,
      conventional.paths
    )
    val choices = BendProofRootSelection.choices(roots)
    val popup = JBPopupFactory
      .getInstance()
      .createPopupChooserBuilder(choices.asJava)
      .setTitle(
        BendPathInventoryStatus
          .notice(conventional.status)
          .fold("Select Bend Proof Root")(notice =>
            s"Select Bend Proof Root — $notice"
          )
      )
      .setItemChosenCallback { choice =>
        choice.path match
          case Some(path) => check(project, path)
          case None       => browse(project)
      }
      .createPopup()
    editor match
      case Some(value) if !value.isDisposed =>
        popup.showInBestPositionFor(value)
      case _ => popup.showCenteredInCurrentWindow(project)

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
          val completeSuccess =
            result.outcome == BendCheckOutcome.Success &&
              result.completeness == BendCompleteness.Complete
          val message =
            if !completeSuccess && detail.nonEmpty
            then s"${result.status}\n$detail"
            else result.status
          val kind =
            if completeSuccess then NotificationType.INFORMATION
            else if result.outcome == BendCheckOutcome.Failed &&
              result.completeness != BendCompleteness.Incomplete
            then NotificationType.ERROR
            else NotificationType.WARNING
          notify(
            project,
            path,
            message,
            kind
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
