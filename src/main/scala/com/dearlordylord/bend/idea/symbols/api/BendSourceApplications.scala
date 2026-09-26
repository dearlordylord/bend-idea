package com.dearlordylord.bend.idea.symbols.api

import com.dearlordylord.bend.idea.syntax.lexer.{BendLexer, BendTokens}
import com.dearlordylord.bend.idea.syntax.parser.BendTypeAngleContext
import com.intellij.psi.{PsiFile, TokenType}
import com.intellij.psi.tree.IElementType
import scala.collection.mutable

enum BendApplicationKind:
  case Function, Datatype, Constructor

final case class BendSourceArgument(source: String, from: Int, until: Int)

/** A source application found without inferring its argument or result types.
  */
final case class BendSourceApplication(
    callee: String,
    calleeFrom: Int,
    calleeUntil: Int,
    openFrom: Int,
    openUntil: Int,
    kind: BendApplicationKind,
    arguments: List[BendSourceArgument],
    activeArgument: Int,
    complete: Boolean
)

final case class BendSourceCallSite(
    callee: String,
    calleeFrom: Int,
    calleeUntil: Int,
    openFrom: Int
)

/** Shared lexical application alignment for parameter information and hints. */
object BendSourceApplications:
  private final case class Token(
      kind: IElementType,
      text: String,
      from: Int,
      until: Int
  )

  def at(file: PsiFile, offset: Int): Option[BendSourceApplication] =
    val source = file.getText
    if offset < 0 || offset > source.length then return None
    val tokens = tokenize(source)
    val anglePairs = anglePairsFor(source, offset)
    val stack = mutable.ArrayBuffer.empty[Int]
    var index = 0
    while index < tokens.size && tokens(index).from < offset do
      val token = tokens(index)
      if stack.nonEmpty && declarationBoundary(token, source) &&
        indentation(source, token.from) <=
          indentation(source, tokens(stack.head).from)
      then stack.clear()
      if isOpening(token, anglePairs) then stack += index
      else if isClosing(token) then close(stack, tokens, index, anglePairs)
      index += 1
    stack.reverseIterator
      .flatMap(open => application(tokens, open, source, offset, anglePairs))
      .take(1)
      .toList
      .headOption

  def forCallee(
      file: PsiFile,
      calleeOffset: Int
  ): Option[BendSourceApplication] =
    val source = file.getText
    val tokens = tokenize(source)
    val anglePairs = BendTypeAngleContext.applicationPairs(source)
    tokens.indices.iterator
      .filter(index => tokens(index).from == calleeOffset)
      .flatMap { calleeIndex =>
        val openIndex = nextSignificant(tokens, calleeIndex + 1)
        openIndex.toList.flatMap { open =>
          if !isOpening(tokens(open), anglePairs) then Nil
          else
            application(tokens, open, source, source.length, anglePairs).toList
        }
      }
      .take(1)
      .toList
      .headOption

  /** Lexical named calls for dependency inspection, without argument parsing.
    */
  def namedCalls(file: PsiFile): List[BendSourceCallSite] =
    val source = file.getText
    val tokens = tokenize(source)
    tokens.indices.iterator
      .filter(index => tokens(index).text == "(")
      .flatMap { index =>
        val calleeIndex =
          if index > 0 && tokens(index - 1).text == "!" then index - 2
          else index - 1
        Option.when(calleeIndex >= 0)(tokens(calleeIndex)).flatMap { token =>
          Option.when(token.text.matches("[A-Za-z_][A-Za-z0-9_.]*"))(
            BendSourceCallSite(
              token.text,
              token.from,
              token.until,
              tokens(index).from
            )
          )
        }
      }
      .toList

  private def tokenize(source: String): Vector[Token] =
    val lexer = new BendLexer()
    lexer.start(source)
    val result = Vector.newBuilder[Token]
    while lexer.getTokenType != null do
      val kind = lexer.getTokenType
      if kind != TokenType.WHITE_SPACE && kind != BendTokens.Comment &&
        kind != BendTokens.StringDelimiter && kind != BendTokens.StringContent &&
        kind != BendTokens.Escape && kind != BendTokens.InvalidEscape
      then
        result += Token(
          kind,
          source.substring(lexer.getTokenStart, lexer.getTokenEnd),
          lexer.getTokenStart,
          lexer.getTokenEnd
        )
      lexer.advance()
    result.result()

  private def anglePairsFor(
      source: String,
      offset: Int
  ): Map[Int, Int] =
    val matched = BendTypeAngleContext.applicationPairs(source)
    BendTypeAngleContext
      .incompleteTypeApplicationAt(source, offset)
      .map(open => matched.updated(open, Int.MaxValue))
      .getOrElse(matched)

  private def nextSignificant(tokens: Vector[Token], from: Int): Option[Int] =
    Option.when(from < tokens.size)(from)

  private def application(
      tokens: Vector[Token],
      open: Int,
      source: String,
      offset: Int,
      anglePairs: Map[Int, Int]
  ): Option[BendSourceApplication] =
    val opener = tokens(open)
    val calleeIndex = previousCallee(tokens, open)
    if calleeIndex < 0 then return None
    val calleeToken = tokens(calleeIndex)
    val callee = calleeToken.text
    if !callee.matches("[A-Za-z_][A-Za-z0-9_.]*") then return None
    val kind = opener.text match
      case "(" => Some(BendApplicationKind.Function)
      case "{" => Some(BendApplicationKind.Constructor)
      case "<" if anglePairs.contains(opener.from) || callee.head.isUpper =>
        Some(BendApplicationKind.Datatype)
      case _ => None
    kind.map { applicationKind =>
      val boundary = nextDeclaration(tokens, open + 1, source, opener.from)
        .getOrElse(tokens.size)
      val matching = matchingCloseIndex(tokens, open, anglePairs, boundary)
      val endIndex = matching.getOrElse(boundary)
      val endOffset =
        matching.map(tokens(_).from).getOrElse {
          if boundary < tokens.size then tokens(boundary).from
          else source.length
        }
      val limit = math.min(offset, endOffset)
      val active = countSeparators(tokens, open, endIndex, limit, anglePairs)
      val arguments = splitArguments(tokens, open, endIndex, source, anglePairs)
      BendSourceApplication(
        callee,
        calleeToken.from,
        calleeToken.until,
        opener.from,
        opener.until,
        applicationKind,
        arguments,
        active,
        matching.nonEmpty
      )
    }

  private def previousCallee(tokens: Vector[Token], open: Int): Int =
    var index = open - 1
    if index >= 0 && tokens(index).text == "!" then index -= 1
    if index >= 0 then index else -1

  private def matchingCloseIndex(
      tokens: Vector[Token],
      open: Int,
      anglePairs: Map[Int, Int],
      until: Int
  ): Option[Int] =
    val first = tokens(open)
    if first.text == "<" then
      anglePairs
        .get(first.from)
        .flatMap(offset =>
          tokens.indices.take(until).find(index => tokens(index).from == offset)
        )
    else
      val closes = mutable.ArrayBuffer.empty[String]
      var index = open
      while index < until do
        val token = tokens(index)
        if isOpening(token, anglePairs) then closes += closing(token.text)
        else if isClosing(token) && closes.lastOption.contains(token.text) then
          val _ = closes.remove(closes.size - 1)
          if closes.isEmpty then return Some(index)
        index += 1
      None

  private def splitArguments(
      tokens: Vector[Token],
      open: Int,
      close: Int,
      source: String,
      anglePairs: Map[Int, Int]
  ): List[BendSourceArgument] =
    val endIndex = if close < tokens.size then close else tokens.size
    val endOffset =
      if close < tokens.size then tokens(close).from else source.length
    val nested = mutable.ArrayBuffer.empty[String]
    val boundaries = mutable.ListBuffer.empty[(Int, Int)]
    var previous = tokens(open).until
    var index = open + 1
    while index < endIndex do
      val token = tokens(index)
      if nested.isEmpty && token.text == "," then
        boundaries += ((previous, token.from))
        previous = token.until
      else if isOpening(token, anglePairs) then nested += closing(token.text)
      else if isClosing(token) && nested.lastOption.contains(token.text) then
        val _ = nested.remove(nested.size - 1)
      index += 1
    boundaries += ((previous, endOffset))
    boundaries.toList.flatMap { case (from, until) =>
      val boundedFrom = math.max(0, math.min(from, source.length))
      val boundedUntil = math.max(boundedFrom, math.min(until, source.length))
      val raw = source.substring(boundedFrom, boundedUntil)
      val left = raw.indexWhere(c => !c.isWhitespace)
      val right = raw.lastIndexWhere(c => !c.isWhitespace)
      if left < 0 then Nil
      else
        val argumentFrom = boundedFrom + left
        val argumentUntil = boundedFrom + right + 1
        List(
          BendSourceArgument(
            source.substring(argumentFrom, argumentUntil),
            argumentFrom,
            argumentUntil
          )
        )
    }

  private def countSeparators(
      tokens: Vector[Token],
      open: Int,
      close: Int,
      offset: Int,
      anglePairs: Map[Int, Int]
  ): Int =
    val endIndex = if close < tokens.size then close else tokens.size
    val nested = mutable.ArrayBuffer.empty[String]
    var commas = 0
    var index = open + 1
    while index < endIndex && tokens(index).from < offset do
      val token = tokens(index)
      if nested.isEmpty && token.text == "," then commas += 1
      else if isOpening(token, anglePairs) then nested += closing(token.text)
      else if isClosing(token) && nested.lastOption.contains(token.text) then
        val _ = nested.remove(nested.size - 1)
      index += 1
    commas

  private def close(
      stack: mutable.ArrayBuffer[Int],
      tokens: Vector[Token],
      closeIndex: Int,
      anglePairs: Map[Int, Int]
  ): Unit =
    val close = tokens(closeIndex).text
    stack.lastIndexWhere(index =>
      closing(tokens(index).text) == close &&
        (tokens(index).text != "<" || anglePairs.contains(tokens(index).from))
    ) match
      case found if found >= 0 => stack.dropRightInPlace(stack.size - found)
      case _                   => ()

  private def isOpening(token: Token, anglePairs: Map[Int, Int]): Boolean =
    Set("(", "[", "{").contains(token.text) ||
      token.text == "<" && anglePairs.contains(token.from)

  private def isClosing(token: Token): Boolean =
    Set(")", "]", "}", ">").contains(token.text)

  private def closing(open: String): String = open match
    case "(" => ")"
    case "[" => "]"
    case "{" => "}"
    case "<" => ">"
    case _   => ""

  private def nextDeclaration(
      tokens: Vector[Token],
      from: Int,
      source: String,
      openFrom: Int
  ): Option[Int] =
    tokens.indices.iterator
      .drop(from)
      .find(index =>
        declarationBoundary(tokens(index), source) &&
          indentation(source, tokens(index).from) <= indentation(
            source,
            openFrom
          )
      )

  private def declarationBoundary(token: Token, source: String): Boolean =
    if token.kind != BendTokens.DeclarationKeyword then false
    else
      val lineStart = source.lastIndexOf('\n', token.from - 1) + 1
      source
        .substring(lineStart, token.from)
        .matches("[ \\t]*(?:@unsafe[ \\t]+)?")

  private def indentation(source: String, offset: Int): Int =
    val lineStart = source.lastIndexOf('\n', offset - 1) + 1
    source
      .substring(lineStart, offset)
      .takeWhile(c => c == ' ' || c == '\t')
      .length
