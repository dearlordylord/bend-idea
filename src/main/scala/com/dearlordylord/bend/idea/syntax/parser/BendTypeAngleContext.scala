package com.dearlordylord.bend.idea.syntax.parser

import com.dearlordylord.bend.idea.syntax.psi.{
  BendConstructor,
  BendDatatype,
  BendHeader
}
import com.dearlordylord.bend.idea.syntax.lexer.{BendLexer, BendTokens}
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import scala.collection.mutable

/** A deliberately narrow syntax judgment for editing type arguments in
  * declaration headers.
  */
object BendTypeAngleContext:
  /** Paired type angles from the tolerant declaration and do-type surface.
    * Other operators stay unmatched.
    */
  def matchedPairs(source: CharSequence): Map[Int, Int] =
    val lexer = new BendLexer()
    lexer.start(source)
    val opens = mutable.ArrayBuffer.empty[Int]
    val pairs = mutable.Map.empty[Int, Int]
    var header = false
    var doType = false
    var datatypeBody = false
    var round = 0
    var square = 0
    var curly = 0
    while lexer.getTokenType != null do
      val start = lexer.getTokenStart
      val end = lexer.getTokenEnd
      val kind = lexer.getTokenType
      if kind == BendTokens.DeclarationKeyword then
        val lineStart = lineBeginning(source, start)
        val before = source.subSequence(lineStart, start).toString
        if before.isEmpty || before.matches("@unsafe\\s+") then
          header = true
          doType = false
          datatypeBody = source.subSequence(start, end).toString == "type"
          opens.clear()
          round = 0
          square = 0
          curly = 0
      if kind == BendTokens.Keyword && source
          .subSequence(start, end)
          .toString == "do"
      then doType = true
      kind match
        case com.intellij.psi.TokenType.WHITE_SPACE
            if doType && opens.isEmpty &&
              source
                .subSequence(start, end)
                .toString
                .exists(c => c == '\n' || c == '\r') =>
          doType = false
        case BendTokens.LeftParen    => round += 1
        case BendTokens.RightParen   => round = math.max(0, round - 1)
        case BendTokens.LeftBracket  => square += 1
        case BendTokens.RightBracket => square = math.max(0, square - 1)
        case BendTokens.LeftBrace    => curly += 1
        case BendTokens.RightBrace   => curly = math.max(0, curly - 1)
        case BendTokens.LeftAngle
            if (header || doType || datatypeBody && curly > 0) &&
              typeNameBefore(source, start) =>
          opens += start
        case BendTokens.RightAngle if opens.nonEmpty =>
          val open = opens.remove(opens.size - 1)
          pairs(open) = start
          pairs(start) = open
        case BendTokens.Separator
            if source.charAt(start) == ':' &&
              round == 0 && square == 0 && curly == 0 && opens.isEmpty =>
          header = false
          doType = false
        case _ => ()
      lexer.advance()
    pairs.toMap

  private def lineBeginning(source: CharSequence, offset: Int): Int =
    var at = offset - 1
    while at >= 0 && source.charAt(at) != '\n' && source.charAt(at) != '\r' do
      at -= 1
    at + 1

  private def typeNameBefore(source: CharSequence, offset: Int): Boolean =
    var at = offset - 1
    while at >= 0 && (source.charAt(at).isLetterOrDigit || source.charAt(
        at
      ) == '_' || source.charAt(at) == '.')
    do at -= 1
    val word = source.subSequence(at + 1, offset).toString
    word.nonEmpty && word.head.isUpper && !word.endsWith(".")

  private def headerAt(file: PsiFile, offset: Int): Option[BendHeader] =
    if offset <= 0 || offset > file.getTextLength then return None
    val leaf = file.findElementAt(offset - 1)
    if leaf == null then return None
    val header = PsiTreeUtil.getParentOfType(leaf, classOf[BendHeader], false)
    Option(header).filter(offset <= _.getTextRange.getEndOffset)

  private def constructorFieldAt(file: PsiFile, offset: Int): Boolean =
    val leaf = file.findElementAt(offset - 1)
    if leaf == null then return false
    val constructor =
      PsiTreeUtil.getParentOfType(leaf, classOf[BendConstructor], false)
    if constructor == null then return false
    val source = constructor.getText
    val until = offset - constructor.getTextRange.getStartOffset
    val open = source.indexOf('{')
    if open < 0 || until <= open || until > source.length then return false
    var angleDepth = 0
    var fieldColon = false
    var at = open + 1
    while at < until do
      source.charAt(at) match
        case '<'                    => angleDepth += 1
        case '>' if angleDepth > 0  => angleDepth -= 1
        case ',' if angleDepth == 0 => fieldColon = false
        case ':' if angleDepth == 0 => fieldColon = true
        case '}' if angleDepth == 0 => return false
        case _                      => ()
      at += 1
    fieldColon

  private def doTypeAt(source: String, offset: Int): Boolean =
    val lexer = new BendLexer()
    lexer.start(source)
    var active = false
    var angleDepth = 0
    while lexer.getTokenType != null && lexer.getTokenStart < offset do
      val kind = lexer.getTokenType
      val start = lexer.getTokenStart
      val end = math.min(lexer.getTokenEnd, offset)
      if kind == com.intellij.psi.TokenType.WHITE_SPACE && angleDepth == 0 &&
        source.substring(start, end).exists(c => c == '\n' || c == '\r')
      then active = false
      else if kind == BendTokens.Keyword && source.substring(start, end) == "do"
      then
        active = true
        angleDepth = 0
      else if active then
        kind match
          case BendTokens.LeftAngle  => angleDepth += 1
          case BendTokens.RightAngle => angleDepth = math.max(0, angleDepth - 1)
          case BendTokens.Separator
              if source.charAt(start) == ':' && angleDepth == 0 =>
            active = false
          case _ => ()
      lexer.advance()
    active

  def at(file: PsiFile, offset: Int): Boolean =
    if offset <= 0 || offset > file.getTextLength then return false
    val source = file.getText
    val header = headerAt(file, offset).orNull
    val lineStart = source.lastIndexOf('\n', offset - 1) + 1
    val prefix = source.substring(
      if header == null then lineStart else header.getTextRange.getStartOffset,
      offset
    )
    val name =
      "([A-Za-z_][A-Za-z0-9_.]*)$".r.findFirstMatchIn(prefix).map(_.group(1))
    val datatype = header != null && PsiTreeUtil.getParentOfType(
      header,
      classOf[BendDatatype],
      false
    ) != null
    val inDoType = header == null && doTypeAt(source, offset)
    name.exists(word =>
      (header != null || inDoType || constructorFieldAt(file, offset)) &&
        (word.head.isUpper || datatype && prefix.matches(
          "(?s).*\\btype\\s+" + java.util.regex.Pattern.quote(word)
        ))
    )
