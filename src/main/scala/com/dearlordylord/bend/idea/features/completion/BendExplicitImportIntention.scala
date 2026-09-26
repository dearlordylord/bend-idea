package com.dearlordylord.bend.idea.features.completion

import com.dearlordylord.bend.idea.syntax.BendLanguage
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile

/** The standard Context Actions entry point for an unresolved Bend call. */
final class BendExplicitImportIntention extends IntentionAction:
  private val flow = new BendExplicitImportFlow

  override def getText: String = "Import Bend symbol"
  override def getFamilyName: String = "Bend imports"

  override def isAvailable(
      project: Project,
      editor: Editor,
      file: PsiFile
  ): Boolean =
    file != null && editor != null &&
      file.getLanguage == BendLanguage.instance &&
      flow
        .referenceAt(file, editor.getCaretModel.getOffset)
        .exists(reference =>
          !reference.getText.contains(".") && flow.isUnresolved(reference)
        )

  override def invoke(project: Project, editor: Editor, file: PsiFile): Unit =
    if project == null || editor == null || file == null then return
    flow
      .referenceAt(file, editor.getCaretModel.getOffset)
      .filter(reference =>
        !reference.getText.contains(".") && flow.isUnresolved(reference)
      )
      .foreach(reference => flow.importAt(project, editor, file, reference))

  override def startInWriteAction: Boolean = false
