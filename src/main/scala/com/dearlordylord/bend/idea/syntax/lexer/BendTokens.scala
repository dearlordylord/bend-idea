package com.dearlordylord.bend.idea.syntax.lexer

import com.dearlordylord.bend.idea.syntax.BendLanguage
import com.intellij.psi.tree.IElementType

/** Shared lexical vocabulary for highlighting and later parser/completion
  * consumers.
  */
object BendTokens:
  private def token(name: String): IElementType =
    new IElementType(name, BendLanguage.instance)

  val Comment = token("COMMENT")
  val StringDelimiter = token("STRING_DELIMITER")
  val StringContent = token("STRING_CONTENT")
  val Escape = token("ESCAPE")
  val InvalidEscape = token("INVALID_ESCAPE")
  val DeclarationKeyword = token("DECLARATION_KEYWORD")
  val Keyword = token("KEYWORD")
  val Unsafe = token("UNSAFE")
  val FunctionName = token("FUNCTION_NAME")
  val TypeName = token("TYPE_NAME")
  val NamespaceName = token("NAMESPACE_NAME")
  val ImportPath = token("IMPORT_PATH")
  val BuiltinType = token("BUILTIN_TYPE")
  val Identifier = token("IDENTIFIER")
  val Number = token("NUMBER")
  val Quantity = token("QUANTITY")
  val Hole = token("HOLE")
  val Wildcard = token("WILDCARD")
  val Rewrite = token("REWRITE")
  val Operator = token("OPERATOR")
  val LeftParen = token("LEFT_PAREN")
  val RightParen = token("RIGHT_PAREN")
  val LeftBrace = token("LEFT_BRACE")
  val RightBrace = token("RIGHT_BRACE")
  val LeftBracket = token("LEFT_BRACKET")
  val RightBracket = token("RIGHT_BRACKET")
  val LeftAngle = token("LEFT_ANGLE")
  val RightAngle = token("RIGHT_ANGLE")
  val Separator = token("SEPARATOR")
