package com.dearlordylord.bend.idea.syntax.parser

import com.dearlordylord.bend.idea.syntax.lexer.{BendLexer, BendTokens}
import com.intellij.psi.TokenType
import scala.collection.mutable

/** Source statement boundaries for same-line Bend bodies. */
object BendSelectionStatements:
  import BendSelectionAtoms.Span

  def indexedWriteEnd(source: String, from: Int): Option[Int] =
    if from < 0 || from > source.length then return None
    val arrow = source.indexWhere(!_.isWhitespace, from)
    if arrow < 0 || !source.startsWith("<-", arrow) ||
      source.substring(from, arrow).contains('\n')
    then None
    else
      val value = source.indexWhere(!_.isWhitespace, arrow + 2)
      if value < 0 || source.charAt(value) == '\n' then None
      else
        val lineEnd = source.indexOf('\n', value) match
          case -1 => source.length
          case at => at
        val angles = BendTypeAngleContext.applicationPairs(source)
        val lexer = new BendLexer()
        lexer.start(source)
        while lexer.getTokenType != null && lexer.getTokenStart < value do
          lexer.advance()
        val nested = mutable.ArrayBuffer.empty[String]
        var rewrite = false
        var limit = lineEnd
        while lexer.getTokenType != null && lexer.getTokenStart < limit do
          val start = lexer.getTokenStart
          val spelling = source.substring(start, lexer.getTokenEnd)
          if lexer.getTokenType == BendTokens.Comment then limit = start
          else if lexer.getTokenType != TokenType.WHITE_SPACE &&
            lexer.getTokenType != BendTokens.StringContent &&
            lexer.getTokenType != BendTokens.StringDelimiter
          then
            spelling match
              case "("                                        => nested += ")"
              case "["                                        => nested += "]"
              case "{"                                        => nested += "}"
              case "<" if angles.contains(start)              => nested += ">"
              case close if nested.lastOption.contains(close) =>
                val _ = nested.remove(nested.size - 1)
              case ")" | "]" | "}" if nested.isEmpty => limit = start
              case ">" if nested.isEmpty && angles.contains(start) =>
                limit = start
              case "," if nested.isEmpty            => limit = start
              case "%" if nested.isEmpty            => rewrite = true
              case ";" if nested.isEmpty && rewrite => rewrite = false
              case ";" if nested.isEmpty            => limit = start
              case _                                => ()
          lexer.advance()
        val last =
          source.substring(value, limit).lastIndexWhere(!_.isWhitespace)
        Option.when(last >= 0)(value + last + 1)

  def at(source: String, offset: Int): List[Span] =
    if offset < 0 || offset > source.length then return Nil
    val lineStart = source.lastIndexOf('\n', math.max(0, offset - 1)) + 1
    val lineEnd = source.indexOf('\n', offset) match
      case -1 => source.length
      case at => at
    val lexer = new BendLexer()
    lexer.start(source)
    val stack = mutable.ArrayBuffer.empty[String]
    val separators = mutable.ArrayBuffer.empty[Int]
    var previous = Option.empty[String]
    var rewrite = false
    while lexer.getTokenType != null && lexer.getTokenStart < lineEnd do
      if lexer.getTokenStart >= lineStart &&
        lexer.getTokenType != TokenType.WHITE_SPACE &&
        lexer.getTokenType != BendTokens.Comment &&
        lexer.getTokenType != BendTokens.StringContent &&
        lexer.getTokenType != BendTokens.StringDelimiter
      then
        val spelling = source.substring(lexer.getTokenStart, lexer.getTokenEnd)
        if stack.isEmpty && spelling == "%" &&
          (previous.isEmpty || previous.exists(
            Set("=", ":", ";", "->", "<-", "return")
          ))
        then rewrite = true
        spelling match
          case "("                                       => stack += ")"
          case "["                                       => stack += "]"
          case "{"                                       => stack += "}"
          case value if stack.lastOption.contains(value) =>
            val _ = stack.remove(stack.size - 1)
          case ";" if stack.isEmpty && rewrite => rewrite = false
          case ";" if stack.isEmpty => separators += lexer.getTokenStart
          case _                    => ()
        previous = Some(spelling)
      lexer.advance()
    val boundaries = (lineStart +: separators.toList.map(_ + 1))
      .zip(separators.toList :+ lineEnd)
    val statements = boundaries
      .flatMap { case (from, until) =>
        val raw = source.substring(from, until)
        val first = raw.indexWhere(!_.isWhitespace)
        val last = raw.lastIndexWhere(!_.isWhitespace)
        Option.when(first >= 0)(Span(from + first, from + last + 1))
      }
      .filter(span => span.from <= offset && offset <= span.until)
    statements ++ enclosingLocals(source, offset)

  private def enclosingLocals(source: String, offset: Int): List[Span] =
    val starts = mutable.ListBuffer(0)
    var newline = source.indexOf('\n')
    while newline >= 0 do
      if newline + 1 < source.length then starts += newline + 1
      newline = source.indexOf('\n', newline + 1)
    val lines = starts.toList
    val candidates =
      lines.takeWhile(_ <= offset).takeRight(200).flatMap { from =>
        if from >= source.length then None
        else
          val newline = source.indexOf('\n', from)
          val until = if newline < 0 then source.length else newline
          val raw = source.substring(from, until).stripSuffix("\r")
          val indent = raw.takeWhile(c => c == ' ' || c == '\t').length
          val content = raw.drop(indent)
          if indent == 0 || content.startsWith("#") ||
            !content.matches(
              "[-+~]?[A-Za-z_][A-Za-z0-9_.]*(?:[ \\t]+[A-Za-z_][A-Za-z0-9_.]*)*(?:[ \\t]*:[^=]+)?[ \\t]*=[^=>].*"
            )
          then None
          else
            val later = lines.dropWhile(_ <= from).takeWhile { next =>
              if next >= source.length then false
              else
                val end = source.indexOf('\n', next) match
                  case -1 => source.length
                  case at => at
                val line = source.substring(next, end)
                line.trim.isEmpty || line
                  .takeWhile(c => c == ' ' || c == '\t')
                  .length >= indent
            }
            val end = later.lastOption
              .map { next =>
                source.indexOf('\n', next) match
                  case -1 => source.length
                  case at => at
              }
              .getOrElse(until)
            val trimmed =
              source.substring(from, end).lastIndexWhere(!_.isWhitespace)
            val finish =
              if end < source.length && source.charAt(end) == '\n'
              then end + 1
              else from + trimmed + 1
            Option.when(trimmed >= 0)(Span(from + indent, finish))
      }
    candidates.filter(span => span.from <= offset && offset <= span.until)
