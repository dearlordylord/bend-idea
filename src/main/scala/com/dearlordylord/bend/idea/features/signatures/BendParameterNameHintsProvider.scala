package com.dearlordylord.bend.idea.features.signatures

import com.dearlordylord.bend.idea.symbols.api.{
  BendApplicationKind,
  BendSourceApplications,
  BendSourceCallSignatures
}
import com.dearlordylord.bend.idea.syntax.psi.BendReferenceElement
import com.intellij.codeInsight.hints.{InlayInfo, InlayParameterHintsProvider}
import com.intellij.psi.{PsiElement, PsiFile}
import scala.jdk.CollectionConverters.*

/** Optional source-name hints for calls whose declaration resolves uniquely. */
final class BendParameterNameHintsProvider extends InlayParameterHintsProvider:
  override def getParameterHints(
      element: PsiElement,
      file: PsiFile
  ): java.util.List[InlayInfo] =
    element match
      case _: BendReferenceElement if file != null =>
        BendSourceApplications
          .forCallee(file, element.getTextOffset)
          .filter(application =>
            application.kind == BendApplicationKind.Function ||
              application.kind == BendApplicationKind.Constructor
          )
          .toList
          .flatMap(application =>
            BendSourceCallSignatures.hints(file, application).map {
              case (name, offset) => new InlayInfo(s"$name:", offset)
            }
          )
          .asJava
      case _ => java.util.Collections.emptyList[InlayInfo]()

  override def getDefaultBlackList: java.util.Set[String] =
    java.util.Collections.emptySet[String]()

  override def getMainCheckboxText: String = "Show Bend parameter names"

  override def getDescription: String =
    "Show source parameter names for uniquely resolved Bend calls."

  override def getSettingsPreview: String = "combine(add: 1, scale: 2)"
