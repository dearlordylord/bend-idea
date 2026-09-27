package com.dearlordylord.bend.idea.syntax.parser

import com.dearlordylord.bend.idea.syntax.lexer.BendTokens
import com.intellij.psi.TokenType
import scala.collection.mutable

/** Lambda and rewrite forms within a larger Bend body. */
object BendSelectionForms:
  import BendSelectionAtoms.Span
  private final case class Token(text: String, from: Int, until: Int)
  private final case class Line(
      from: Int,
      until: Int,
      indent: Int,
      text: String
  )

  def at(source: String, offset: Int): List[Span] =
    at(new BendSelectionSource(source), offset)

  def at(view: BendSelectionSource, offset: Int): List[Span] =
    val source = view.text
    if offset < 0 || offset > source.length then return Nil
    val lineStart = source.lastIndexOf('\n', math.max(0, offset - 1)) + 1
    val lineEnd = source.indexOf('\n', offset) match
      case -1 => source.length
      case at => at
    val tokens = Vector.newBuilder[Token]
    view.tokens.iterator.takeWhile(_.from < lineEnd).foreach { token =>
      if token.from >= lineStart &&
        token.kind != TokenType.WHITE_SPACE &&
        token.kind != BendTokens.Comment &&
        token.kind != BendTokens.StringContent &&
        token.kind != BendTokens.StringDelimiter
      then
        tokens += Token(
          token.spelling,
          token.from,
          token.until
        )
    }
    val significant = tokens.result()
    val depth = mutable.ArrayBuffer.empty[String]
    val starts = List.newBuilder[Int]
    significant.indices.foreach { index =>
      val token = significant(index)
      val previous = Option.when(index > 0)(significant(index - 1).text)
      if depth.isEmpty && token.text == "%" &&
        (previous.isEmpty || previous.exists(
          Set("=", ":", ";", "->", "<-", "return")
        ))
      then starts += token.from
      if depth.isEmpty && token.text == "=>" && index > 0 then
        val binder = significant(index - 1)
        if binder.text.matches("[A-Za-z_][A-Za-z0-9_.]*") then
          starts += binder.from
      token.text match
        case "("                                       => depth += ")"
        case "["                                       => depth += "]"
        case "{"                                       => depth += "}"
        case value if depth.lastOption.contains(value) =>
          val _ = depth.remove(depth.size - 1)
        case _ => ()
    }
    val last =
      source.substring(lineStart, lineEnd).lastIndexWhere(!_.isWhitespace)
    val sameLine = if last < 0 then Nil
    else
      val until = lineStart + last + 1
      starts
        .result()
        .map(from => Span(from, until))
        .filter(span => span.from <= offset && offset <= span.until)
    val multiline = enclosingMultiline(source, offset)
    sameLine.filterNot(span =>
      multiline.exists(form =>
        form.from == span.from && form.until > span.until
      )
    ) ++ multiline

  private def enclosingMultiline(source: String, offset: Int): List[Span] =
    val lines = Vector.newBuilder[Line]
    var from = 0
    while from < source.length do
      val newline = source.indexOf('\n', from)
      val until = if newline < 0 then source.length else newline
      val text = source.substring(from, until).stripSuffix("\r")
      lines += Line(
        from,
        until,
        text.takeWhile(c => c == ' ' || c == '\t').length,
        text
      )
      from = if newline < 0 then source.length else newline + 1
    val all = lines.result()
    val current = all.lastIndexWhere(_.from <= offset)
    if current < 0 then return Nil
    all.indices.take(current + 1).takeRight(200).toList.flatMap { index =>
      val header = all(index)
      val trimmed = header.text.trim
      val marker = if trimmed.startsWith("#") then None
      else if trimmed.endsWith("=>") then
        "([A-Za-z_][A-Za-z0-9_.]*)[ \\t]*=>$".r
          .findFirstMatchIn(header.text)
          .map(m => header.from + m.start)
      else if trimmed.endsWith(";") then
        val percent = header.text.indexOf('%')
        val before =
          if percent >= 0 then header.text.substring(0, percent).trim else ""
        Option.when(
          percent >= 0 &&
            (before.isEmpty || List("=", ":", ";", "<-", "return")
              .exists(before.endsWith))
        )(header.from + percent)
      else None
      marker.flatMap { start =>
        val body =
          all.indices.drop(index + 1).find(i => all(i).text.trim.nonEmpty)
        body.flatMap { first =>
          if all(first).indent < header.indent then None
          else
            val rest = all.indices
              .drop(first)
              .take(200)
              .takeWhile(i =>
                all(i).text.trim.isEmpty ||
                  all(i).indent >= all(first).indent
              )
            rest.lastOption.flatMap { last =>
              val end = if all(last).until < source.length then
                all(last).until + 1
              else all(last).until
              Option.when(start <= offset && offset < end)(Span(start, end))
            }
        }
      }
    }
