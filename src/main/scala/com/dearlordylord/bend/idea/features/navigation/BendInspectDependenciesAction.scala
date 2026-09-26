package com.dearlordylord.bend.idea.features.navigation

import com.dearlordylord.bend.idea.syntax.BendLanguage
import com.dearlordylord.bend.idea.syntax.psi.BendDeclaration
import com.dearlordylord.bend.idea.workspace.api.BendLoadingConfiguration
import com.intellij.openapi.actionSystem.{
  AnAction,
  AnActionEvent,
  ActionUpdateThread,
  CommonDataKeys
}
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.progress.{ProgressIndicator, ProgressManager, Task}
import com.intellij.openapi.project.{DumbAware, Project}
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.psi.{PsiDocumentManager, PsiFile}
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.util.PsiTreeUtil
import scala.jdk.CollectionConverters.*

/** Opens a navigable list of direct source dependencies around the caret. */
final class BendInspectDependenciesAction extends AnAction with DumbAware:
  override def getActionUpdateThread: ActionUpdateThread =
    ActionUpdateThread.BGT

  override def update(event: AnActionEvent): Unit =
    val file = event.getData(CommonDataKeys.PSI_FILE)
    val enabled = file != null && file.getLanguage == BendLanguage.instance
    event.getPresentation.setEnabledAndVisible(enabled)

  override def actionPerformed(event: AnActionEvent): Unit =
    val file = event.getData(CommonDataKeys.PSI_FILE)
    if file == null || file.getLanguage != BendLanguage.instance then return
    val project = file.getProject
    val editor = event.getData(CommonDataKeys.EDITOR)
    commitOpenDocuments(project)
    val pointer = ReadAction.compute(() =>
      val selected =
        if editor == null then file
        else
          Option(file.findElementAt(editor.getCaretModel.getOffset))
            .flatMap(element =>
              Option(
                PsiTreeUtil.getParentOfType(
                  element,
                  classOf[BendDeclaration],
                  false
                )
              )
            )
            .getOrElse(file)
      SmartPointerManager
        .getInstance(project)
        .createSmartPsiElementPointer(selected)
    )
    ProgressManager
      .getInstance()
      .run(
        new Task.Backgroundable(project, "Inspect Bend Dependencies", true):
          private var report
              : Option[Either[String, BendStaticDependencyReport]] =
            None

          override def run(indicator: ProgressIndicator): Unit =
            indicator.setText("Scanning Bend sources and references")
            val selected = ReadAction.compute(() =>
              ProgressManager.checkCanceled()
              Option(pointer.getElement)
            )
            report = Some(
              selected
                .map(BendStaticDependencies.inspect)
                .getOrElse(
                  Left("The selected Bend source is no longer available.")
                )
            )

          override def onSuccess(): Unit =
            if !project.isDisposed then
              report.foreach { result =>
                val current = result match
                  case Right(value) => reportIsCurrent(project, value)
                  case Left(_)      => true
                showReport(
                  project,
                  editor,
                  if current then result
                  else
                    Left(
                      "Bend sources or loading settings changed during inspection. Please retry."
                    )
                )
              }
      )

  private[navigation] def commitOpenDocuments(project: Project): Unit =
    PsiDocumentManager.getInstance(project).commitAllDocuments()

  private def showReport(
      project: Project,
      editor: Editor,
      result: Either[String, BendStaticDependencyReport]
  ): Unit =
    result match
      case Left(message) =>
        Messages.showInfoMessage(project, message, "Bend Dependencies")
      case Right(report) if report.dependencies.isEmpty =>
        Messages.showInfoMessage(
          project,
          "No direct static source dependencies were found.\n\n" +
            report.coverage,
          "Bend Dependencies"
        )
      case Right(report) =>
        val chooser = JBPopupFactory
          .getInstance()
          .createPopupChooserBuilder(report.dependencies.asJava)
        val popup = chooser
          .setTitle("Bend Static Dependencies")
          .setAdText(report.coverage)
          .setItemChosenCallback(dependency =>
            navigate(project, dependency, report)
          )
          .createPopup()
        if editor == null || editor.isDisposed then
          popup.showCenteredInCurrentWindow(project)
        else popup.showInBestPositionFor(editor)

  private def navigate(
      project: com.intellij.openapi.project.Project,
      dependency: BendStaticDependency,
      report: BendStaticDependencyReport
  ): Unit =
    val target = ReadAction.compute(() => {
      if !reportIsCurrent(project, report) then None
      else
        Option(dependency.navigation.getElement)
          .filter(_.isValid)
          .flatMap { element =>
            val file: PsiFile = element match
              case source: PsiFile => source
              case other           => other.getContainingFile
            Option(file)
              .filter(_.isValid)
              .flatMap(value =>
                Option(value.getVirtualFile)
                  .map(virtual => (virtual, element.getTextOffset))
              )
          }
    })
    target match
      case Some((virtual, offset)) =>
        new OpenFileDescriptor(project, virtual, offset).navigate(true)
      case None if !project.isDisposed =>
        Messages.showInfoMessage(
          project,
          "Bend sources or loading settings changed. Inspect dependencies again.",
          "Bend Dependencies"
        )
      case _ => ()

  private[navigation] def reportIsCurrent(
      project: Project,
      report: BendStaticDependencyReport
  ): Boolean = ReadAction.compute(() =>
    !PsiDocumentManager.getInstance(project).hasUncommitedDocuments &&
      PsiModificationTracker
        .getInstance(project)
        .getModificationCount == report.sourceModificationCount &&
      project
        .getService(classOf[BendLoadingConfiguration])
        .configurationRevision == report.loadingConfigurationRevision
  )
