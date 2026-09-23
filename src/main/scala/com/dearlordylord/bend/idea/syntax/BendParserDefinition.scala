package com.dearlordylord.bend.idea.syntax

import com.dearlordylord.bend.idea.syntax.lexer.{BendLexer, BendTokens}
import com.intellij.lang.{ASTNode, ParserDefinition, PsiBuilder, PsiParser}
import com.intellij.lexer.Lexer
import com.intellij.openapi.project.Project
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.psi.{FileViewProvider, PsiElement, PsiFile, TokenType}
import com.intellij.extapi.psi.{ASTWrapperPsiElement, PsiFileBase}
import com.intellij.psi.tree.{IElementType, IFileElementType, TokenSet}

/** A file root and token stream until the tolerant declaration parser arrives in #5. */
final class BendParserDefinition extends ParserDefinition:
  override def createLexer(project: Project): Lexer = new BendLexer()
  override def createParser(project: Project): PsiParser = new PsiParser:
    override def parse(root: IElementType, builder: PsiBuilder): ASTNode =
      val file = builder.mark()
      while !builder.eof() do builder.advanceLexer()
      file.done(root)
      builder.getTreeBuilt
  override def getFileNodeType: IFileElementType = BendParserDefinition.File
  override def getCommentTokens: TokenSet = TokenSet.create(BendTokens.Comment)
  override def getWhitespaceTokens: TokenSet = TokenSet.create(TokenType.WHITE_SPACE)
  override def getStringLiteralElements: TokenSet = TokenSet.create(BendTokens.StringDelimiter, BendTokens.StringContent, BendTokens.Escape, BendTokens.InvalidEscape)
  override def createElement(node: ASTNode): PsiElement = new ASTWrapperPsiElement(node)
  override def createFile(viewProvider: FileViewProvider): PsiFile = new BendFile(viewProvider)

object BendParserDefinition:
  val File: IFileElementType = new IFileElementType(BendLanguage.instance)

final class BendFile(viewProvider: FileViewProvider) extends PsiFileBase(viewProvider, BendLanguage.instance):
  override def getFileType: BendFileType = FileTypeManager.getInstance.getFileTypeByExtension("bend").asInstanceOf[BendFileType]
