package com.dearlordylord.bend.idea.syntax.psi

import com.dearlordylord.bend.idea.syntax.lexer.{
  BendLexer,
  BendTokens,
  BendWords
}
import com.intellij.psi.TokenType
import com.intellij.openapi.progress.ProgressManager

final case class BendDeclaredTypeHead(spelling: String, offset: Int)

/** Nominal heads from explicit source annotations; never inferred types. */
object BendDeclaredTypeHeads:
  def binding(
      source: String,
      nameEnd: Int,
      limit: Int
  ): Option[BendDeclaredTypeHead] =
    val tokens = significant(source, nameEnd, limit, 2)
    tokens.headOption.filter(_._1 == ":").flatMap(_ => head(tokens.drop(1)))

  def result(
      source: String,
      start: Int,
      limit: Int
  ): Option[BendDeclaredTypeHead] =
    val tokens = significant(source, start, limit)
    val delimiters = scala.collection.mutable.ArrayBuffer.empty[String]
    val arrow = tokens.indexWhere { (text, _) =>
      val found = text == "->" && delimiters.isEmpty
      text match
        case "("                                            => delimiters += ")"
        case "<"                                            => delimiters += ">"
        case "{"                                            => delimiters += "}"
        case "["                                            => delimiters += "]"
        case close if delimiters.lastOption.contains(close) =>
          val _ = delimiters.remove(delimiters.size - 1)
        case _ => ()
      found
    }
    if arrow < 0 then None else head(tokens.drop(arrow + 1))

  private def head(tokens: List[(String, Int)]): Option[BendDeclaredTypeHead] =
    tokens.headOption.collect {
      case (name, offset)
          if name.matches("[A-Za-z_][A-Za-z0-9_.]*") &&
            !name.endsWith(".") && !BendWords.reserved(name) =>
        BendDeclaredTypeHead(name, offset)
    }

  private def significant(
      source: String,
      start: Int,
      limit: Int,
      maxTokens: Int = Int.MaxValue
  ): List[(String, Int)] =
    if start < 0 || start >= source.length || limit <= start then Nil
    else
      val lexer = new BendLexer()
      lexer.start(source, start, math.min(limit, source.length), 0)
      val tokens = scala.collection.mutable.ListBuffer.empty[(String, Int)]
      while lexer.getTokenType != null && tokens.size < maxTokens do
        ProgressManager.checkCanceled()
        if lexer.getTokenType != TokenType.WHITE_SPACE && lexer.getTokenType != BendTokens.Comment
        then
          tokens += ((
            source.substring(lexer.getTokenStart, lexer.getTokenEnd),
            lexer.getTokenStart
          ))
        lexer.advance()
      tokens.toList
