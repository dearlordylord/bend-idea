package com.dearlordylord.bend.idea.features.editing

import com.dearlordylord.bend.idea.symbols.api.BendSourceApplications
import com.dearlordylord.bend.idea.syntax.BendLanguage
import com.dearlordylord.bend.idea.syntax.lexer.{
  BendLexer,
  BendTokens,
  BendWords
}
import com.dearlordylord.bend.idea.syntax.parser.BendTypeAngleContext
import com.dearlordylord.bend.idea.syntax.psi.*
import com.intellij.codeInsight.editorActions.ExtendWordSelectionHandler
import com.intellij.lang.Language
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.TextRange
import com.intellij.psi.{PsiElement, PsiFile, TokenType}
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.ui.breadcrumbs.BreadcrumbsProvider
import java.util.List as JavaList
import scala.collection.mutable
import scala.jdk.CollectionConverters.*

/** Source-level navigation labels for declarations and nested body forms. */
final class BendBreadcrumbsProvider extends BreadcrumbsProvider:
  override def getLanguages: Array[Language] = Array(BendLanguage.instance)

  override def acceptElement(element: PsiElement): Boolean =
    element match
      case _: PsiFile | _: BendDeclaration => true
      case _                               =>
        Option(element.getContainingFile)
          .filter(_.getLanguage == BendLanguage.instance)
          .exists(file =>
            BendBreadcrumbSurface.active(file, element.getTextOffset).nonEmpty
          )

  override def getElementInfo(element: PsiElement): String =
    element match
      case file: PsiFile                => file.getName
      case declaration: BendDeclaration =>
        val kind = declaration match
          case _: BendDefinition  => "def "
          case _: BendDatatype    => "type "
          case _: BendLaw         => "law "
          case _: BendConstructor => ""
        (kind + Option(declaration.getName).getOrElse("<unfinished>")).trim
      case _ =>
        Option(element.getContainingFile)
          .flatMap(file =>
            BendBreadcrumbSurface
              .active(file, element.getTextOffset)
              .lastOption
              .map(_.label)
          )
          .getOrElse("")

  override def getElementTooltip(element: PsiElement): String =
    getElementInfo(element)

  override def getParent(element: PsiElement): PsiElement =
    element match
      case declaration: BendDeclaration =>
        Option(declaration.getParent).getOrElse(declaration.getContainingFile)
      case file: PsiFile => null
      case _             =>
        val file = element.getContainingFile
        if file == null then null
        else
          val frames = BendBreadcrumbSurface.active(file, element.getTextOffset)
          frames
            .dropRight(1)
            .lastOption
            .flatMap(frame => Option(file.findElementAt(frame.from)))
            .getOrElse(
              PsiTreeUtil
                .getParentOfType(element, classOf[BendDeclaration], true)
            )

/** Parsed line nesting, used only to present Bend's indentation-sensitive body
  * surface.
  */
private object BendBreadcrumbSurface:
  final case class Frame(from: Int, until: Int, indent: Int, label: String)
  private final case class OpenFrame(
      from: Int,
      indent: Int,
      label: String,
      var until: Int
  )

  def active(file: PsiFile, offset: Int): List[Frame] =
    val source = file.getText
    val open = mutable.ArrayBuffer.empty[OpenFrame]
    val completed = mutable.ListBuffer.empty[OpenFrame]
    sourceLines(source).foreach { line =>
      if line.from <= offset && line.code.nonEmpty then
        while open.lastOption.exists(_.indent >= line.indent) do
          val frame = open.remove(open.size - 1)
          frame.until = line.from
          completed += frame
        blockHeader(line).foreach { case (keywordFrom, label) =>
          open += OpenFrame(keywordFrom, line.indent, label, source.length)
        }
    }
    completed ++= open
    completed
      .filter(frame => frame.from <= offset && offset < frame.until)
      .map(frame => Frame(frame.from, frame.until, frame.indent, frame.label))
      .sortBy(_.from)
      .toList

  private final case class Line(from: Int, indent: Int, code: String)

  private def sourceLines(source: String): List[Line] =
    val result = List.newBuilder[Line]
    var from = 0
    while from < source.length do
      val newline = source.indexOf('\n', from)
      val until = if newline < 0 then source.length else newline
      val raw = source.substring(from, until).stripSuffix("\r")
      val indent = raw.takeWhile(c => c == ' ' || c == '\t').length
      val code = raw.drop(indent).trim
      if code.nonEmpty && !code.startsWith("#") then
        result += Line(from, indent, code)
      from = if newline < 0 then source.length else newline + 1
    result.result()

  private def blockHeader(line: Line): Option[(Int, String)] =
    val keyword = List("match", "case", "do").find(value =>
      line.code == value || line.code.startsWith(value + " ") ||
        line.code.startsWith(value + ":")
    )
    keyword.map { value =>
      val colon = line.code.indexOf(':')
      val label = if colon >= 0 then line.code.take(colon).trim
      else line.code.trim
      (line.from + line.indent, label)
    }

