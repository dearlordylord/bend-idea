package com.dearlordylord.bend.idea.features.checking

import com.dearlordylord.bend.idea.analysis.api.BendCheckService
import com.dearlordylord.bend.idea.analysis.model.BendCheckResult
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile

final class BendLintRuleDocumentation(result: BendCheckResult, link: String)
    extends IntentionAction:
  override def getText: String = "Open bend-lint rule documentation"
  override def getFamilyName: String = "Bend lint diagnostics"
  override def startInWriteAction(): Boolean = false
  override def isAvailable(
      project: Project,
      editor: Editor,
      file: PsiFile
  ): Boolean =
    project.getService(classOf[BendCheckService]).isCurrent(result)
  override def invoke(project: Project, editor: Editor, file: PsiFile): Unit =
    if isAvailable(project, editor, file) then BrowserUtil.browse(link)
