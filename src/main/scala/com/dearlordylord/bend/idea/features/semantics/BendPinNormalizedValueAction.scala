package com.dearlordylord.bend.idea.features.semantics

import com.intellij.openapi.actionSystem.{
  ActionUpdateThread,
  AnAction,
  AnActionEvent,
  CommonDataKeys
}

/** Explicit opt-in normalization presentation; never evaluates while typing. */
final class BendPinNormalizedValueAction
    extends AnAction("Pin Normalized Bend Value"):
  override def getActionUpdateThread: ActionUpdateThread =
    ActionUpdateThread.EDT

  override def update(event: AnActionEvent): Unit =
    val file = event.getData(CommonDataKeys.VIRTUAL_FILE)
    val editor = event.getData(CommonDataKeys.EDITOR)
    val project = event.getProject
    val available = project != null && file != null && file.getName.endsWith(
      ".bend"
    ) && editor != null
    event.getPresentation.setEnabledAndVisible(available)
    event.getPresentation.setText(
      if available && project
          .getService(classOf[BendNormalizationInlays])
          .pinnedAtCaret(editor)
      then "Unpin Normalized Bend Value"
      else "Pin Normalized Bend Value"
    )

  override def actionPerformed(event: AnActionEvent): Unit =
    val editor = event.getData(CommonDataKeys.EDITOR)
    val file = event.getData(CommonDataKeys.VIRTUAL_FILE)
    val project = event.getProject
    if editor != null && file != null && project != null then
      project.getService(classOf[BendNormalizationInlays]).toggle(editor, file)
