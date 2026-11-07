package com.dearlordylord.bend.idea.features.checking

import com.dearlordylord.bend.idea.analysis.api.{
  BendExplicitCheckOutcome,
  BendExplicitCheckRunner
}
import com.dearlordylord.bend.idea.toolchain.api.BendToolchainSettings
import com.intellij.openapi.actionSystem.{
  AnAction,
  AnActionEvent,
  ActionUpdateThread,
  CommonDataKeys
}
import com.intellij.openapi.application.ApplicationManager
import com.intellij.notification.{NotificationGroupManager, NotificationType}
import com.intellij.openapi.util.text.StringUtil

/** The current file is explicitly the root; the worker captures its imports. */
final class BendRunLintAction extends AnAction("Run bend-lint on Current File"):
  override def getActionUpdateThread: ActionUpdateThread =
    ActionUpdateThread.BGT

  override def update(event: AnActionEvent): Unit =
    val file = event.getData(CommonDataKeys.VIRTUAL_FILE)
    event.getPresentation.setEnabledAndVisible(
      event.getProject != null && file != null && file.getName.endsWith(".bend")
    )

  override def actionPerformed(event: AnActionEvent): Unit =
    val project = event.getProject
    val file = event.getData(CommonDataKeys.VIRTUAL_FILE)
    if project == null || file == null then return
    def show(message: String): Unit =
      NotificationGroupManager
        .getInstance()
        .getNotificationGroup("Bend")
        .createNotification(
          "bend-lint",
          StringUtil.escapeXmlEntities(message).replace("\n", "<br/>"),
          NotificationType.INFORMATION
        )
        .notify(project)
    val options = ApplicationManager.getApplication
      .getService(classOf[BendToolchainSettings])
      .selection
      .lint
    if !options.enabled || options.bun.isEmpty || options.directory.isEmpty then
      show(
        "Enable bend-lint and install its pinned tools in Settings | Languages & Frameworks | Bend."
      )
      return
    project
      .getService(classOf[BendExplicitCheckRunner])
      .checkLint(file.getPath, "Running bend-lint") {
        case BendExplicitCheckOutcome.Rejected(_, reason) => show(reason)
        case BendExplicitCheckOutcome.Published(result)   =>
          show(
            result.lint.fold(
              "bend-lint did not run: " + result.status + ". " + result.details
                .take(512)
            )(lint => BendCheckPresentation.lintDetails(lint, result.sources))
          )
      }
