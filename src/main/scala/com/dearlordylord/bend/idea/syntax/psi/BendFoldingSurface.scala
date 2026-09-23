package com.dearlordylord.bend.idea.syntax.psi

import com.dearlordylord.bend.idea.syntax.lexer.{BendLexer, BendTokens}
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import scala.collection.mutable
import scala.jdk.CollectionConverters.*

enum BendFoldKind:
  case Declaration, MatchBlock, CaseBlock, DoBlock, Expression

final case class BendFoldRange(from: Int, until: Int, kind: BendFoldKind)

/** Foldable ranges from current tolerant PSI and lexer tokens; no compiler or
  * resolver judgment.
  */
object BendFoldingSurface:
  private final case class Line(
      from: Int,
      until: Int,
      indent: Int,
      content: String
  )

  def ranges(file: PsiFile): List[BendFoldRange] =
    val source = file.getText
    val result = mutable.LinkedHashSet.empty[BendFoldRange]
    val declarations =
      PsiTreeUtil.findChildrenOfType(file, classOf[BendDeclaration]).asScala
    declarations.foreach { declaration =>
      val header = PsiTreeUtil.getChildOfType(declaration, classOf[BendHeader])
      if header != null then
        add(
          source,
          result,
          header.getTextRange.getEndOffset,
          declaration.getTextRange.getEndOffset,
          BendFoldKind.Declaration
        )
    }
    val lines = sourceLines(source)
    val lexer = new BendLexer()
    lexer.start(source)
    val delimiters = mutable.ArrayBuffer.empty[(Int, Char)]
    val blocks = mutable.ArrayBuffer.empty[(Int, BendFoldKind)]
    while lexer.getTokenType != null do
      val kind = lexer.getTokenType
      val offset = lexer.getTokenStart
      val end = lexer.getTokenEnd
      if kind == BendTokens.DeclarationKeyword && atColumnZero(source, offset)
      then delimiters.clear()
      val spelling = source.substring(offset, end)
      if kind == BendTokens.Keyword then
        val blockKind = spelling match
          case "match" => Some(BendFoldKind.MatchBlock)
          case "case"  => Some(BendFoldKind.CaseBlock)
          case "do"    => Some(BendFoldKind.DoBlock)
          case _       => None
        blockKind.foreach(value => blocks += ((offset, value)))
      val opening = kind match
        case BendTokens.LeftParen   => Some(')')
        case BendTokens.LeftBracket => Some(']')
        case BendTokens.LeftBrace   => Some('}')
        case _                      => None
      opening.foreach(close => delimiters += ((end, close)))
      if Set(
          BendTokens.RightParen,
          BendTokens.RightBracket,
          BendTokens.RightBrace
        ).contains(kind) &&
        delimiters.lastOption.exists(_._2 == spelling.head)
      then
        val (from, _) = delimiters.remove(delimiters.size - 1)
        add(source, result, from, offset, BendFoldKind.Expression)
      lexer.advance()
    for (keywordOffset, blockKind) <- blocks do
      lines
        .find(line => line.from <= keywordOffset && keywordOffset < line.until)
        .foreach { line =>
          val colon = blockColon(source, keywordOffset, line.until)
          colon.foreach { bodyStart =>
            val later = lines.dropWhile(_.from <= line.from).takeWhile { next =>
              next.content.trim.isEmpty || next.indent > line.indent
            }
            if later.exists(_.content.trim.nonEmpty) then
              val end = later.lastOption.map(_.until).getOrElse(line.until)
              add(source, result, bodyStart, end, blockKind)
          }
        }
    result.toList.sortBy(range => (range.from, range.until))

  private def add(
      source: String,
      result: mutable.Set[BendFoldRange],
      from: Int,
      until: Int,
      kind: BendFoldKind
  ): Unit =
    val end = math.min(until, source.length)
    var trimmed = end
    if kind != BendFoldKind.Expression then
      while trimmed > from && source.charAt(trimmed - 1).isWhitespace do
        trimmed -= 1
    if from >= 0 && trimmed > from && source
        .substring(from, trimmed)
        .contains('\n')
    then result += BendFoldRange(from, trimmed, kind)

  private def atColumnZero(source: String, offset: Int): Boolean =
    offset == 0 || source.charAt(offset - 1) == '\n' || source.charAt(
      offset - 1
    ) == '\r'

  private def sourceLines(source: String): Vector[Line] =
    val result = Vector.newBuilder[Line]
    var start = 0
    while start < source.length do
      val newline = source.indexOf('\n', start)
      val until = if newline < 0 then source.length else newline + 1
      val content = source.substring(start, until)
      val indent = content.takeWhile(c => c == ' ' || c == '\t').length
      result += Line(start, until, indent, content)
      start = until
    result.result()

  private def blockColon(
      source: String,
      keywordOffset: Int,
      lineEnd: Int
  ): Option[Int] =
    val lexer = new BendLexer()
    lexer.start(source, keywordOffset, lineEnd, 0)
    var depth = 0
    var colon: Option[Int] = None
    while lexer.getTokenType != null do
      val kind = lexer.getTokenType
      val spelling = source.substring(lexer.getTokenStart, lexer.getTokenEnd)
      kind match
        case BendTokens.LeftParen | BendTokens.LeftBracket |
            BendTokens.LeftBrace | BendTokens.LeftAngle =>
          depth += 1
        case BendTokens.RightParen | BendTokens.RightBracket |
            BendTokens.RightBrace | BendTokens.RightAngle =>
          depth = math.max(0, depth - 1)
        case BendTokens.Separator if spelling == ":" && depth == 0 =>
          colon = Some(lexer.getTokenEnd)
        case _ => ()
      lexer.advance()
    colon
