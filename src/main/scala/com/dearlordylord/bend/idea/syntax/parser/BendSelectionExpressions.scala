package com.dearlordylord.bend.idea.syntax.parser

import com.dearlordylord.bend.idea.syntax.lexer.BendTokens
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import scala.collection.mutable

/** Tolerant source expression spans using Bend's operator precedence. */
object BendSelectionExpressions:
  import BendSelectionAtoms.Span
  private final case class Token(
      kind: IElementType,
      text: String,
      from: Int,
      until: Int
  )
  private final case class Parsed(span: Span, next: Int, ranges: List[Span])
  private val precedence = Map(
    "->" -> (0, true),
    "&" -> (1, true),
    "|" -> (1, true),
    "||" -> (2, false),
    "&&" -> (3, false),
    "<" -> (4, false),
    ">" -> (4, false),
    "<=" -> (4, false),
    ">=" -> (4, false),
    "<>" -> (5, true),
    "++" -> (5, true),
    "<&>" -> (5, true),
    ".|." -> (6, false),
    ".^." -> (7, false),
    ".&." -> (8, false),
    "<<" -> (9, false),
    ">>" -> (9, false),
    "+" -> (10, false),
    "-" -> (10, false),
    "*" -> (11, false),
    "/" -> (11, false),
    "%" -> (11, false)
  )

  def at(source: String, offset: Int): List[Span] =
    at(new BendSelectionSource(source), offset)

  def at(view: BendSelectionSource, offset: Int): List[Span] =
    val source = view.text
    if offset < 0 || offset > source.length then return Nil
    val lowerBound = math.max(0, offset - 4096)
    val angles = view.anglePairs
    val tokens = mergeRightShifts(tokenize(view), angles)
    tokens.indices.iterator
      .filter(i =>
        tokens(i).from >= lowerBound &&
          tokens(i).from <= offset && candidate(tokens, i, source)
      )
      .flatMap(i => parse(tokens, i, 0, source, angles).toList)
      .flatMap(_.ranges)
      .filter(span =>
        span.from <= offset && offset <= span.until &&
          span.until - span.from <= 4096
      )
      .toList
      .distinct

  private def candidate(
      tokens: Vector[Token],
      index: Int,
      source: String
  ): Boolean =
    val token = tokens(index)
    if !termStart(token) then false
    else if index == 0 then true
    else
      val previous = tokens(index - 1)
      val headerArrow = previous.text == "->" && index >= 2 &&
        tokens(index - 2).text == ")"
      (!precedence.contains(previous.text) || headerArrow) &&
      (source.substring(previous.until, token.from).contains('\n') ||
        Set("(", "[", "{", ",", ":", ";", "=", "->", "=>").contains(
          previous.text
        ) ||
        Set("return", "case", "match", "for", "do").contains(previous.text))

  private def parse(
      tokens: Vector[Token],
      from: Int,
      minimum: Int,
      source: String,
      angles: Map[Int, Int]
  ): Option[Parsed] =
    primary(tokens, from, source, angles).map { first =>
      var left = first
      var running = true
      while running && left.next < tokens.size do
        val operator = tokens(left.next)
        val rule = precedence.get(operator.text)
        val next = if operator.until < source.length then
          source.charAt(operator.until)
        else 0.toChar
        val before =
          if operator.from > 0 then source.charAt(operator.from - 1) else ' '
        val excluded = Set("+", "-").contains(operator.text) &&
          (next == '>' || next == '_' || next.isLetter) ||
          operator.text == "%" && !next.isWhitespace ||
          operator.text.startsWith(">") && !before.isWhitespace
        if excluded || rule.isEmpty || rule.get._1 < minimum ||
          operator.text == "<" && angles.contains(operator.from) ||
          operator.text == ">" && angles.contains(operator.from)
        then running = false
        else
          val (level, rightAssociative) = rule.get
          val rhs = parse(
            tokens,
            left.next + 1,
            if rightAssociative then level else level + 1,
            source,
            angles
          )
          rhs match
            case None        => running = false
            case Some(right) =>
              val span = Span(left.span.from, right.span.until)
              left =
                Parsed(span, right.next, left.ranges ++ right.ranges :+ span)
      left
    }

  private def primary(
      tokens: Vector[Token],
      from: Int,
      source: String,
      angles: Map[Int, Int]
  ): Option[Parsed] =
    if from >= tokens.size then return None
    val token = tokens(from)
    if token.text == "+" && from + 1 < tokens.size &&
      token.until == tokens(from + 1).from
    then
      return primary(tokens, from + 1, source, angles).map(value =>
        Parsed(Span(token.from, value.span.until), value.next, value.ranges)
      )
    if !termStart(token) then return None
    var end = if Set("(", "[", "{").contains(token.text) then
      matching(tokens, from, angles).map(_ + 1).getOrElse(from + 1)
    else from + 1
    while end < tokens.size && Set("(", "[", "{", "<").contains(
        tokens(end).text
      ) &&
      (tokens(end).text != "<" || angles.contains(tokens(end).from)) &&
      !source.substring(tokens(end - 1).until, tokens(end).from).contains('\n')
    do
      matching(tokens, end, angles) match
        case Some(close) => end = close + 1
        case None        =>
          return Some(Parsed(Span(token.from, tokens(end - 1).until), end, Nil))
    Some(Parsed(Span(token.from, tokens(end - 1).until), end, Nil))

  private def matching(
      tokens: Vector[Token],
      open: Int,
      angles: Map[Int, Int]
  ): Option[Int] =
    val closes = mutable.ArrayBuffer.empty[String]
    var index = open
    while index < tokens.size do
      val token = tokens(index)
      val closer = token.text match
        case "("                                => Some(")")
        case "["                                => Some("]")
        case "{"                                => Some("}")
        case "<" if angles.contains(token.from) => Some(">")
        case _                                  => None
      closer.foreach(closes += _)
      if closer.isEmpty && closes.lastOption.contains(token.text) then
        val _ = closes.remove(closes.size - 1)
        if closes.isEmpty then return Some(index)
      index += 1
    None

  private def atom(token: Token): Boolean =
    Set(
      BendTokens.Identifier,
      BendTokens.FunctionName,
      BendTokens.TypeName,
      BendTokens.NamespaceName,
      BendTokens.BuiltinType,
      BendTokens.Number,
      BendTokens.Hole,
      BendTokens.Quantity,
      BendTokens.Wildcard,
      BendTokens.StringDelimiter
    ).contains(token.kind)

  private def termStart(token: Token): Boolean =
    atom(token) || Set("(", "[", "{", "+").contains(token.text)

  private def tokenize(view: BendSelectionSource): Vector[Token] =
    val source = view.text
    val result = Vector.newBuilder[Token]
    var literal = Option.empty[Int]
    view.tokens.foreach { token =>
      val kind = token.kind
      if kind == BendTokens.StringDelimiter then
        literal match
          case None        => literal = Some(token.from)
          case Some(start) =>
            result += Token(
              BendTokens.StringDelimiter,
              source.substring(start, token.until),
              start,
              token.until
            )
            literal = None
      else if literal.isEmpty && kind != TokenType.WHITE_SPACE &&
        kind != BendTokens.Comment
      then
        result += Token(
          kind,
          token.spelling,
          token.from,
          token.until
        )
    }
    result.result()

  private def mergeRightShifts(
      tokens: Vector[Token],
      angles: Map[Int, Int]
  ): Vector[Token] =
    val result = Vector.newBuilder[Token]
    var index = 0
    while index < tokens.size do
      val first = tokens(index)
      if first.text == ">" && !angles.contains(first.from) &&
        index + 1 < tokens.size && tokens(index + 1).text == ">" &&
        tokens(index + 1).from == first.until &&
        !angles.contains(tokens(index + 1).from)
      then
        result += Token(
          BendTokens.Operator,
          ">>",
          first.from,
          tokens(index + 1).until
        )
        index += 2
      else
        result += first
        index += 1
    result.result()
