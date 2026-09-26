package com.dearlordylord.bend.idea.syntax.psi

import com.dearlordylord.bend.idea.syntax.BendLanguage
import com.dearlordylord.bend.idea.syntax.lexer.{BendLexer, BendTokens}
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.{PsiFile, PsiFileFactory, TokenType}
import com.intellij.psi.util.PsiTreeUtil
import scala.collection.mutable
import scala.jdk.CollectionConverters.*

final case class BendForeignPath(
    spelling: String,
    range: TextRange,
    quote: Char
)

final case class BendForeignPathCompletionSite(
    range: TextRange,
    quote: Char,
    prefix: String
)

/** Source projection for the quoted foreign assets in zero-template def bodies.
  * It never treats leading Bend module imports or unrelated literals as foreign
  * paths.
  */
object BendForeignPaths:
  private final case class Token(
      kind: com.intellij.psi.tree.IElementType,
      text: String,
      start: Int,
      end: Int
  )

  private final case class Parsed(
      paths: List[BendForeignPath],
      completion: Option[BendForeignPathCompletionSite]
  )

  def in(file: PsiFile): List[BendForeignPath] =
    PsiTreeUtil
      .findChildrenOfType(file, classOf[BendDefinition])
      .asScala
      .toList
      .flatMap(inDefinition)

  def inDefinition(definition: BendDefinition): List[BendForeignPath] =
    if definition.parameters.exists(_.template) then Nil
    else
      val file = definition.getContainingFile
      if file == null then Nil
      else parse(file.getText, definition, None).paths

  def inSource(project: Project, source: String): List[BendForeignPath] =
    val file = PsiFileFactory
      .getInstance(project)
      .createFileFromText("loaded.bend", BendLanguage.instance, source)
    in(file)

  def completionSite(
      file: PsiFile,
      offset: Int
  ): Option[BendForeignPathCompletionSite] =
    PsiTreeUtil
      .findChildrenOfType(file, classOf[BendDefinition])
      .asScala
      .iterator
      .filterNot(_.parameters.exists(_.template))
      .flatMap(definition =>
        parse(file.getText, definition, Some(offset)).completion
      )
      .take(1)
      .toList
      .headOption

  def completionSiteInSource(
      project: Project,
      source: String,
      offset: Int
  ): Option[BendForeignPathCompletionSite] =
    val file = PsiFileFactory
      .getInstance(project)
      .createFileFromText("completion.bend", BendLanguage.instance, source)
    completionSite(file, offset)

  /** Bend resolves a foreign spelling against the directory of the source file
    * that declares it, including when that source was imported.
    */
  def resolve(
      sourcePath: String,
      spelling: String
  ): Option[java.nio.file.Path] =
    try
      val source = java.nio.file.Path.of(sourcePath).toAbsolutePath.normalize()
      val directory = source.getParent
      if directory == null then None
      else
        val relative = java.nio.file.Path.of(spelling)
        val sourceRelative = if relative.isAbsolute then
          relative.getRoot.relativize(relative)
        else relative
        Some(
          directory.resolve(sourceRelative).normalize()
        )
    catch case _: Exception => None

  private def parse(
      source: String,
      definition: BendDefinition,
      completionOffset: Option[Int]
  ): Parsed =
    val header = PsiTreeUtil.getChildOfType(definition, classOf[BendHeader])
    val start = if header == null then definition.getTextRange.getStartOffset
    else header.getTextRange.getEndOffset
    val end = definition.getTextRange.getEndOffset
    if start < 0 || end > source.length || start >= end then
      return Parsed(Nil, None)
    val lexer = new BendLexer()
    lexer.start(source, start, end, 0)
    val tokens = mutable.ArrayBuffer.empty[Token]
    while lexer.getTokenType != null do
      tokens += Token(
        lexer.getTokenType,
        source.substring(lexer.getTokenStart, lexer.getTokenEnd),
        lexer.getTokenStart,
        lexer.getTokenEnd
      )
      lexer.advance()

    val paths = mutable.ListBuffer.empty[BendForeignPath]
    var completion: Option[BendForeignPathCompletionSite] = None
    var index = 0
    def skipTrivia(): Unit =
      while index < tokens.size &&
        (tokens(index).kind == TokenType.WHITE_SPACE ||
          tokens(index).kind == BendTokens.Comment)
      do index += 1

    skipTrivia()
    while index < tokens.size do
      if tokens(index).kind != BendTokens.Keyword || tokens(
          index
        ).text != "import"
      then return Parsed(Nil, None)
      index += 1
      skipTrivia()
      if index >= tokens.size || tokens(
          index
        ).kind != BendTokens.StringDelimiter
      then return Parsed(Nil, None)
      val open = tokens(index)
      val quote = open.text.headOption.getOrElse('"')
      val contentStart = open.end
      index += 1
      val decoded = new StringBuilder
      var closed = false
      var contentEnd = contentStart
      var invalid = false
      var activeCompletion: Option[BendForeignPathCompletionSite] = None
      while index < tokens.size && !closed && !invalid do
        val token = tokens(index)
        if token.kind == BendTokens.StringDelimiter && token.text == quote.toString
        then
          contentEnd = token.start
          completionOffset
            .filter(offset => offset >= contentStart && offset <= contentEnd)
            .foreach { offset =>
              activeCompletion = Some(
                BendForeignPathCompletionSite(
                  new TextRange(contentStart, contentEnd),
                  quote,
                  source.substring(contentStart, offset)
                )
              )
            }
          closed = true
        else if token.kind == BendTokens.StringContent then
          if activeCompletion.isEmpty then
            completionOffset
              .filter(offset => offset >= token.start && offset <= token.end)
              .foreach { offset =>
                activeCompletion = Some(
                  BendForeignPathCompletionSite(
                    new TextRange(contentStart, token.end),
                    quote,
                    source.substring(contentStart, offset)
                  )
                )
              }
          decoded.append(token.text)
          contentEnd = token.end
        else if token.kind == BendTokens.Escape then
          decodeEscape(token.text) match
            case Some(value) => decoded.append(value)
            case None        => invalid = true
          contentEnd = token.end
        else invalid = true
        if !closed && !invalid then index += 1
      if invalid then return Parsed(Nil, activeCompletion)
      if !closed then
        val site = completionOffset
          .filter(offset => offset >= contentStart && offset <= contentEnd)
          .map(offset =>
            BendForeignPathCompletionSite(
              new TextRange(contentStart, contentEnd),
              quote,
              source.substring(contentStart, offset)
            )
          )
          .orElse(activeCompletion)
        return Parsed(Nil, site)
      activeCompletion.foreach { site =>
        completion = Some(
          site.copy(range = new TextRange(contentStart, contentEnd))
        )
      }
      val spelling = decoded.toString
      if (spelling.endsWith(".c") || spelling.endsWith(".js")) &&
        !spelling.exists(c => c == '\n' || c == '\r' || c == '\u0000')
      then
        paths += BendForeignPath(
          spelling,
          new TextRange(contentStart, contentEnd),
          quote
        )
      index += 1
      skipTrivia()
    Parsed(paths.toList, completion)

  private def decodeEscape(value: String): Option[String] =
    if value.length == 2 then
      value.charAt(1) match
        case 'n'  => Some("\n")
        case 't'  => Some("\t")
        case 'r'  => Some("\r")
        case '0'  => Some("\u0000")
        case '\\' => Some("\\")
        case '\'' => Some("'")
        case '"'  => Some("\"")
        case _    => None
    else if value.startsWith("\\u{") && value.endsWith("}") then
      try
        val point = Integer.parseInt(value.substring(3, value.length - 1), 16)
        Some(new String(Character.toChars(point)))
      catch case _: Exception => None
    else None
