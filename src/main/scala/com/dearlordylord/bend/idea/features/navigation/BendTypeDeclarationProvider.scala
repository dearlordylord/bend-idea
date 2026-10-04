package com.dearlordylord.bend.idea.features.navigation

import com.dearlordylord.bend.idea.symbols.api.BendSourceTypeTargets
import com.dearlordylord.bend.idea.syntax.BendLanguage
import com.intellij.codeInsight.navigation.actions.TypeDeclarationProvider
import com.intellij.psi.PsiElement

final class BendTypeDeclarationProvider extends TypeDeclarationProvider:
  override def getSymbolTypeDeclarations(
      element: PsiElement
  ): Array[PsiElement] =
    if element.getLanguage != BendLanguage.instance then null
    else BendSourceTypeTargets.declarations(element).toArray
