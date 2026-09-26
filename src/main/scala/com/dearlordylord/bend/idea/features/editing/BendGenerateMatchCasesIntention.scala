package com.dearlordylord.bend.idea.features.editing

import com.dearlordylord.bend.idea.syntax.BendLanguage
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile

/** Exposes safe match-case generation in IDEA's standard context-action menu.
  */
final class BendGenerateMatchCasesIntention extends IntentionAction:
  override def getText: String = "Generate Match Cases"
  override def getFamilyName: String = "Bend match cases"

  override def isAvailable(
      project: Project,
      editor: Editor,
      file: PsiFile
  ): Boolean =
    project != null && editor != null && file != null &&
      file.getLanguage == BendLanguage.instance &&
      BendMatchSkeletonGenerator
        .plan(file, editor.getCaretModel.getOffset)
        .nonEmpty

  override def invoke(project: Project, editor: Editor, file: PsiFile): Unit =
    val _ = BendGenerateMatchCases.invoke(project, editor, file)

  override def startInWriteAction: Boolean = false
