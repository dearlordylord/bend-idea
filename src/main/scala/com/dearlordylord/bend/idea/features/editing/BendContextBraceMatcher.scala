package com.dearlordylord.bend.idea.features.editing

import com.dearlordylord.bend.idea.syntax.lexer.BendTokens
import com.dearlordylord.bend.idea.syntax.parser.BendTypeAngleContext
import com.intellij.codeInsight.highlighting.BraceMatcher
import com.intellij.openapi.editor.highlighter.HighlighterIterator
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.util.Key
import com.intellij.psi.{PsiFile, TokenType}
import com.intellij.psi.tree.IElementType

/** Contextual matching complements the language's ordinary auto-pair
  * definitions.
  */
final class BendContextBraceMatcher extends BraceMatcher:
  private val cacheKey =
    Key.create[(Long, Map[Int, Int])]("bend.type.angle.matches")

  private def pairs(
      iterator: HighlighterIterator,
      source: CharSequence
  ): Map[Int, Int] =
    val document = iterator.getDocument
    val stamp = document.getModificationStamp
    Option(document.getUserData(cacheKey)) match
      case Some((cachedStamp, cached)) if cachedStamp == stamp => cached
      case _                                                   =>
        val found = BendTypeAngleContext.matchedPairs(source)
        document.putUserData(cacheKey, (stamp, found))
        found

  override def getBraceTokenGroupId(kind: IElementType): Int =
    if kind == BendTokens.LeftAngle || kind == BendTokens.RightAngle then 2
    else if opposite(kind) != null then 1
    else -1

  override def isLBraceToken(
      iterator: HighlighterIterator,
      text: CharSequence,
      fileType: FileType
  ): Boolean =
    val kind = iterator.getTokenType
    Set(BendTokens.LeftParen, BendTokens.LeftBracket, BendTokens.LeftBrace)
      .contains(kind) ||
    kind == BendTokens.LeftAngle && pairs(iterator, text).contains(
      iterator.getStart
    )

  override def isRBraceToken(
      iterator: HighlighterIterator,
      text: CharSequence,
      fileType: FileType
  ): Boolean =
    val kind = iterator.getTokenType
    Set(BendTokens.RightParen, BendTokens.RightBracket, BendTokens.RightBrace)
      .contains(kind) ||
    kind == BendTokens.RightAngle && pairs(iterator, text).contains(
      iterator.getStart
    )

  override def isPairBraces(left: IElementType, right: IElementType): Boolean =
    opposite(left) == right

  override def isStructuralBrace(
      iterator: HighlighterIterator,
      text: CharSequence,
      fileType: FileType
  ): Boolean =
    iterator.getTokenType == BendTokens.LeftBrace || iterator.getTokenType == BendTokens.RightBrace

  override def getOppositeBraceTokenType(kind: IElementType): IElementType =
    opposite(kind)

  private def opposite(kind: IElementType): IElementType = kind match
    case BendTokens.LeftParen    => BendTokens.RightParen
    case BendTokens.RightParen   => BendTokens.LeftParen
    case BendTokens.LeftBracket  => BendTokens.RightBracket
    case BendTokens.RightBracket => BendTokens.LeftBracket
    case BendTokens.LeftBrace    => BendTokens.RightBrace
    case BendTokens.RightBrace   => BendTokens.LeftBrace
    case BendTokens.LeftAngle    => BendTokens.RightAngle
    case BendTokens.RightAngle   => BendTokens.LeftAngle
    case _                       => null

  override def isPairedBracesAllowedBeforeType(
      left: IElementType,
      next: IElementType
  ): Boolean =
    next == null || next == BendTokens.Comment ||
      Set(
        BendTokens.RightParen,
        BendTokens.RightBracket,
        BendTokens.RightBrace,
        BendTokens.Separator,
        BendTokens.Operator,
        TokenType.WHITE_SPACE
      ).contains(next)

  override def getCodeConstructStart(
      file: PsiFile,
      openingBraceOffset: Int
  ): Int = openingBraceOffset
