package com.dearlordylord.bend.idea.syntax.parser

import com.dearlordylord.bend.idea.syntax.lexer.BendLexer
import com.intellij.psi.tree.IElementType

/** One invocation's UTF-16 source and lexer view for selection readers. */
final class BendSelectionSource(val text: String):
  final case class Token(kind: IElementType, from: Int, until: Int):
    def spelling: String = text.substring(from, until)

  lazy val tokens: Vector[Token] =
    val lexer = new BendLexer()
    lexer.start(text)
    val result = Vector.newBuilder[Token]
    while lexer.getTokenType != null do
      result += Token(
        lexer.getTokenType,
        lexer.getTokenStart,
        lexer.getTokenEnd
      )
      lexer.advance()
    result.result()

  lazy val anglePairs: Map[Int, Int] =
    BendTypeAngleContext.applicationPairs(text)
