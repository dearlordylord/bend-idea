package com.dearlordylord.bend.idea.syntax.parser

import com.dearlordylord.bend.idea.syntax.lexer.{BendLexer, BendTokens}
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType

/** Token-bounded source atoms shared by editor selection. Offsets are UTF-16.
  */
object BendSelectionAtoms:
  final case class Span(from: Int, until: Int)
  private final case class Token(kind: IElementType, text: String, span: Span)

  def at(source: String, offset: Int): List[Span] =
    if offset < 0 || offset > source.length then return Nil
    val tokens = tokenize(source)
    val index = tokens.indexWhere(token =>
      token.span.from <= offset && offset < token.span.until
    )
    if index < 0 then return Nil
    val token = tokens(index)
    if token.kind == BendTokens.StringContent || token.kind == BendTokens.Escape ||
      token.kind == BendTokens.InvalidEscape || token.kind == BendTokens.StringDelimiter
    then literalRanges(tokens, index, offset)
    else if token.kind == BendTokens.Hole || token.kind == BendTokens.Quantity ||
      token.kind == BendTokens.Number || token.kind == BendTokens.Wildcard
    then List(token.span)
    else if nameToken(token.kind) then nameRanges(tokens, index, offset)
    else if Set("+", "-", "~").contains(
        token.text
      ) && index + 1 < tokens.size &&
      tokens(index + 1).span.from == token.span.until &&
      nameToken(tokens(index + 1).kind) && prefixAllowed(tokens, index)
    then
      nameRanges(tokens, index + 1, tokens(index + 1).span.from) :+
        Span(token.span.from, tokens(index + 1).span.until)
    else Nil

  private def nameRanges(
      tokens: Vector[Token],
      index: Int,
      offset: Int
  ): List[Span] =
    val token = tokens(index)
    val text = token.text
    if !text.matches("[A-Za-z_][A-Za-z0-9_.]*") then return List(token.span)
    val boundaries =
      (0 :: text.indices.filter(text.charAt(_) == '.').map(_ + 1).toList) :+
        text.length + 1
    val parts = boundaries
      .sliding(2)
      .collect { case List(from, next) =>
        val until = if next > text.length then text.length else next - 1
        Span(token.span.from + from, token.span.from + until)
      }
      .toList
      .filter(span => span.until > span.from)
    val component =
      parts.find(span => span.from <= offset && offset < span.until)
    val qualified = component.toList.flatMap { part =>
      val position = parts.indexOf(part)
      val prefixes = parts
        .take(position + 1)
        .indices
        .map(i => Span(parts(i).from, part.until))
      val suffixes = parts
        .drop(position)
        .indices
        .map(i => Span(part.from, parts(position + i).until))
      (prefixes ++ suffixes).toList
    }
    val marked =
      if index > 0 && tokens(index - 1).span.until == token.span.from &&
        Set("+", "-", "~").contains(tokens(index - 1).text) &&
        prefixAllowed(tokens, index - 1)
      then List(Span(tokens(index - 1).span.from, token.span.until))
      else Nil
    (component.toList ++ qualified ++ List(token.span) ++ marked).distinct

  private def prefixAllowed(tokens: Vector[Token], index: Int): Boolean =
    val previous =
      tokens.take(index).reverse.find(_.kind != TokenType.WHITE_SPACE)
    val newLine = tokens
      .take(index)
      .reverse
      .takeWhile(_.kind == TokenType.WHITE_SPACE)
      .exists(_.text.contains('\n'))
    previous.isEmpty || previous.exists(token =>
      newLine || Set("(", "[", "{", ",", ":", ";", "=", "->", "<-", "=>")
        .contains(
          token.text
        ) ||
        token.kind == BendTokens.Keyword || token.kind == BendTokens.DeclarationKeyword
    )

  private def literalRanges(
      tokens: Vector[Token],
      index: Int,
      offset: Int
  ): List[Span] =
    var active = Option.empty[Int]
    var open = Option.empty[Int]
    var close = Option.empty[Int]
    tokens.indices.take(index + 1).foreach { i =>
      if tokens(i).kind == BendTokens.StringDelimiter then
        active match
          case None        => active = Some(i)
          case Some(start) =>
            if i == index then
              open = Some(start)
              close = Some(i)
            active = None
      if i == index && open.isEmpty then open = active
    }
    open.toList.flatMap { first =>
      val quote = tokens(first).text
      val ending = close.orElse(
        tokens.indices
          .drop(first + 1)
          .find(i =>
            tokens(i).kind == BendTokens.StringDelimiter && tokens(
              i
            ).text == quote
          )
      )
      val end = ending
        .map(tokens(_).span.until)
        .getOrElse(
          tokens
            .drop(first + 1)
            .takeWhile(t =>
              t.kind == BendTokens.StringContent || t.kind == BendTokens.Escape ||
                t.kind == BendTokens.InvalidEscape
            )
            .lastOption
            .map(_.span.until)
            .getOrElse(tokens(first).span.until)
        )
      if offset >= end then Nil
      else
        val content = Span(
          tokens(first).span.until,
          ending.map(tokens(_).span.from).getOrElse(end)
        )
        val part = tokens(index).span
        List(part, content, Span(tokens(first).span.from, end))
          .filter(span => span.from <= offset && offset < span.until)
          .distinct
    }

  private def nameToken(kind: IElementType): Boolean =
    Set(
      BendTokens.Identifier,
      BendTokens.FunctionName,
      BendTokens.TypeName,
      BendTokens.NamespaceName,
      BendTokens.BuiltinType
    ).contains(kind)

  private def tokenize(source: String): Vector[Token] =
    val lexer = new BendLexer()
    lexer.start(source)
    val result = Vector.newBuilder[Token]
    while lexer.getTokenType != null do
      result += Token(
        lexer.getTokenType,
        source.substring(lexer.getTokenStart, lexer.getTokenEnd),
        Span(lexer.getTokenStart, lexer.getTokenEnd)
      )
      lexer.advance()
    result.result()
