package com.dearlordylord.bend.idea.features.editing

import com.dearlordylord.bend.idea.symbols.api.*
import com.dearlordylord.bend.idea.syntax.lexer.BendColors
import com.dearlordylord.bend.idea.syntax.psi.BendReferenceElement
import com.intellij.lang.annotation.{
  AnnotationHolder,
  Annotator,
  HighlightSeverity
}
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.psi.PsiElement

/** Adds category colors only when the shared source resolver returns one
  * target.
  */
final class BendSemanticAnnotator extends Annotator:
  override def annotate(element: PsiElement, holder: AnnotationHolder): Unit =
    element match
      case referenceElement: BendReferenceElement =>
        BendSourceSemantics
          .classifications(referenceElement)
          .foreach { classification =>
            colorKey(classification.category).foreach { key =>
              val _ = holder
                .newSilentAnnotation(HighlightSeverity.INFORMATION)
                .range(
                  classification.rangeInElement.shiftRight(
                    referenceElement.getTextOffset
                  )
                )
                .textAttributes(key)
                .create()
            }
          }
      case _ => ()

  private def colorKey(
      category: BendSemanticCategory
  ): Option[TextAttributesKey] =
    category match
      case BendSemanticCategory.Alias      => Some(BendColors.SemanticAlias)
      case BendSemanticCategory.Definition =>
        Some(BendColors.SemanticDefinition)
      case BendSemanticCategory.Datatype    => Some(BendColors.SemanticDatatype)
      case BendSemanticCategory.Law         => Some(BendColors.SemanticLaw)
      case BendSemanticCategory.Constructor =>
        Some(BendColors.SemanticConstructor)
      case BendSemanticCategory.Parameter => Some(BendColors.SemanticParameter)
      case BendSemanticCategory.Local     => Some(BendColors.SemanticLocal)
