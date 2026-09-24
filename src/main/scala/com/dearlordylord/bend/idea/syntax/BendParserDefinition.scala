package com.dearlordylord.bend.idea.syntax

import com.dearlordylord.bend.idea.syntax.lexer.{BendLexer, BendTokens}
import com.dearlordylord.bend.idea.syntax.parser.BendSurfaceParser
import com.dearlordylord.bend.idea.syntax.psi.*
import com.intellij.lang.{ASTNode, ParserDefinition, PsiParser}
import com.intellij.lexer.Lexer
import com.intellij.openapi.project.Project
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.psi.{FileViewProvider, PsiElement, PsiFile, TokenType}
import com.intellij.extapi.psi.{ASTWrapperPsiElement, PsiFileBase}
import com.intellij.psi.tree.{IElementType, IFileElementType, TokenSet}

/** File root, shared lexer and tolerant declaration PSI. */
final class BendParserDefinition extends ParserDefinition:
  override def createLexer(project: Project): Lexer = new BendLexer()
  override def createParser(project: Project): PsiParser =
    new BendSurfaceParser()
  override def getFileNodeType: IFileElementType = BendParserDefinition.File
  override def getCommentTokens: TokenSet = TokenSet.create(BendTokens.Comment)
  override def getWhitespaceTokens: TokenSet =
    TokenSet.create(TokenType.WHITE_SPACE)
  override def getStringLiteralElements: TokenSet = TokenSet.create(
    BendTokens.StringDelimiter,
    BendTokens.StringContent,
    BendTokens.Escape,
    BendTokens.InvalidEscape
  )
  override def createElement(node: ASTNode): PsiElement =
    node.getElementType match
      case BendElements.Name        => new BendName(node)
      case BendElements.Reference   => new BendReferenceElement(node)
      case BendElements.Alias       => new BendAlias(node)
      case BendElements.Header      => new BendHeader(node)
      case BendElements.Definition  => new BendDefinition(node)
      case BendElements.Datatype    => new BendDatatype(node)
      case BendElements.Law         => new BendLaw(node)
      case BendElements.Constructor => new BendConstructor(node)
      case _                        => new ASTWrapperPsiElement(node)
  override def createFile(viewProvider: FileViewProvider): PsiFile =
    new BendFile(viewProvider)

object BendParserDefinition:
  val File: IFileElementType = new IFileElementType(BendLanguage.instance)

final class BendFile(viewProvider: FileViewProvider)
    extends PsiFileBase(viewProvider, BendLanguage.instance):
  override def getReferences: Array[com.intellij.psi.PsiReference] =
    com.intellij.psi.impl.source.resolve.reference.ReferenceProvidersRegistry
      .getReferencesFromProviders(this)
  override def getFileType: BendFileType = FileTypeManager.getInstance
    .getFileTypeByExtension("bend")
    .asInstanceOf[BendFileType]
