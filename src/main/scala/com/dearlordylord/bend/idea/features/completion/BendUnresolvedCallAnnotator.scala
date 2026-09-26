package com.dearlordylord.bend.idea.features.completion

import com.dearlordylord.bend.idea.symbols.api.BendSourceApplications
import com.dearlordylord.bend.idea.syntax.BendLanguage
import com.dearlordylord.bend.idea.syntax.psi.{
  BendDeclaration,
  BendReferenceElement
}
import com.intellij.lang.annotation.{
  AnnotationHolder,
  Annotator,
  HighlightSeverity
}
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi.{PsiElement, PsiFile}
import com.intellij.psi.util.PsiTreeUtil

/** Marks unresolved named applications and offers the explicit-import fix. */
final class BendUnresolvedCallAnnotator extends Annotator:
  private val MaxSourceLength = 1024 * 1024

  override def annotate(element: PsiElement, holder: AnnotationHolder): Unit =
    element match
      case file: PsiFile
          if file.getLanguage == BendLanguage.instance &&
            file.getTextLength <= MaxSourceLength =>
        BendSourceApplications.namedCalls(file).foreach { call =>
          ProgressManager.checkCanceled()
          val reference = Option(file.findElementAt(call.calleeFrom))
            .flatMap(leaf =>
              Option(
                PsiTreeUtil.getParentOfType(
                  leaf,
                  classOf[BendReferenceElement],
                  false
                )
              )
            )
            .filter(_.getTextOffset == call.calleeFrom)
          val isDeclaration = reference.exists { value =>
            val declaration = PsiTreeUtil.getParentOfType(
              value,
              classOf[BendDeclaration],
              false
            )
            declaration != null && declaration.getNameIdentifier == value
          }
          val unresolved =
            reference.filterNot(_ => isDeclaration).filter { value =>
              val references = value.getReferences
              references.nonEmpty && references.forall(_.resolve() == null)
            }
          unresolved.foreach { value =>
            val annotation = holder
              .newAnnotation(
                HighlightSeverity.ERROR,
                s"Unresolved Bend name: ${call.callee}"
              )
              .range(TextRange.create(call.calleeFrom, call.calleeUntil))
            val withImportFix =
              if !call.callee.contains(".") then
                annotation.withFix(new BendExplicitImportIntention)
              else annotation
            val _ = withImportFix.create()
          }
        }
      case _ => ()
