package com.dearlordylord.bend.idea.syntax.lexer

import com.intellij.lexer.Lexer
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors as Colors
import com.intellij.openapi.editor.HighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.{
  SyntaxHighlighter,
  SyntaxHighlighterBase,
  SyntaxHighlighterFactory
}
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType

object BendColors:
  private def key(
      name: String,
      fallback: TextAttributesKey
  ): TextAttributesKey =
    TextAttributesKey.createTextAttributesKey(s"BEND_$name", fallback)
  val Keyword = key("KEYWORD", Colors.KEYWORD)
  val Declaration = key("DECLARATION", Colors.KEYWORD)
  val Function = key("FUNCTION", Colors.FUNCTION_DECLARATION)
  val Type = key("TYPE", Colors.CLASS_NAME)
  val Namespace = key("NAMESPACE", Colors.CLASS_NAME)
  val Builtin = key("BUILTIN", Colors.CLASS_NAME)
  val Identifier = key("IDENTIFIER", Colors.IDENTIFIER)
  val Number = key("NUMBER", Colors.NUMBER)
  val Quantity = key("QUANTITY", Colors.NUMBER)
  val String = key("STRING", Colors.STRING)
  val Escape = key("ESCAPE", Colors.VALID_STRING_ESCAPE)
  val InvalidEscape = key("INVALID_ESCAPE", Colors.INVALID_STRING_ESCAPE)
  val Comment = key("COMMENT", Colors.LINE_COMMENT)
  val Hole = key("HOLE", Colors.INSTANCE_FIELD)
  val Rewrite = key("REWRITE", Colors.KEYWORD)
  val Operator = key("OPERATOR", Colors.OPERATION_SIGN)
  val Bracket = key("BRACKET", Colors.BRACES)
  val Separator = key("SEPARATOR", Colors.COMMA)
  val Bad = key("BAD", HighlighterColors.BAD_CHARACTER)
  val SemanticLocal = key("SEMANTIC_LOCAL", Colors.LOCAL_VARIABLE)
  val SemanticParameter = key("SEMANTIC_PARAMETER", Colors.PARAMETER)
  val SemanticConstructor = key("SEMANTIC_CONSTRUCTOR", Colors.FUNCTION_CALL)
  val SemanticDatatype = key("SEMANTIC_DATATYPE", Colors.CLASS_NAME)
  val SemanticLaw = key("SEMANTIC_LAW", Colors.CONSTANT)
  val SemanticDefinition = key("SEMANTIC_DEFINITION", Colors.FUNCTION_CALL)
  val SemanticAlias = key("SEMANTIC_ALIAS", Colors.CLASS_NAME)

final class BendSyntaxHighlighter extends SyntaxHighlighterBase:
  override def getHighlightingLexer: Lexer = new BendLexer()

  override def getTokenHighlights(
      tokenType: IElementType
  ): Array[TextAttributesKey] =
    val color =
      if tokenType == BendTokens.Comment then BendColors.Comment
      else if tokenType == BendTokens.StringDelimiter || tokenType == BendTokens.StringContent || tokenType == BendTokens.ImportPath
      then BendColors.String
      else if tokenType == BendTokens.Escape then BendColors.Escape
      else if tokenType == BendTokens.InvalidEscape then
        BendColors.InvalidEscape
      else if tokenType == BendTokens.DeclarationKeyword then
        BendColors.Declaration
      else if tokenType == BendTokens.Keyword || tokenType == BendTokens.Unsafe
      then BendColors.Keyword
      else if tokenType == BendTokens.FunctionName then BendColors.Function
      else if tokenType == BendTokens.TypeName then BendColors.Type
      else if tokenType == BendTokens.NamespaceName then BendColors.Namespace
      else if tokenType == BendTokens.BuiltinType then BendColors.Builtin
      else if tokenType == BendTokens.Identifier then BendColors.Identifier
      else if tokenType == BendTokens.Number then BendColors.Number
      else if tokenType == BendTokens.Quantity then BendColors.Quantity
      else if tokenType == BendTokens.Hole then BendColors.Hole
      else if tokenType == BendTokens.Rewrite || tokenType == BendTokens.Wildcard
      then BendColors.Rewrite
      else if tokenType == BendTokens.Operator || tokenType == BendTokens.LeftAngle ||
        tokenType == BendTokens.RightAngle
      then BendColors.Operator
      else if Set(
          BendTokens.LeftParen,
          BendTokens.RightParen,
          BendTokens.LeftBrace,
          BendTokens.RightBrace,
          BendTokens.LeftBracket,
          BendTokens.RightBracket
        ).contains(tokenType)
      then BendColors.Bracket
      else if tokenType == BendTokens.Separator then BendColors.Separator
      else if tokenType == TokenType.BAD_CHARACTER then BendColors.Bad
      else null
    if color == null then Array.empty else Array(color)

final class BendSyntaxHighlighterFactory extends SyntaxHighlighterFactory:
  override def getSyntaxHighlighter(
      project: Project,
      virtualFile: VirtualFile
  ): SyntaxHighlighter =
    new BendSyntaxHighlighter()
