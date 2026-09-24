package com.dearlordylord.bend.idea.features.checking

import com.dearlordylord.bend.idea.analysis.api.BendCheckService
import com.dearlordylord.bend.idea.analysis.model.*
import com.dearlordylord.bend.idea.analysis.api.BendGraphSnapshot
import com.dearlordylord.bend.idea.model.FileId
import com.dearlordylord.bend.idea.workspace.api.{BendImportLines, BendWorkspaceGraph}
import com.dearlordylord.bend.idea.workspace.model.BendSourceRecord
import com.dearlordylord.bend.idea.toolchain.api.BendToolchainSettings
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
    val path = file.getPath
    val proof = file.getName == "PROOF.bend"
    val id = new FileId(Option(file.getCanonicalPath).getOrElse(file.getPath),
      file.getCanonicalPath != null)
    val selection = ApplicationManager.getApplication.getService(classOf[BendToolchainSettings]).selection
    val initial = BendCheckSnapshot(id, path, document.getText,
      document.getModificationStamp,
      selection)
    val service = project.getService(classOf[BendCheckService])
    ProgressManager.getInstance().run(new Task.Backgroundable(project, "Checking Bend", true):
      override def onCancel(): Unit = service.cancel(id)
      override def run(indicator: ProgressIndicator): Unit =
        val reservation = service.begin(initial, callback => {
          val listener = new DocumentListener:
            override def documentChanged(event: DocumentEvent): Unit = callback()
          document.addDocumentListener(listener)
          () => document.removeDocumentListener(listener)
        }, () => document.getModificationStamp == initial.sourceRevision)
        if reservation.isEmpty then
          ApplicationManager.getApplication.invokeLater(() => {
            if !project.isDisposed then
              HintManager.getInstance().showInformationHint(editor, "A Bend check is already running")
          })
          return
        val rootSource = BendSourceRecord(id, path, initial.text, initial.sourceRevision,
          BendImportLines.parse(initial.text))
        val graph = project.getService(classOf[BendWorkspaceGraph]).load(rootSource,
          selection.baseSource, selection.packageCache,
          () => indicator.isCanceled || project.isDisposed)
        val siblingLaws = if proof then
          project.getService(classOf[BendWorkspaceGraph]).siblingLaws(path)
        else None
        val snapshot = BendGraphSnapshot.attach(initial, graph, siblingLaws)
        val checked = service.check(snapshot, reservation.get,
          () => indicator.isCanceled || project.isDisposed)
        ApplicationManager.getApplication.invokeLater(() => {
          if !project.isDisposed && document.getModificationStamp == initial.sourceRevision then
            checked.foreach { result =>
              HintManager.getInstance().showInformationHint(editor, result.status)
            }
        })
    )
