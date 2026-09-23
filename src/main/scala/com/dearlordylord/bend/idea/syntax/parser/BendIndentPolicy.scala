package com.dearlordylord.bend.idea.syntax.parser

import com.dearlordylord.bend.idea.syntax.lexer.{BendLexer, BendTokens}
import scala.collection.mutable

/** Indentation levels from source layout; callers perform the editor write. */
object BendIndentPolicy:
  def afterEnter(source: String, caret: Int, step: Int): Option[Int] =
    if caret <= 0 || caret > source.length then return None
    val currentStart = source.lastIndexOf('\n', caret - 1) + 1
    if currentStart == 0 then return None
    val previousEnd = currentStart - 1
    val previousStart = source.lastIndexOf('\n', previousEnd - 1) + 1
    val previous = source.substring(previousStart, previousEnd).stripSuffix("\r")
    if previous.trim.isEmpty then return None
    val base = leading(previous)
    if inLiteral(source, previousEnd) then return Some(0)
    if previous.drop(base).startsWith("#") then return Some(base)
    val suffix = previous.trim
    if suffix.endsWith(":") then Some(base + step)
    else
      val open = unmatchedOpening(source, previousEnd)
      open match
        case Some(position) =>
          val lineStart = source.lastIndexOf('\n', position - 1) + 1
          Some(leading(source.substring(lineStart, position)) + step)
        case None => Some(base)

  def backspace(source: String, caret: Int, step: Int): Option[Int] =
    if caret <= 0 || caret > source.length then return None
    val lineStart = source.lastIndexOf('\n', caret - 1) + 1
    val before = source.substring(lineStart, caret)
    if before.isEmpty || !before.forall(c => c == ' ' || c == '\t') then return None
    val current = before.length
    if current == 0 then None
    else Some(math.max(0, ((current - 1) / step) * step))

  private def leading(line: String): Int = line.takeWhile(c => c == ' ' || c == '\t').length

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
          (offset == 0 || source.charAt(offset - 1) == '\n') then stack.clear()
      kind match
        case BendTokens.LeftParen => stack += ((')', offset))
        case BendTokens.LeftBracket => stack += ((']', offset))
        case BendTokens.LeftBrace => stack += (('}', offset))
        case BendTokens.RightParen | BendTokens.RightBracket | BendTokens.RightBrace =>
          val close = source.charAt(offset)
          if stack.lastOption.exists(_._1 == close) then stack.remove(stack.size - 1)
        case _ => ()
      lexer.advance()
    stack.lastOption.map(_._2)
