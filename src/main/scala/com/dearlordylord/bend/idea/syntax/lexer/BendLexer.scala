package com.dearlordylord.bend.idea.syntax.lexer

import com.intellij.lexer.LexerBase
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType

/** UTF-16 lexer. State encodes literal mode and the name expected after a declaration/import.
  * Each token begins at the previous token's end, including malformed and unfinished source.
  */
final class BendLexer extends LexerBase:
  private val Normal = 0
  private val Double = 1
  private val Single = 2
  private val NoName = 0
  private val Function = 1
  private val Type = 2
  private val Namespace = 3
  private val Path = 4
  private val ImportContext = 1 << 5

  private var input: CharSequence = ""
  private var end = 0
  private var startOffset = 0
  private var tokenEnd = 0
  private var state = 0
  private var nextState = 0
  private var kind: IElementType = null

  override def start(buffer: CharSequence, start: Int, endOffset: Int, initialState: Int): Unit =
    input = buffer
    end = endOffset
    startOffset = start
    state = initialState
    scan()

  override def getState: Int = state
  override def getTokenType: IElementType = kind
  override def getTokenStart: Int = startOffset
  override def getTokenEnd: Int = tokenEnd
  override def getBufferSequence: CharSequence = input
  override def getBufferEnd: Int = end

  override def advance(): Unit =
    startOffset = tokenEnd
    state = nextState
    scan()

  private def char(at: Int): Char = if at < end then input.charAt(at) else 0.toChar
  private def head(c: Char): Boolean = c == '_' || c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z'
  private def digit(c: Char): Boolean = c >= '0' && c <= '9'
  private def hex(c: Char): Boolean = digit(c) || c >= 'a' && c <= 'f' || c >= 'A' && c <= 'F'
  private def name(c: Char): Boolean = head(c) || digit(c) || c == '.'
  private def space(c: Char): Boolean = c == ' ' || c == '\t' || c == '\r' || c == '\n'
  private def mode: Int = state & 3
  private def expected: Int = (state >> 2) & 7
  private def inImport: Boolean = (state & ImportContext) != 0
  private def withExpected(value: Int): Unit = nextState = (nextState & ~28) | (value << 2)
  private def withMode(value: Int): Unit = nextState = (nextState & ~3) | value

  private def scan(): Unit =
    tokenEnd = startOffset
    nextState = state
    kind = null
    if startOffset >= end then return
    if mode != Normal then scanLiteral()
    else scanNormal()
    if tokenEnd <= startOffset then
      tokenEnd = startOffset + 1
      kind = TokenType.BAD_CHARACTER

  private def scanLiteral(): Unit =
    val c = char(startOffset)
    val quote = if mode == Double then '"' else '\''
    if c == quote then
      tokenEnd += 1
      kind = BendTokens.StringDelimiter
      withMode(Normal)
    else if c == '\\' then
      tokenEnd += 1
      if char(tokenEnd) == 'u' && char(tokenEnd + 1) == '{' then
        var p = tokenEnd + 2
        while hex(char(p)) && p < end do p += 1
        if p > tokenEnd + 2 && char(p) == '}' then
          tokenEnd = p + 1
          kind = BendTokens.Escape
        else
          tokenEnd = p
          kind = BendTokens.InvalidEscape
      else if "ntr0\\'\"".contains(char(tokenEnd)) && tokenEnd < end then
        tokenEnd += 1
        kind = BendTokens.Escape
      else
        if tokenEnd < end then tokenEnd += 1
        kind = BendTokens.InvalidEscape
    else
      while tokenEnd < end && char(tokenEnd) != quote && char(tokenEnd) != '\\' do
        tokenEnd += 1
      kind = BendTokens.StringContent

  private def scanNormal(): Unit =
    val c = char(startOffset)
    if space(c) then
      while space(char(tokenEnd)) && tokenEnd < end do tokenEnd += 1
      kind = TokenType.WHITE_SPACE
      var p = startOffset
      while p < tokenEnd do
        if char(p) == '\r' || char(p) == '\n' then
          nextState &= ~ImportContext
          if expected == Path || expected == Namespace then withExpected(NoName)
        p += 1
    else if c == '#' then
      while tokenEnd < end && char(tokenEnd) != '\r' && char(tokenEnd) != '\n' do tokenEnd += 1
      kind = BendTokens.Comment
    else if c == '"' || c == '\'' then
      tokenEnd += 1
      kind = BendTokens.StringDelimiter
      withMode(if c == '"' then Double else Single)
      withExpected(NoName)
    else if expected == Path && c != '#' then
      while tokenEnd < end && !space(char(tokenEnd)) && char(tokenEnd) != '#' do tokenEnd += 1
      kind = BendTokens.ImportPath
      withExpected(NoName)
    else if c == '&' && "012".contains(char(startOffset + 1)) && !name(char(startOffset + 2)) then
      tokenEnd += 2
      kind = BendTokens.Quantity
    else if c == '?' && head(char(startOffset + 1)) then
      tokenEnd += 1
      while tokenEnd < end && name(char(tokenEnd)) do tokenEnd += 1
      kind = BendTokens.Hole
    else if digit(c) then scanNumber()
    else if head(c) then scanName()
    else if c == '@' && startsWith("@unsafe") && !name(char(startOffset + 7)) then
      tokenEnd += 7
      kind = BendTokens.Unsafe
    else if startsWith("{==}") then
      tokenEnd += 4
      kind = BendTokens.Rewrite
    else if "(){}[]".contains(c) then
      tokenEnd += 1
      kind = BendTokens.Bracket
    else if ",;:".contains(c) then
      tokenEnd += 1
      kind = BendTokens.Separator
    else if "+-*/%=!~@&|<>^\\.".contains(c) then
      tokenEnd += 1
      while tokenEnd < end && "+-*/%=!~@&|<>^\\.".contains(char(tokenEnd)) do tokenEnd += 1
      kind = BendTokens.Operator
    else
      tokenEnd += (if Character.isHighSurrogate(c) && Character.isLowSurrogate(char(startOffset + 1)) then 2 else 1)
      kind = TokenType.BAD_CHARACTER

  private def startsWith(value: String): Boolean =
    startOffset + value.length <= end && input.subSequence(startOffset, startOffset + value.length).toString == value

  private def scanName(): Unit =
    while tokenEnd < end && name(char(tokenEnd)) do tokenEnd += 1
    // The parser consumes the entire lexeme then rejects names ending in a dot.
    if char(tokenEnd - 1) == '.' then
      kind = TokenType.BAD_CHARACTER
      withExpected(NoName)
      return
    val word = input.subSequence(startOffset, tokenEnd).toString
    // Reserved declaration words cannot be names. Recover from an unfinished
    // preceding declaration without losing its next valid name after whitespace.
    if word == "def" || word == "law" then
      kind = BendTokens.DeclarationKeyword
      withExpected(Function)
    else if word == "type" then
      kind = BendTokens.DeclarationKeyword
      withExpected(Type)
    else if word == "import" then
      kind = BendTokens.Keyword
      nextState |= ImportContext
      withExpected(Path)
    else if expected == Function then kind = BendTokens.FunctionName
    else if expected == Type then kind = BendTokens.TypeName
    else if expected == Namespace then kind = BendTokens.NamespaceName
    else if word == "as" && inImport then
      kind = BendTokens.Keyword
      withExpected(Namespace)
    else if Set("match", "case", "do", "return", "for", "exs", "where", "is").contains(word) then kind = BendTokens.Keyword
    else if Set("Type", "Data", "Kind", "Quant", "Nat", "U32", "F32", "Char", "String", "Bool", "Unit", "Empty", "IO", "List", "Array", "Maybe", "Result", "Either", "Sigma", "Word").contains(word) then kind = BendTokens.BuiltinType
    else if word == "_" then kind = BendTokens.Wildcard
    else kind = BendTokens.Identifier
    if expected != NoName && !Set("def", "law", "type", "import").contains(word) then withExpected(NoName)

  private def scanNumber(): Unit =
    while digit(char(tokenEnd)) && tokenEnd < end do tokenEnd += 1
    if char(tokenEnd) == 'n' && !name(char(tokenEnd + 1)) then tokenEnd += 1
    else if char(tokenEnd) == '.' && digit(char(tokenEnd + 1)) then
      tokenEnd += 1
      while digit(char(tokenEnd)) && tokenEnd < end do tokenEnd += 1
      if char(tokenEnd) == 'e' || char(tokenEnd) == 'E' then
        val sign = if char(tokenEnd + 1) == '+' || char(tokenEnd + 1) == '-' then 1 else 0
        if digit(char(tokenEnd + 1 + sign)) then
          tokenEnd += 1 + sign
          while digit(char(tokenEnd)) && tokenEnd < end do tokenEnd += 1
    kind = BendTokens.Number
