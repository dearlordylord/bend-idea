package com.dearlordylord.bend.idea.features.completion

import com.dearlordylord.bend.idea.symbols.api.{
  BendWorkspaceSymbolCandidate,
  BendWorkspaceSymbolSearch
}
import com.dearlordylord.bend.idea.syntax.BendLanguage
import com.dearlordylord.bend.idea.syntax.psi.{
  BendDeclaration,
  BendReferenceElement
}
import com.intellij.codeInsight.hint.HintManager
import com.intellij.openapi.actionSystem.{
  ActionUpdateThread,
  AnAction,
  AnActionEvent,
  CommonDataKeys
}
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import scala.jdk.CollectionConverters.*

/** Deliberate workspace import; ordinary completion remains name-only. */
final class BendExplicitImportAction
    extends AnAction("Import Bend Symbol at Caret"):
  override def getActionUpdateThread: ActionUpdateThread =
    ActionUpdateThread.BGT

  override def update(event: AnActionEvent): Unit =
    val editor = event.getData(CommonDataKeys.EDITOR)
    val file = event.getData(CommonDataKeys.PSI_FILE)
    event.getPresentation.setEnabledAndVisible(
      editor != null && file != null &&
        file.getLanguage == BendLanguage.instance &&
        referenceAt(file, editor.getCaretModel.getOffset).nonEmpty
    )

  override def actionPerformed(event: AnActionEvent): Unit =
    val project = event.getProject
    val editor = event.getData(CommonDataKeys.EDITOR)
    val file = event.getData(CommonDataKeys.PSI_FILE)
    if project == null || editor == null || file == null then return
    referenceAt(file, editor.getCaretModel.getOffset) match
      case None => hint(editor, "Place the caret on an unresolved Bend name")
      case Some(reference) =>
        val name = reference.getText
        BendWorkspaceSymbolSearch.named(project, name) match
          case Left(reason) => hint(editor, reason)
          case Right(all)   =>
            val current =
              com.dearlordylord.bend.idea.symbols.api.BendSourceSymbols
                .fileId(file)
            val candidates = all.filter(candidate =>
              candidate.isBase || candidate.symbol.handle.file != current
            )
            candidates match
              case Nil =>
                hint(editor, "No other Bend declaration has this name")
              case one :: Nil =>
                insert(project, editor, file, reference, one)
              case many =>
                JBPopupFactory
                  .getInstance()
                  .createPopupChooserBuilder(many.asJava)
                  .setTitle("Choose Bend declaration to import")
                  .setItemChosenCallback(candidate =>
                    insert(project, editor, file, reference, candidate)
                  )
                  .createPopup()
                  .showInBestPositionFor(editor)

  private def insert(
      project: Project,
      editor: Editor,
      file: PsiFile,
      reference: BendReferenceElement,
      candidate: BendWorkspaceSymbolCandidate
  ): Unit =
    BendExplicitImportPlanner
      .plan(file, reference, candidate)
      .flatMap(plan =>
        BendExplicitImportPlanner.insert(project, file, plan)
      ) match
      case Left(reason)  => hint(editor, reason)
      case Right(result) =>
        editor.getCaretModel.moveToOffset(result.referenceEnd)

  private def referenceAt(
      file: PsiFile,
      offset: Int
  ): Option[BendReferenceElement] =
    val positions = List(offset, offset - 1).filter(pos =>
      pos >= 0 && pos < file.getTextLength
    )
    positions.iterator
      .flatMap(position =>
        Option(file.findElementAt(position)).flatMap(element =>
          Option(
            PsiTreeUtil.getParentOfType(
              element,
              classOf[BendReferenceElement]
            )
          )
        )
      )
      .find { reference =>
        val declaration = PsiTreeUtil.getParentOfType(
          reference,
          classOf[BendDeclaration]
        )
        declaration == null ||
        declaration.getNameIdentifier != reference
      }

  private def hint(editor: Editor, message: String): Unit =
    HintManager.getInstance().showInformationHint(editor, message)
