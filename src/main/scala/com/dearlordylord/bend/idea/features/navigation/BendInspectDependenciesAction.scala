package com.dearlordylord.bend.idea.features.navigation

import com.dearlordylord.bend.idea.syntax.BendLanguage
import com.dearlordylord.bend.idea.syntax.psi.BendDeclaration
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
    if editor != null then
      PsiDocumentManager.getInstance(project).commitDocument(editor.getDocument)
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
            report = Some(
              ReadAction.compute(() =>
                ProgressManager.checkCanceled()
                Option(pointer.getElement)
                  .map(BendStaticDependencies.inspect)
                  .getOrElse(
                    Left("The selected Bend source is no longer available.")
                  )
              )
            )

          override def onSuccess(): Unit =
            if !project.isDisposed then
              report.foreach(result => showReport(project, editor, result))
      )

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
          .setItemChosenCallback(dependency => navigate(project, dependency))
          .createPopup()
        if editor == null || editor.isDisposed then
          popup.showCenteredInCurrentWindow(project)
        else popup.showInBestPositionFor(editor)

  private def navigate(
      project: com.intellij.openapi.project.Project,
      dependency: BendStaticDependency
  ): Unit =
    val file: PsiFile = dependency.navigation match
      case source: PsiFile => source
      case element         => element.getContainingFile
    Option(file)
      .flatMap(value => Option(value.getVirtualFile))
      .foreach(virtual =>
        new OpenFileDescriptor(project, virtual, dependency.offset)
          .navigate(true)
      )
