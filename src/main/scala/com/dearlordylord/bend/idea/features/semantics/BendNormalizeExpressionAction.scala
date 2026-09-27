package com.dearlordylord.bend.idea.features.semantics

import com.dearlordylord.bend.idea.analysis.api.{
  BendCheckService,
  BendExplicitCheckOutcome,
  BendExplicitCheckRunner
}
import com.dearlordylord.bend.idea.analysis.model.BendNormalizationRequest
import com.dearlordylord.bend.idea.model.FileId
import com.intellij.codeInsight.hint.HintManager
import com.intellij.openapi.actionSystem.{
  ActionUpdateThread,
  AnAction,
  AnActionEvent,
  CommonDataKeys
}

/** Displays a bounded compiler normalization for a closed checked expression.
  */
final class BendNormalizeExpressionAction
    extends AnAction("Normalize Bend Expression"):
  override def getActionUpdateThread: ActionUpdateThread =
    ActionUpdateThread.BGT

  override def update(event: AnActionEvent): Unit =
    val file = event.getData(CommonDataKeys.VIRTUAL_FILE)
    event.getPresentation.setEnabledAndVisible(
      file != null && file.getName.endsWith(".bend") &&
        event.getData(CommonDataKeys.EDITOR) != null
    )

  override def actionPerformed(event: AnActionEvent): Unit =
    val editor = event.getData(CommonDataKeys.EDITOR)
    val file = event.getData(CommonDataKeys.VIRTUAL_FILE)
    val project = event.getProject
    if editor == null || file == null || project == null then return
    val document = editor.getDocument
    val revision = document.getModificationStamp
    val offset = editor.getCaretModel.getOffset
    val id = new FileId(
      Option(file.getCanonicalPath).getOrElse(file.getPath),
      file.getCanonicalPath != null
    )
    val checkService = project.getService(classOf[BendCheckService])
    val roots = checkService.resultsFor(id).map(_.key.root.value).distinct
    if roots.size > 1 then
      info(editor, "Select one Bend root before normalizing")
      return
    val root = roots.headOption.getOrElse(file.getPath)
    project
      .getService(classOf[BendExplicitCheckRunner])
      .checkNormalization(
        root,
        BendNormalizationRequest(id, offset),
        "Normalizing Bend expression"
      ) {
        case BendExplicitCheckOutcome.Published(result)
            if !editor.isDisposed && document.getModificationStamp == revision &&
              editor.getCaretModel.getOffset == offset &&
              checkService.isCurrent(result) &&
              result.sources.exists(source =>
                source.id == id && source.text == document.getText &&
                  source.revision == revision
              ) =>
          result.normalization match
            case Some(normal)
                if normal.source == id && normal.range.start <= offset &&
                  offset < normal.range.end =>
              val escaped = normal.text
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
              info(
                editor,
                s"<html><b>Normalized by Bend</b><br><pre>$escaped</pre></html>"
              )
            case _ =>
              info(
                editor,
                "Normalization unavailable here: select a closed, checked expression with an exact compiler span"
              )
        case BendExplicitCheckOutcome.Rejected(_, reason)
            if !editor.isDisposed && document.getModificationStamp == revision =>
          info(editor, reason)
        case _ => ()
      }

  private def info(
      editor: com.intellij.openapi.editor.Editor,
      text: String
  ): Unit =
    HintManager.getInstance().showInformationHint(editor, text)
