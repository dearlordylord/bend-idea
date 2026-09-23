package com.dearlordylord.bend.idea.features.checking

import com.dearlordylord.bend.idea.analysis.api.BendCheckService
import com.dearlordylord.bend.idea.analysis.model.*
import com.dearlordylord.bend.idea.analysis.api.BendGraphSnapshot
import com.dearlordylord.bend.idea.model.FileId
import com.dearlordylord.bend.idea.workspace.api.{BendImportLines, BendWorkspaceGraph}
import com.dearlordylord.bend.idea.workspace.model.BendSourceRecord
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
    val selection = ApplicationManager.getApplication.getService(classOf[BendToolchainSettings]).selection
    val initial = BendCheckSnapshot(id, file.getPath, document.getText,
      document.getModificationStamp,
      selection)
    val service = project.getService(classOf[BendCheckService])
    val started = service.begin(initial, callback => {
      val listener = new DocumentListener:
        override def documentChanged(event: DocumentEvent): Unit = callback()
      document.addDocumentListener(listener)
      () => document.removeDocumentListener(listener)
    }, () => document.getModificationStamp == initial.sourceRevision)
    if !started then
      HintManager.getInstance().showInformationHint(editor, "A Bend check is already running")
      return
    DaemonCodeAnalyzer.getInstance(project).restart()
    ProgressManager.getInstance().run(new Task.Backgroundable(project, "Checking Bend", true):
      override def onCancel(): Unit = service.cancel(id)
      override def run(indicator: ProgressIndicator): Unit =
        val rootSource = BendSourceRecord(id, file.getPath, initial.text, initial.sourceRevision,
          BendImportLines.parse(initial.text))
        val graph = project.getService(classOf[BendWorkspaceGraph]).load(rootSource,
          selection.baseSource, selection.packageCache,
          () => indicator.isCanceled || project.isDisposed)
        val siblingLaws = if file.getName == "PROOF.bend" then
          project.getService(classOf[BendWorkspaceGraph]).siblingLaws(file.getPath)
        else None
        val snapshot = BendGraphSnapshot.attach(initial, graph, siblingLaws)
        val checked = service.check(snapshot, () => indicator.isCanceled || project.isDisposed)
        ApplicationManager.getApplication.invokeLater(() => {
          if !project.isDisposed && document.getModificationStamp == initial.sourceRevision then
            checked.foreach { result =>
              DaemonCodeAnalyzer.getInstance(project).restart()
              HintManager.getInstance().showInformationHint(editor, result.status)
            }
        })
    )
