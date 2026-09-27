package com.dearlordylord.bend.idea.features.semantics

import com.dearlordylord.bend.idea.analysis.model.BendGoal
import com.dearlordylord.bend.idea.features.semantics.api.{
  BendCurrentLocationInquiry,
  BendInquiryOutcome
}
import com.intellij.codeInsight.hint.HintManager
import com.intellij.openapi.actionSystem.{
  ActionUpdateThread,
  AnAction,
  AnActionEvent,
  CommonDataKeys
}

/** Explicitly checks the selected root and displays a compiler-provided goal
  * only when the caret is inside its current source range.
  */
final class BendInspectGoalAction extends AnAction("Inspect Bend Goal"):
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
    project
      .getService(classOf[BendCurrentLocationInquiry])
      .goal(editor, file, "Inspecting Bend goal") {
        case BendInquiryOutcome.Available(_, _, goal) =>
          HintManager
            .getInstance()
            .showInformationHint(
              editor,
              BendInspectGoalAction.render(goal)
            )
        case BendInquiryOutcome.Unavailable(reason) =>
          HintManager.getInstance().showInformationHint(editor, reason)
        case BendInquiryOutcome.AmbiguousRoots =>
          HintManager
            .getInstance()
            .showInformationHint(
              editor,
              "Goal unavailable: this source belongs to multiple checked roots. Check one root and retry."
            )
      }

object BendInspectGoalAction:
  private def escaped(value: String): String =
    value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

  private[semantics] def render(goal: BendGoal): String =
    val bindings = goal.context.map(binding =>
      s"${escaped(binding.quantity)}${escaped(binding.name)} : ${escaped(binding.typeText)}"
    )
    val context =
      if bindings.isEmpty then ""
      else "<br><b>Context</b><br>" + bindings.mkString("<br>")
    s"<html><b>?${escaped(goal.holeName)}</b> : ${escaped(goal.expectedType)}$context</html>"
