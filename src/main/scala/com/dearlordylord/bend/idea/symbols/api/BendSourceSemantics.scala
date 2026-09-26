package com.dearlordylord.bend.idea.symbols.api

import com.dearlordylord.bend.idea.syntax.psi.*
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil

/** A source-backed category suitable for editor presentation. */
enum BendSemanticCategory:
  case Alias, Definition, Datatype, Law, Constructor, Parameter, Local

final case class BendSemanticReference(
    category: BendSemanticCategory,
    rangeInElement: TextRange
)

/** Resolves semantic presentation through the shared source identity owner. */
object BendSourceSemantics:
  def classifications(
      element: BendReferenceElement
  ): List[BendSemanticReference] =
    val file = element.getContainingFile
    if file == null then Nil
    else
      element.getReferences.iterator.flatMap { reference =>
        Option(reference.resolve()).flatMap { target =>
          classifyTarget(file, element, target).map(category =>
            BendSemanticReference(category, reference.getRangeInElement)
          )
        }
      }.toList

  private def classifyTarget(
      file: com.intellij.psi.PsiFile,
      element: BendReferenceElement,
      target: PsiElement
  ): Option[BendSemanticCategory] =
    target match
      case _: BendAlias                 => Some(BendSemanticCategory.Alias)
      case declaration: BendDeclaration => declarationCategory(declaration)
      case name: BendName               =>
        Option(
          PsiTreeUtil.getParentOfType(name, classOf[BendDeclaration], true)
        )
          .flatMap(declarationCategory)
      case _: BendReferenceElement =>
        BendSourceSymbols.resolveCurrentFile(
          file,
          element.getTextOffset,
          element.getText,
          Some(BendSymbolCategory.Binder)
        ) match
          case BendSourceResolution.ResolvedBinder(binding) =>
            Some(
              if binding.origin == BendBindingKind.Parameter then
                BendSemanticCategory.Parameter
              else BendSemanticCategory.Local
            )
          case _ => None
      case _ => None

  private def declarationCategory(
      declaration: BendDeclaration
  ): Option[BendSemanticCategory] =
    val category = declaration match
      case _: BendDefinition  => Some(BendSemanticCategory.Definition)
      case _: BendDatatype    => Some(BendSemanticCategory.Datatype)
      case _: BendLaw         => Some(BendSemanticCategory.Law)
      case _: BendConstructor => Some(BendSemanticCategory.Constructor)
    category
