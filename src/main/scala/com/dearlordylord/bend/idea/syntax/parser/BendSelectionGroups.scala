package com.dearlordylord.bend.idea.syntax.parser

import com.dearlordylord.bend.idea.syntax.lexer.BendTokens
import com.intellij.psi.TokenType
import scala.collection.mutable

/** Top-level components of a matched Bend group, excluding nested separators.
  */
object BendSelectionGroups:
  import BendSelectionAtoms.Span
  private final case class Token(text: String, from: Int, until: Int)
  final case class Pair(from: Int, openUntil: Int, closeFrom: Int, until: Int)

  def pairs(view: BendSelectionSource): List[Pair] =
    val anglePairs = view.anglePairs
    val stack = mutable.ArrayBuffer.empty[(String, Int, Int)]
    val pairs = mutable.ListBuffer.empty[Pair]
    view.tokens.foreach { token =>
      val kind = token.kind
      val from = token.from
      val until = token.until
      val spelling = token.spelling
      if kind != TokenType.WHITE_SPACE && kind != BendTokens.Comment &&
        kind != BendTokens.StringDelimiter && kind != BendTokens.StringContent &&
        kind != BendTokens.Escape && kind != BendTokens.InvalidEscape
      then
        val closing = kind match
          case BendTokens.LeftParen   => Some(")")
          case BendTokens.LeftBrace   => Some("}")
          case BendTokens.LeftBracket => Some("]")
          case _ if kind == BendTokens.LeftAngle && anglePairs.contains(from) =>
            Some(">")
          case _ => None
        closing.foreach(value => stack += ((value, from, until)))
        if Set(")", "}", "]").contains(spelling) ||
          kind == BendTokens.RightAngle && anglePairs.contains(from)
        then
          stack.lastIndexWhere(_._1 == spelling) match
            case found if found >= 0 =>
              val (_, openFrom, openUntil) = stack.remove(found)
              pairs += Pair(openFrom, openUntil, from, until)
            case _ => ()
    }
    pairs.toList

  def components(source: String, open: Int, close: Int): List[Span] =
    components(new BendSelectionSource(source), open, close)

  def components(view: BendSelectionSource, open: Int, close: Int): List[Span] =
    val source = view.text
    if open < 0 || close <= open || close >= source.length then return Nil
    val contentFrom = open + 1
    val tokens = tokenize(view, contentFrom, close)
    val stack = mutable.ArrayBuffer.empty[String]
    val boundaries = mutable.ListBuffer(contentFrom)
    val square = source.charAt(open) == '['
    val angles = view.anglePairs
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

  private def tokenize(
      view: BendSelectionSource,
      from: Int,
      until: Int
  ): List[Token] =
    view.tokens.iterator
      .filter(token =>
        token.from >= from && token.until <= until &&
          token.kind != TokenType.WHITE_SPACE &&
          token.kind != BendTokens.Comment &&
          token.kind != BendTokens.StringContent &&
          token.kind != BendTokens.StringDelimiter
      )
      .map(token => Token(token.spelling, token.from, token.until))
      .toList
