package com.dearlordylord.bend.idea.syntax.parser

import com.dearlordylord.bend.idea.syntax.lexer.{BendTokens, BendWords}
import com.dearlordylord.bend.idea.syntax.psi.BendElements
import com.intellij.lang.{ASTNode, PsiBuilder, PsiParser}
import com.intellij.psi.tree.IElementType

/** Tolerant declaration grammar. A column-zero declaration always ends the preceding item,
  * including an unfinished header or body. Expressions remain opaque at this stage.
  */
final class BendSurfaceParser extends PsiParser:
  override def parse(root: IElementType, builder: PsiBuilder): ASTNode =
    val file = builder.mark()
    while !builder.eof() do
      if declarationStart(builder) then declaration(builder)
      else if builder.getTokenType == BendTokens.NamespaceName then alias(builder)
      else builder.advanceLexer()
    file.done(root)
    builder.getTreeBuilt

  private def text(builder: PsiBuilder): String = Option(builder.getTokenText).getOrElse("")
  private def lineStart(builder: PsiBuilder): Int =
    val source = builder.getOriginalText
    val offset = builder.getCurrentOffset
    var p = offset - 1
    while p >= 0 && source.charAt(p) != '\n' && source.charAt(p) != '\r' do p -= 1
    p + 1

  private def indentation(builder: PsiBuilder): Int =
    val source = builder.getOriginalText
    val begin = lineStart(builder)
    var p = begin
    while p < builder.getCurrentOffset && (source.charAt(p) == ' ' || source.charAt(p) == '\t') do p += 1
    if p == builder.getCurrentOffset then p - begin else -1

  private def declarationStart(builder: PsiBuilder): Boolean =
    indentation(builder) == 0 &&
      ((builder.getTokenType == BendTokens.DeclarationKeyword && BendWords.declaration(text(builder))) ||
        (builder.getTokenType == BendTokens.Unsafe && builder.lookAhead(1) == BendTokens.DeclarationKeyword))

  private def nameToken(builder: PsiBuilder): Boolean =
    Set(BendTokens.FunctionName, BendTokens.TypeName, BendTokens.Identifier, BendTokens.BuiltinType,
      BendTokens.NamespaceName).contains(builder.getTokenType) &&
      text(builder).matches("[A-Za-z_][A-Za-z0-9_.]*") && !text(builder).endsWith(".") && !BendWords.reserved(text(builder))

  private def advanceReference(builder: PsiBuilder): Unit =
    val incompleteQualified = builder.getTokenType == com.intellij.psi.TokenType.BAD_CHARACTER &&
      text(builder).matches("[A-Za-z_][A-Za-z0-9_.]*\\.")
    if builder.getTokenType == BendTokens.NamespaceName then alias(builder)
    else if nameToken(builder) || incompleteQualified then
      val reference = builder.mark()
      builder.advanceLexer()
      reference.done(BendElements.Reference)
    else builder.advanceLexer()

  private def alias(builder: PsiBuilder): Unit =
    val marker = builder.mark()
    builder.advanceLexer()
    marker.done(BendElements.Alias)

  private def declaration(builder: PsiBuilder): Unit =
    val item = builder.mark()
    val header = builder.mark()
    if builder.getTokenType == BendTokens.Unsafe then builder.advanceLexer()
    val kind = text(builder)
    builder.advanceLexer()
    if !builder.eof() && !declarationStart(builder) && nameToken(builder) then
      val name = builder.mark()
      builder.advanceLexer()
      name.done(BendElements.Name)
    val stack = scala.collection.mutable.ArrayBuffer.empty[String]
    var colonFound = false
    while !builder.eof() && !declarationStart(builder) && !colonFound do
      val word = text(builder)
      word match
        case "(" => stack += ")"
        case "{" => stack += "}"
        case "[" => stack += "]"
        case close if stack.lastOption.contains(close) => stack.remove(stack.size - 1)
        case ":" if stack.isEmpty => colonFound = true
        case _ if Set(BendTokens.Operator, BendTokens.LeftAngle, BendTokens.RightAngle).contains(builder.getTokenType) =>
          val source = builder.getOriginalText
          for i <- word.indices do
            val c = word.charAt(i)
            val before = builder.getCurrentOffset + i - 1
            if c == '<' && (stack.isEmpty || stack.lastOption.contains(">")) && before >= 0 &&
              (Character.isLetterOrDigit(source.charAt(before)) || source.charAt(before) == '_' || source.charAt(before) == '>' || source.charAt(before) == ')') then stack += ">"
            else if c == '>' && (i == 0 || word.charAt(i - 1) != '-') && stack.lastOption.contains(">") then stack.remove(stack.size - 1)
        case _ => ()
      advanceReference(builder)
    header.done(BendElements.Header)
    while !builder.eof() && !declarationStart(builder) do
      if kind == "type" && colonFound && constructorStart(builder) then constructor(builder)
      else advanceReference(builder)
    item.done(kind match
      case "type" => BendElements.Datatype
      case "law" => BendElements.Law
      case _ => BendElements.Definition)

  private def constructorStart(builder: PsiBuilder): Boolean =
    indentation(builder) > 0 && nameToken(builder) && builder.lookAhead(1) == BendTokens.LeftBrace &&
      Option(builder.getOriginalText).exists { source =>
        var next = builder.getCurrentOffset + text(builder).length
        while next < source.length && (source.charAt(next) == ' ' || source.charAt(next) == '\t') do next += 1
        next < source.length && source.charAt(next) == '{'
      }

  private def constructor(builder: PsiBuilder): Unit =
    val item = builder.mark()
    val name = builder.mark()
    builder.advanceLexer()
    name.done(BendElements.Name)
    var depth = 0
    var started = false
    while !builder.eof() && !declarationStart(builder) && (!started || depth > 0) do
      text(builder) match
        case "{" => depth += 1; started = true
        case "}" => depth -= 1
        case _ => ()
      advanceReference(builder)
    item.done(BendElements.Constructor)
