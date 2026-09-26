package com.dearlordylord.bend.idea.features.spelling

import com.dearlordylord.bend.idea.syntax.BendLanguage
import com.dearlordylord.bend.idea.syntax.lexer.BendTokens
import com.dearlordylord.bend.idea.syntax.psi.BendForeignPaths
import com.dearlordylord.bend.idea.syntax.psi.BendDefinition
import com.intellij.psi.{PsiComment, PsiElement}
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.spellchecker.tokenizer.{SpellcheckingStrategy, Tokenizer}

/** Checks natural-language comments and ordinary string text using IDE
  * dictionaries, splitters and correction actions. Lexical escapes and code
  * tokens never enter a spellchecking tokenizer.
  */
final class BendSpellcheckingStrategy extends SpellcheckingStrategy:
  override def getTokenizer(element: PsiElement): Tokenizer[? <: PsiElement] =
    if element == null || element.getNode == null ||
      element.getContainingFile == null ||
      element.getContainingFile.getLanguage != BendLanguage.instance
    then SpellcheckingStrategy.EMPTY_TOKENIZER
    else
      element.getNode.getElementType match
        case BendTokens.Comment =>
          element match
            case comment: PsiComment => myCommentTokenizer
            case _                   => SpellcheckingStrategy.EMPTY_TOKENIZER
        case BendTokens.StringContent =>
          if isForeignPathText(element) then
            SpellcheckingStrategy.EMPTY_TOKENIZER
          else SpellcheckingStrategy.TEXT_TOKENIZER
        case _ => SpellcheckingStrategy.EMPTY_TOKENIZER

  override def isMyContext(element: PsiElement): Boolean =
    element != null && element.getNode != null &&
      element.getContainingFile != null &&
      element.getContainingFile.getLanguage == BendLanguage.instance &&
      (element.getNode.getElementType == BendTokens.Comment ||
        element.getNode.getElementType == BendTokens.StringContent)

  private def isForeignPathText(element: PsiElement): Boolean =
    val range = element.getTextRange
    Option(
      PsiTreeUtil.getParentOfType(element, classOf[BendDefinition], false)
    ).exists(definition =>
      BendForeignPaths
        .inDefinition(definition)
        .exists(_.range.intersects(range))
    )
