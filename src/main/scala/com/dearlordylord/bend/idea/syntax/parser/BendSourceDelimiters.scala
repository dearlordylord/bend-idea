package com.dearlordylord.bend.idea.syntax.parser

import com.dearlordylord.bend.idea.syntax.lexer.{BendLexer, BendTokens}
import com.intellij.psi.TokenType

/** A narrow prerequisite for absence-based source hints, not full parsing.
  * Lexer tokens keep delimiters inside comments and strings opaque.
  */
object BendSourceDelimiters:
  def balanced(source: String, canceled: () => Unit = () => ()): Boolean =
    val lexer = new BendLexer()
    lexer.start(source)
    var expected = List.empty[com.intellij.psi.tree.IElementType]
    while lexer.getTokenType != null do
      canceled()
      if lexer.getTokenType == TokenType.BAD_CHARACTER then return false
      lexer.getTokenType match
        case BendTokens.LeftParen =>
          expected = BendTokens.RightParen :: expected
        case BendTokens.LeftBracket =>
          expected = BendTokens.RightBracket :: expected
        case BendTokens.LeftBrace =>
          expected = BendTokens.RightBrace :: expected
        case token
            if token == BendTokens.RightParen || token == BendTokens.RightBracket || token == BendTokens.RightBrace =>
          if !expected.headOption.contains(token) then return false
          expected = expected.tail
        case _ => ()
      lexer.advance()
    expected.isEmpty
