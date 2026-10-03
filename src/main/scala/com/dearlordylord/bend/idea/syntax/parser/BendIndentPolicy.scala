package com.dearlordylord.bend.idea.syntax.parser

import com.dearlordylord.bend.idea.syntax.lexer.{BendLexer, BendTokens}
import scala.collection.mutable

/** Indentation levels from source layout; callers perform the editor write. */
object BendIndentPolicy:
  def afterEnter(
      source: String,
      caret: Int,
      settings: BendLayoutPolicy.Settings
  ): Option[String] =
    if !settings.valid then return None
    if caret <= 0 || caret > source.length then return None
    val currentStart = source.lastIndexOf('\n', caret - 1) + 1
    if currentStart == 0 then return None
    val previousEnd = currentStart - 1
    val previousStart = source.lastIndexOf('\n', previousEnd - 1) + 1
    val previous =
      source.substring(previousStart, previousEnd).stripSuffix("\r")
    if previous.trim.isEmpty then return None
    val prefix = leading(previous)
    if !safePrefix(prefix, settings) then return None
    val base = settings.columns(prefix)
    if inLiteral(source, previousEnd) then return settings.indent(0)
    if previous.drop(prefix.length).startsWith("#") then
      return sustainedIndent(prefix, base, settings)
    val suffix = previous.trim
    if suffix.endsWith(":") then increasedIndent(prefix, base, settings)
    else
      val open = unmatchedOpening(source, previousEnd)
      open match
        case Some(position) =>
          val lineStart = source.lastIndexOf('\n', position - 1) + 1
          val context = leading(source.substring(lineStart, position))
          if !safePrefix(context, settings) then None
          else increasedIndent(context, settings.columns(context), settings)
        case None => sustainedIndent(prefix, base, settings)

  def backspace(
      source: String,
      caret: Int,
      settings: BendLayoutPolicy.Settings
  ): Option[String] =
    if !settings.valid then return None
    if caret <= 0 || caret > source.length then return None
    val lineStart = source.lastIndexOf('\n', caret - 1) + 1
    val before = source.substring(lineStart, caret)
    if before.isEmpty || !before.forall(c => c == ' ' || c == '\t') then
      return None
    if !safePrefix(before, settings) then return None
    val current = settings.columns(before)
    if current == 0 then None
    else
      settings
        .indent(
          math
            .max(0, ((current - 1) / settings.indentSize) * settings.indentSize)
        )
        .filter(_.length < before.length)

  // Bend's parser measures physical characters, while EditorConfig measures
  // visual columns. Do not suggest a logical level that reverses parser order.
  private def sustainedIndent(
      prefix: String,
      columns: Int,
      settings: BendLayoutPolicy.Settings
  ): Option[String] =
    settings.indent(columns).filter(_.length == prefix.length)

  private def increasedIndent(
      prefix: String,
      columns: Int,
      settings: BendLayoutPolicy.Settings
  ): Option[String] =
    settings
      .indent(columns + settings.indentSize)
      .filter(_.length > prefix.length)

  private def safePrefix(
      prefix: String,
      settings: BendLayoutPolicy.Settings
  ): Boolean =
    !prefix.contains(' ') || !prefix.contains('\t') ||
      settings.indent(settings.columns(prefix)).contains(prefix)

  private def leading(line: String): String =
    line.takeWhile(c => c == ' ' || c == '\t')

  private def inLiteral(source: String, until: Int): Boolean =
    val lexer = new BendLexer()
    lexer.start(source, 0, until, 0)
    while lexer.getTokenType != null do lexer.advance()
    (lexer.getState & 3) != 0

  private def unmatchedOpening(source: String, until: Int): Option[Int] =
    val lexer = new BendLexer()
    lexer.start(source, 0, until, 0)
    val stack = mutable.ArrayBuffer.empty[(Char, Int)]
    while lexer.getTokenType != null do
      val kind = lexer.getTokenType
      val offset = lexer.getTokenStart
      if kind == BendTokens.DeclarationKeyword &&
        (offset == 0 || source.charAt(offset - 1) == '\n')
      then stack.clear()
      kind match
        case BendTokens.LeftParen   => stack += ((')', offset))
        case BendTokens.LeftBracket => stack += ((']', offset))
        case BendTokens.LeftBrace   => stack += (('}', offset))
        case BendTokens.RightParen | BendTokens.RightBracket |
            BendTokens.RightBrace =>
          val close = source.charAt(offset)
          if stack.lastOption.exists(_._1 == close) then
            val _ = stack.remove(stack.size - 1)
        case _ => ()
      lexer.advance()
    stack.lastOption.map(_._2)
