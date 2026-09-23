package com.dearlordylord.bend.idea.features.checking

import com.dearlordylord.bend.idea.analysis.api.BendCheckService
import com.dearlordylord.bend.idea.analysis.model.*
import com.dearlordylord.bend.idea.model.FileId
import com.dearlordylord.bend.idea.toolchain.api.BendToolchainSettings
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.codeInsight.hint.HintManager
import com.intellij.openapi.actionSystem.{AnAction, AnActionEvent, CommonDataKeys}
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.{ProgressIndicator, ProgressManager, Task}
import com.intellij.openapi.editor.event.{DocumentEvent, DocumentListener}

/** Checks the current document explicitly; no editor pass invokes Bend. */
final class BendCheckCurrentFileAction extends AnAction("Check Current Bend File"):
  override def update(event: AnActionEvent): Unit =
    val file = event.getData(CommonDataKeys.VIRTUAL_FILE)
    event.getPresentation.setEnabledAndVisible(file != null && file.getName.endsWith(".bend") &&
      event.getData(CommonDataKeys.EDITOR) != null)

  override def actionPerformed(event: AnActionEvent): Unit =
    val editor = event.getData(CommonDataKeys.EDITOR)
    val file = event.getData(CommonDataKeys.VIRTUAL_FILE)
    val project = event.getData(CommonDataKeys.PROJECT)
    if editor == null || file == null || project == null then return
    val document = editor.getDocument
    val id = new FileId(Option(file.getCanonicalPath).getOrElse(file.getPath),
      file.getCanonicalPath != null)
    val snapshot = BendCheckSnapshot(id, file.getPath, document.getText,
      document.getModificationStamp,
      ApplicationManager.getApplication.getService(classOf[BendToolchainSettings]).selection)
    val service = project.getService(classOf[BendCheckService])
    val started = service.begin(snapshot, callback => {
      val listener = new DocumentListener:
        override def documentChanged(event: DocumentEvent): Unit = callback()
      document.addDocumentListener(listener)
      () => document.removeDocumentListener(listener)
    }, () => document.getModificationStamp == snapshot.sourceRevision)
    if !started then
      HintManager.getInstance().showInformationHint(editor, "A Bend check is already running")
      return
    DaemonCodeAnalyzer.getInstance(project).restart()
    ProgressManager.getInstance().run(new Task.Backgroundable(project, "Checking Bend", true):
      override def onCancel(): Unit = service.cancel(id)
      override def run(indicator: ProgressIndicator): Unit =
        val checked = service.check(snapshot, () => indicator.isCanceled || project.isDisposed)
        ApplicationManager.getApplication.invokeLater(() => {
          if !project.isDisposed && document.getModificationStamp == snapshot.sourceRevision then
            checked.foreach { result =>
              DaemonCodeAnalyzer.getInstance(project).restart()
              HintManager.getInstance().showInformationHint(editor, result.status)
            }
        })
    )
