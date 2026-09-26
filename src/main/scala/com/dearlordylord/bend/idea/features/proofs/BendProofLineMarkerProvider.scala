package com.dearlordylord.bend.idea.features.proofs

import com.dearlordylord.bend.idea.symbols.api.BendSourceSymbols
import com.dearlordylord.bend.idea.syntax.BendFileType
import com.dearlordylord.bend.idea.syntax.psi.{
  BendDeclaration,
  BendDefinition,
  BendLaw,
  BendName
}
import com.intellij.codeInsight.daemon.{
  GutterIconNavigationHandler,
  LineMarkerInfo,
  LineMarkerProvider
}
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import java.awt.event.MouseEvent

/** Gutter affordances resolve candidates only when clicked, not on every edit.
  */
final class BendProofLineMarkerProvider extends LineMarkerProvider:
  override def getLineMarkerInfo(
      element: PsiElement
  ): LineMarkerInfo[? <: PsiElement] =
    val name = PsiTreeUtil.getParentOfType(element, classOf[BendName])
    if name == null || name.getFirstChild != element then return null
    val declaration = PsiTreeUtil.getParentOfType(
      name,
      classOf[BendDeclaration]
    )
    val tooltip = declaration match
      case _: BendLaw =>
        Some(
          "Find candidate fills in selected proof roots; source presence is not proof success"
        )
      case definition: BendDefinition if isPotentialFill(definition) =>
        Some(
          "Find the matching law in selected proof roots; source presence is not proof success"
        )
      case _ => None
    tooltip.map { text =>
      new LineMarkerInfo[PsiElement](
        element,
        element.getTextRange,
        new BendFileType().getIcon,
        (_: PsiElement) => text,
        new GutterIconNavigationHandler[PsiElement]:
          override def navigate(
              event: MouseEvent,
              clicked: PsiElement
          ): Unit = BendProofNavigation.openFromGutter(clicked)
        ,
        GutterIconRenderer.Alignment.LEFT,
        () => "Bend law and proof navigation"
      )
    }.orNull

  private def isPotentialFill(definition: BendDefinition): Boolean =
    val parameters = definition.parameters
    val header = definition.headerText
    val file = definition.getContainingFile
    val sameFileFill = BendSourceSymbols
      .declarations(file)
      .find(_.handle.nameOffset == definition.getNameIdentifier.getTextOffset)
      .exists(BendSourceSymbols.isLocalLawFill(file, _))
    val qualifiedFill =
      definition.getName != null && definition.getName.contains(".")
    !header.contains("->") && parameters.forall(p => p.source == p.name) &&
    (sameFileFill || qualifiedFill)
