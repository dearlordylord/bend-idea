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
  private final case class Word(
      text: String,
      from: Int,
      until: Int,
      kind: com.intellij.psi.tree.IElementType
  )

  def scan(source: String, base: Int = 0): List[BendProofForm] =
    val lexer = new BendLexer()
    lexer.start(source)
    val words = Vector.newBuilder[Word]
    while lexer.getTokenType != null do
      if lexer.getTokenType != TokenType.WHITE_SPACE && lexer.getTokenType != BendTokens.Comment
      then
        words += Word(
          source.substring(lexer.getTokenStart, lexer.getTokenEnd),
          lexer.getTokenStart + base,
          lexer.getTokenEnd + base,
          lexer.getTokenType
        )
      lexer.advance()
    val tokens = words.result()
    val result = List.newBuilder[BendProofForm]
    for i <- tokens.indices do
      val word = tokens(i)
      if word.kind == BendTokens.Hole || word.text == "?" then
        result += BendProofForm.Hole(word.text.drop(1), word.from, word.until)
      else if word.kind == BendTokens.Rewrite then
        result += BendProofForm.Reflexivity(word.from, word.until)
      else if word.text == "%" then
        val colon = topLevel(tokens, i + 1, ":")
        val semi = colon.flatMap(j => topLevel(tokens, j + 1, ";"))
        val boundary = semi.map(tokens(_).from).getOrElse(base + source.length)
        val motive = colon.map(j => (tokens(j).until, boundary))
        result += BendProofForm.Rewrite(word.from, boundary, motive)
      else if word.text == "{" then
        val close = matching(tokens, i, "{", "}")
        val stop = close.getOrElse(tokens.size)
        val relation = topLevel(tokens, i + 1, Set("==", "!="), stop)
        if relation.nonEmpty && topLevel(
            tokens,
            relation.get + 1,
            ":",
            stop
          ).nonEmpty
        then
          result += BendProofForm.Equality(
            word.from,
            close.map(tokens(_).until).getOrElse(base + source.length),
            tokens(relation.get).text == "!="
          )
      else if word.text == "(" then
        val close = matching(tokens, i, "(", ")")
        val stop = close.getOrElse(tokens.size)
        val colon = topLevel(tokens, i + 1, ":", stop)
        val operator = tokens
          .slice(i + 1, stop)
          .exists(t => t.kind == BendTokens.Operator && t.text != "->")
        if operator && colon.nonEmpty then
          result += BendProofForm.OperatorAnnotation(
            word.from,
            close.map(tokens(_).until).getOrElse(base + source.length),
            colon.map(j =>
              (
                tokens(j).until,
                close.map(tokens(_).from).getOrElse(base + source.length)
              )
            )
          )
    result.result()

  private def matching(
      tokens: Vector[Word],
      at: Int,
      open: String,
      close: String
  ): Option[Int] =
    var depth = 0
    var i = at
    while i < tokens.size do
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
      until: Int = Int.MaxValue
  ): Option[Int] = topLevel(tokens, from, Set(wanted), until)

  private def topLevel(
      tokens: Vector[Word],
      from: Int,
      wanted: Set[String],
      until: Int
  ): Option[Int] =
    var depth = 0
    var i = from
    while i < math.min(tokens.size, until) do
      val word = tokens(i).text
      if depth == 0 && wanted.contains(word) then return Some(i)
      word match
        case "(" | "[" | "{" => depth += 1
        case ")" | "]" | "}" => depth = math.max(0, depth - 1)
        case _               => ()
      i += 1
    None
