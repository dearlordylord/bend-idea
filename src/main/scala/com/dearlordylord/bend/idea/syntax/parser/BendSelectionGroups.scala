package com.dearlordylord.bend.idea.syntax.parser

import com.dearlordylord.bend.idea.syntax.lexer.{BendLexer, BendTokens}
import com.intellij.psi.TokenType
import scala.collection.mutable

/** Top-level components of a matched Bend group, excluding nested separators.
  */
object BendSelectionGroups:
  import BendSelectionAtoms.Span
  private final case class Token(text: String, from: Int, until: Int)

  def components(source: String, open: Int, close: Int): List[Span] =
    if open < 0 || close <= open || close >= source.length then return Nil
    val contentFrom = open + 1
    val tokens = tokenize(source, contentFrom, close)
    val stack = mutable.ArrayBuffer.empty[String]
    val boundaries = mutable.ListBuffer(contentFrom)
    val square = source.charAt(open) == '['
    val angles = BendTypeAngleContext.applicationPairs(source)
    val topTokens = if square then topLevel(tokens, angles) else Nil
    val colon = topTokens.find(_.text == ":")
    val arrayMarker = colon.flatMap(separator =>
      topTokens
        .dropWhile(_.from <= separator.from)
        .find(token => token.text == "^" || token.text == "*")
    )
    tokens.foreach { token =>
      val top = stack.isEmpty
      if top && (token.text == "," || colon.exists(_.from == token.from) ||
          arrayMarker.exists(_.from == token.from))
      then
        boundaries += token.from
        boundaries += token.until
      token.text match
        case "("                                => stack += ")"
        case "["                                => stack += "]"
        case "{"                                => stack += "}"
        case "<" if angles.contains(token.from) =>
          stack += ">"
        case value if stack.lastOption.contains(value) =>
          val _ = stack.remove(stack.size - 1)
        case _ => ()
    }
    boundaries += close
    boundaries.toList
      .grouped(2)
      .flatMap {
        case List(from, until) =>
          val left = source.substring(from, until).indexWhere(!_.isWhitespace)
          val right =
            source.substring(from, until).lastIndexWhere(!_.isWhitespace)
          Option.when(left >= 0)(Span(from + left, from + right + 1))
        case _ => None
      }
      .toList

  private def topLevel(
      tokens: List[Token],
      angles: Map[Int, Int]
  ): List[Token] =
    val stack = mutable.ArrayBuffer.empty[String]
    tokens.filter { token =>
      val top = stack.isEmpty
      token.text match
        case "("                                       => stack += ")"
        case "["                                       => stack += "]"
        case "{"                                       => stack += "}"
        case "<" if angles.contains(token.from)        => stack += ">"
        case value if stack.lastOption.contains(value) =>
          val _ = stack.remove(stack.size - 1)
        case _ => ()
      top
    }

  private def tokenize(source: String, from: Int, until: Int): List[Token] =
    val lexer = new BendLexer()
    lexer.start(source)
    val result = List.newBuilder[Token]
    while lexer.getTokenType != null do
      if lexer.getTokenStart >= from && lexer.getTokenEnd <= until &&
        lexer.getTokenType != TokenType.WHITE_SPACE &&
        lexer.getTokenType != BendTokens.Comment &&
        lexer.getTokenType != BendTokens.StringContent &&
        lexer.getTokenType != BendTokens.StringDelimiter
      then
        result += Token(
          source.substring(lexer.getTokenStart, lexer.getTokenEnd),
          lexer.getTokenStart,
          lexer.getTokenEnd
        )
      lexer.advance()
    result.result()
