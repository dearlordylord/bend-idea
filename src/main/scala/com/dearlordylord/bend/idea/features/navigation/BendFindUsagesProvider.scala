package com.dearlordylord.bend.idea.features.navigation

import com.dearlordylord.bend.idea.symbols.api.BendSourceSymbols
import com.dearlordylord.bend.idea.syntax.BendLanguage
import com.dearlordylord.bend.idea.syntax.psi.BendDeclaration
import com.intellij.lang.findUsages.FindUsagesProvider
import com.intellij.psi.PsiElement

/** Makes native Bend declarations and local binding names available to Find Usages. */
final class BendFindUsagesProvider extends FindUsagesProvider:
  override def canFindUsagesFor(element: PsiElement): Boolean =
    Option(element.getContainingFile).exists(_.getLanguage == BendLanguage.instance) &&
      (element match
        case declaration: BendDeclaration => declaration.getNameIdentifier != null
        case _ => element.getText.matches("[A-Za-z_][A-Za-z0-9_.]*"))
  override def getHelpId(element: PsiElement): String = null
  override def getType(element: PsiElement): String =
    val file = element.getContainingFile
    if file == null then "symbol"
    else
      val name = element match
        case declaration: BendDeclaration => declaration.getNameIdentifier
        case _ => element
      BendSourceSymbols.declarations(file).find(_.handle.nameOffset == name.getTextOffset)
      .map(_.category.toString.toLowerCase).getOrElse("binding")
  override def getDescriptiveName(element: PsiElement): String = element match
    case declaration: BendDeclaration => declaration.getName
    case _ => element.getText
  override def getNodeText(element: PsiElement, useFullName: Boolean): String =
    getDescriptiveName(element)
