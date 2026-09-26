package com.dearlordylord.bend.idea.syntax.psi

import com.dearlordylord.bend.idea.syntax.lexer.{BendLexer, BendTokens}
import com.intellij.psi.TokenType

/** Distinct source forms. These describe syntax and make no proof or type
  * judgment.
  */
sealed trait BendProofForm:
  def from: Int
  def until: Int

object BendProofForm:
  final case class Hole(name: String, from: Int, until: Int)
      extends BendProofForm
  final case class Reflexivity(from: Int, until: Int) extends BendProofForm
  final case class Rewrite(from: Int, until: Int, motive: Option[(Int, Int)])
      extends BendProofForm
  final case class Equality(from: Int, until: Int, inequality: Boolean)
      extends BendProofForm
  final case class OperatorAnnotation(
      from: Int,
      until: Int,
      typeRange: Option[(Int, Int)]
  ) extends BendProofForm

/** Tolerant lexer-backed projection for proof navigation and later semantic
  * requests.
  */
object BendProofSurface:
  final case class BoundedScan(forms: List[BendProofForm], truncated: Boolean)

  private final case class Word(
      text: String,
      from: Int,
      until: Int,
      kind: com.intellij.psi.tree.IElementType
  )

  def scan(source: String, base: Int = 0): List[BendProofForm] =
    scanBounded(source, base, Int.MaxValue, Int.MaxValue, () => false)
      .fold(List.empty[BendProofForm])(_.forms)

  /** Cancellation-aware projection for background inventories. Both token
    * visits and returned forms are bounded before sorting or publication.
    */
  def scanBounded(
      source: String,
      base: Int,
      maxTokens: Int,
      maxForms: Int,
      canceled: () => Boolean
  ): Option[BoundedScan] =
    val lexer = new BendLexer()
    lexer.start(source)
    val words = Vector.newBuilder[Word]
    var visited = 0
    while lexer.getTokenType != null && visited < maxTokens && !canceled() do
      if lexer.getTokenType != TokenType.WHITE_SPACE &&
        lexer.getTokenType != BendTokens.Comment &&
        lexer.getTokenType != BendTokens.StringDelimiter &&
        lexer.getTokenType != BendTokens.StringContent &&
        lexer.getTokenType != BendTokens.Escape &&
        lexer.getTokenType != BendTokens.InvalidEscape
      then
        words += Word(
          source.substring(lexer.getTokenStart, lexer.getTokenEnd),
          lexer.getTokenStart + base,
          lexer.getTokenEnd + base,
          lexer.getTokenType
        )
      lexer.advance()
      visited += 1
    if canceled() then return None
    val tokenLimitReached = lexer.getTokenType != null
    val tokens = words.result()
    val result = scala.collection.mutable.ListBuffer.empty[BendProofForm]
    var i = 0
    while i < tokens.size && result.size < maxForms && !canceled() do
      val word = tokens(i)
      if word.kind == BendTokens.Hole || word.text == "?" then
        result += BendProofForm.Hole(word.text.drop(1), word.from, word.until)
      else if word.kind == BendTokens.Rewrite then
        result += BendProofForm.Reflexivity(word.from, word.until)
      else if word.text == "%" then
        val colon = topLevel(tokens, i + 1, ":", Int.MaxValue, canceled)
        val semi = colon.flatMap(j =>
          topLevel(tokens, j + 1, ";", Int.MaxValue, canceled)
        )
        val boundary = semi.map(tokens(_).from).getOrElse(base + source.length)
        val motive = colon.map(j => (tokens(j).until, boundary))
        result += BendProofForm.Rewrite(word.from, boundary, motive)
      else if word.text == "{" then
        val close = matching(tokens, i, "{", "}", canceled)
        val stop = close.getOrElse(tokens.size)
        val relation = topLevel(
          tokens,
          i + 1,
          Set("==", "!="),
          stop,
          canceled
        )
        if relation.nonEmpty && topLevel(
            tokens,
            relation.get + 1,
            ":",
            stop,
            canceled
          ).nonEmpty
        then
          result += BendProofForm.Equality(
            word.from,
            close.map(tokens(_).until).getOrElse(base + source.length),
            tokens(relation.get).text == "!="
          )
      else if word.text == "(" then
        val close = matching(tokens, i, "(", ")", canceled)
        val stop = close.getOrElse(tokens.size)
        val colon = topLevel(tokens, i + 1, ":", stop, canceled)
        val operator = tokens
          .slice(i + 1, stop)
          .exists(t => t.kind == BendTokens.Operator && t.text != "->")
        if operator && colon.nonEmpty then
          val typeRange = colon.map(j =>
            (
              tokens(j).until,
              close.map(tokens(_).from).getOrElse(base + source.length)
            )
          )
          result += BendProofForm.OperatorAnnotation(
            word.from,
            close.map(tokens(_).until).getOrElse(base + source.length),
            typeRange
          )
      i += 1
    if canceled() then None
    else
      val forms = result.toList
      Some(BoundedScan(forms, tokenLimitReached || i < tokens.size))

  /** Hole-only inventory avoids delimiter matching and nested token rescans. */
  def holesBounded(
      source: String,
      base: Int,
      maxTokens: Int,
      maxHoles: Int,
      canceled: () => Boolean
  ): Option[BoundedScan] =
    val lexer = new BendLexer()
    lexer.start(source)
    val holes = scala.collection.mutable.ListBuffer.empty[BendProofForm]
    var visited = 0
    while lexer.getTokenType != null && visited < maxTokens &&
      holes.size < maxHoles && !canceled()
    do
      val kind = lexer.getTokenType
      val tokenText = source.substring(lexer.getTokenStart, lexer.getTokenEnd)
      val inLiteralOrComment =
        kind == TokenType.WHITE_SPACE || kind == BendTokens.Comment ||
          kind == BendTokens.StringDelimiter || kind == BendTokens.StringContent ||
          kind == BendTokens.Escape || kind == BendTokens.InvalidEscape
      if !inLiteralOrComment &&
        (kind == BendTokens.Hole || tokenText == "?")
      then
        holes += BendProofForm.Hole(
          tokenText.drop(1),
          lexer.getTokenStart + base,
          lexer.getTokenEnd + base
        )
      lexer.advance()
      visited += 1
    if canceled() then None
    else
      Some(
        BoundedScan(
          holes.toList,
          lexer.getTokenType != null &&
            (visited >= maxTokens || holes.size >= maxHoles)
        )
      )

  private def matching(
      tokens: Vector[Word],
      at: Int,
      open: String,
      close: String,
      canceled: () => Boolean
  ): Option[Int] =
    var depth = 0
    var i = at
    while i < tokens.size && !canceled() do
      if tokens(i).text == open then depth += 1
      else if tokens(i).text == close then
        depth -= 1
        if depth == 0 then return Some(i)
      i += 1
    None

  private def topLevel(
      tokens: Vector[Word],
      from: Int,
      wanted: String,
      until: Int,
      canceled: () => Boolean
  ): Option[Int] = topLevel(tokens, from, Set(wanted), until, canceled)

  private def topLevel(
      tokens: Vector[Word],
      from: Int,
      wanted: Set[String],
      until: Int,
      canceled: () => Boolean
  ): Option[Int] =
    var depth = 0
    var i = from
    while i < math.min(tokens.size, until) && !canceled() do
      val word = tokens(i).text
      if depth == 0 && wanted.contains(word) then return Some(i)
      word match
        case "(" | "[" | "{" => depth += 1
        case ")" | "]" | "}" => depth = math.max(0, depth - 1)
        case _               => ()
      i += 1
    None
