package com.dearlordylord.bend.idea.features.semantics

import com.dearlordylord.bend.idea.analysis.api.{
  BendCheckService,
  BendExplicitCheckOutcome,
  BendExplicitCheckRunner,
  BendGoalAvailability,
  BendGoalQuery,
  BendProofEditValidator,
  BendResourceCandidateStatus,
  BendResourceReport,
  BendResourceReportOutcome
}
import com.dearlordylord.bend.idea.analysis.model.BendReliance
import com.dearlordylord.bend.idea.model.FileId
import com.intellij.codeInsight.hint.HintManager
import com.intellij.openapi.actionSystem.{
  ActionUpdateThread,
  AnAction,
  AnActionEvent,
  CommonDataKeys
}
import com.intellij.openapi.editor.Editor

/** Explains the compiler's bounded whole-root candidate judgments at the first
  * reachable named hole. No occurrence counting or source mutation.
  */
final class BendExplainResourcesAction
    extends AnAction("Explain Bend Resources"):
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
      info(editor, "Select one Bend root before checking resources")
      return
    val root = roots.headOption.getOrElse(file.getPath)
    def current: Boolean =
      !editor.isDisposed && document.getModificationStamp == revision &&
        editor.getCaretModel.getOffset == offset
    project
      .getService(classOf[BendExplicitCheckRunner])
      .checkGoal(root, "Checking Bend resources") {
        case BendExplicitCheckOutcome.Published(result) if current =>
          BendGoalQuery.at(
            result,
            id,
            document.getText,
            revision,
            offset
          ) match
            case BendGoalAvailability.Available(goal) =>
              project
                .getService(classOf[BendProofEditValidator])
                .probeGoalResources(result, goal) {
                  case BendResourceReportOutcome.Available(report)
                      if current && checkService.isCurrent(result) =>
                    info(editor, BendExplainResourcesAction.render(report))
                  case BendResourceReportOutcome.Unavailable(reason)
                      if current =>
                    info(editor, reason)
                  case _ => ()
                }
            case BendGoalAvailability.Unavailable(reason) =>
              info(editor, reason)
        case BendExplicitCheckOutcome.Rejected(_, reason) if current =>
          info(editor, reason)
        case _ => ()
      }

  private def info(editor: Editor, value: String): Unit =
    HintManager.getInstance().showInformationHint(editor, value)

object BendExplainResourcesAction:
  private def escaped(value: String): String =
    value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

  private[semantics] def render(report: BendResourceReport): String =
    val lines = report.bindings.map { binding =>
      val quantity = binding.quantity match
        case "-" => "erased"
        case "+" => "reusable"
        case ""  => "affine"
        case _   => "quantity unavailable"
      val status = binding.status match
        case BendResourceCandidateStatus.AcceptedComplete(reliance) =>
          val relied = if reliance == BendReliance.UnsafeOrForeign then
            " (root relies on unsafe or foreign code)"
          else ""
          "Bend accepts this replacement in the complete root" + relied
        case BendResourceCandidateStatus.AcceptedUntilTodo =>
          "Bend accepts up to a later TODO; root remains incomplete"
        case BendResourceCandidateStatus.Rejected(details) =>
          "Bend rejects this replacement: " + details
        case BendResourceCandidateStatus.NotTypeCompatible =>
          "not type compatible with this goal"
        case BendResourceCandidateStatus.NotChecked =>
          "whole-root availability unavailable"
      s"${escaped(binding.name)} ($quantity): ${escaped(status)}"
    }
    val cap = if report.capped then
      "<br>Only the first eight compatible binders were probed."
    else ""
    "<html><b>Compiler resource probes at this goal</b><br>" +
      lines.mkString("<br>") + cap +
      "<br>A rejected replacement may have a non-resource cause.</html>"