final class BendSelectionHandler extends ExtendWordSelectionHandler:
  override def canSelect(element: PsiElement): Boolean =
    Option(element.getContainingFile)
      .exists(_.getLanguage == BendLanguage.instance)

  override def select(
      element: PsiElement,
      editorText: CharSequence,
      cursorOffset: Int,
      editor: Editor
  ): JavaList[TextRange] =
    val source = editorText.toString
    val offset = math.max(
      0,
      math.min(
        cursorOffset,
        math.min(source.length, editor.getDocument.getTextLength)
      )
    )
    val ranges = mutable.LinkedHashSet.empty[TextRange]
    wordRanges(source, offset).foreach(ranges += _)
    val file = element.getContainingFile
    if file != null && file.getLanguage == BendLanguage.instance then
      val pairs = delimiterPairs(source)
      val containing = pairs
        .filter(pair => pair.from <= offset && offset <= pair.until)
        .sortBy(pair => pair.until - pair.from)
      containing.foreach { pair =>
        ranges += new TextRange(pair.from, pair.until)
        ranges += new TextRange(pair.openUntil, pair.closeFrom)
        callStart(source, pair.from).foreach(start =>
          ranges += new TextRange(start, pair.until)
        )
      }
      val innermostCall = BendSourceApplications.at(file, offset)
      innermostCall.foreach { application =>
        pairs
          .find(_.from == application.openFrom)
          .foreach(pair =>
            ranges += new TextRange(application.calleeFrom, pair.until)
          )
        application.arguments
          .find(argument => argument.from <= offset && offset <= argument.until)
          .foreach(argument =>
            ranges += new TextRange(argument.from, argument.until)
          )
      }
      BendFoldingSurface
        .ranges(file)
        .filter(range => range.from <= offset && offset <= range.until)
        .foreach(range => ranges += new TextRange(range.from, range.until))
      Option(
        PsiTreeUtil.getParentOfType(element, classOf[BendDeclaration], true)
      )
        .foreach { declaration =>
          Option(PsiTreeUtil.getChildOfType(declaration, classOf[BendHeader]))
            .foreach(header => ranges += header.getTextRange)
          declaration.proofForms
            .filter(form => form.from <= offset && offset <= form.until)
            .foreach(form => ranges += new TextRange(form.from, form.until))
          ranges += declaration.getTextRange
        }
    val lineStart = source.lastIndexOf('\n', math.max(0, offset - 1)) + 1
    val lineEnd = source.indexOf('\n', offset) match
      case -1  => source.length
      case end => end
    if lineEnd > lineStart then ranges += new TextRange(lineStart, lineEnd)
    ranges.toList
      .sortBy(range => (range.getLength, range.getStartOffset))
      .asJava

  private final case class Pair(
      from: Int,
      openUntil: Int,
      closeFrom: Int,
      until: Int
  )

  private def wordRanges(source: String, offset: Int): List[TextRange] =
    def word(c: Char): Boolean = c.isLetterOrDigit || c == '_' || c == '.'
    val pivot = if offset == source.length then offset - 1 else offset
    if pivot < 0 || !word(source.charAt(pivot)) then Nil
    else
      var left = pivot
      var right = pivot + 1
      while left > 0 && word(source.charAt(left - 1)) do left -= 1
      while right < source.length && word(source.charAt(right)) do right += 1
      val complete = new TextRange(left, right)
      val dot = source.indexOf('.', left)
      val component =
        if dot >= left && dot < right then
          val segmentStart = if pivot < dot then left else dot + 1
          val segmentEnd = if pivot < dot then dot else right
          List(new TextRange(segmentStart, segmentEnd))
        else Nil
      complete :: component

  private def callStart(source: String, open: Int): Option[Int] =
    var end = open - 1
    while end >= 0 && source.charAt(end).isWhitespace do end -= 1
    if end >= 0 && source.charAt(end) == '!' then
      end -= 1
      while end >= 0 && source.charAt(end).isWhitespace do end -= 1
    var start = end
    while start >= 0 && (source.charAt(start).isLetterOrDigit ||
        source.charAt(start) == '_' || source.charAt(start) == '.')
    do start -= 1
    val name = source.substring(start + 1, end + 1)
    Option.when(
      name.matches("[A-Za-z_][A-Za-z0-9_.]*") &&
        !BendWords.reserved(name)
    )(start + 1)

  private def delimiterPairs(source: String): List[Pair] =
    val anglePairs = BendTypeAngleContext.matchedPairs(source)
    val lexer = new BendLexer()
    lexer.start(source)
    val stack = mutable.ArrayBuffer.empty[(String, Int, Int)]
    val pairs = mutable.ListBuffer.empty[Pair]
    while lexer.getTokenType != null do
      val kind = lexer.getTokenType
      val from = lexer.getTokenStart
      val until = lexer.getTokenEnd
      val spelling = source.substring(from, until)
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
          kind == BendTokens.RightAngle && anglePairs.valuesIterator.contains(
            from
          )
        then
          stack.lastIndexWhere(_._1 == spelling) match
            case found if found >= 0 =>
              val (_, openFrom, openUntil) = stack.remove(found)
              pairs += Pair(openFrom, openUntil, from, until)
            case _ => ()
      lexer.advance()
    pairs.toList
