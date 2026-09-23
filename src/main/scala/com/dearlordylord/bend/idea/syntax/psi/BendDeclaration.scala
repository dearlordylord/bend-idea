package com.dearlordylord.bend.idea.syntax.psi

import com.dearlordylord.bend.idea.syntax.lexer.BendWords
import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.lang.ASTNode
import com.intellij.psi.{PsiElement, PsiNameIdentifierOwner}
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.IncorrectOperationException
import com.intellij.psi.impl.source.resolve.reference.ReferenceProvidersRegistry

/** One source declaration; the name element remains an IntelliJ rename target.
  */
sealed abstract class BendDeclaration(node: ASTNode)
    extends ASTWrapperPsiElement(node)
    with PsiNameIdentifierOwner:
  override def getNameIdentifier: PsiElement =
    val header = PsiTreeUtil.getChildOfType(this, classOf[BendHeader])
    if header != null then PsiTreeUtil.getChildOfType(header, classOf[BendName])
    else PsiTreeUtil.getChildOfType(this, classOf[BendName])
  override def getName: String = Option(getNameIdentifier).map(_.getText).orNull
  override def setName(newName: String): PsiElement =
    if !newName.matches("[A-Za-z_][A-Za-z0-9_.]*") || newName.endsWith(
        "."
      ) || BendWords.reserved(newName)
    then throw new IncorrectOperationException("Invalid Bend declaration name")
    val original = getNameIdentifier
    if original == null then
      throw new IncorrectOperationException("Declaration has no name")
    val replacement = com.intellij.psi.PsiFileFactory
      .getInstance(getProject)
      .createFileFromText(
        "rename.bend",
        com.dearlordylord.bend.idea.syntax.BendLanguage.instance,
        s"def $newName():\n  0\n"
      )
    val declaration =
      PsiTreeUtil.findChildOfType(replacement, classOf[BendDefinition])
    original.replace(declaration.getNameIdentifier)
    this

  def headerText: String = Option(
    PsiTreeUtil.getChildOfType(this, classOf[BendHeader])
  ).map(_.getText.trim).getOrElse("")
  def sourceComments: String =
    val file = getContainingFile
    if file == null then ""
    else
      val source = file.getText
      val start = getTextRange.getStartOffset
      val lineStart = source.lastIndexOf('\n', start - 1) + 1
      val indent = source
        .substring(lineStart, start)
        .takeWhile(c => c == ' ' || c == '\t')
        .length
      val lines = source
        .substring(0, start)
        .split("\\r?\\n", -1)
        .toList
        .dropRight(1)
        .reverse
      lines
        .takeWhile { line =>
          val spaces = line.takeWhile(c => c == ' ' || c == '\t').length
          spaces == indent && line.drop(spaces).startsWith("#")
        }
        .reverse
        .map(_.trim.stripPrefix("#").stripPrefix(" "))
        .mkString("\n")

  def parameters: List[BendSourceParameter] =
    BendSourceParameter.fromHeader(headerText, this.isInstanceOf[BendDatatype])

  def proofForms: List[BendProofForm] =
    BendProofSurface.scan(getText, getTextRange.getStartOffset)

final class BendName(node: ASTNode)
    extends ASTWrapperPsiElement(node)
    with PsiNameIdentifierOwner:
  override def getNameIdentifier: PsiElement = this
  override def getName: String = getText
  override def setName(newName: String): PsiElement =
    val declaration =
      PsiTreeUtil.getParentOfType(this, classOf[BendDeclaration])
    if declaration == null then
      throw new IncorrectOperationException("Bend name has no declaration")
    declaration.setName(newName)
    declaration.getNameIdentifier
  override def getReferences: Array[com.intellij.psi.PsiReference] =
    ReferenceProvidersRegistry.getReferencesFromProviders(this)
final class BendReferenceElement(node: ASTNode)
    extends ASTWrapperPsiElement(node)
    with PsiNameIdentifierOwner:
  override def getNameIdentifier: PsiElement = this
  override def getName: String = getText
  override def setName(newName: String): PsiElement =
    if !newName.matches("[A-Za-z_][A-Za-z0-9_]*") || BendWords.reserved(newName)
    then throw new IncorrectOperationException("Invalid Bend local name")
    val sample = com.intellij.psi.PsiFileFactory
      .getInstance(getProject)
      .createFileFromText(
        "rename.bend",
        com.dearlordylord.bend.idea.syntax.BendLanguage.instance,
        s"def rename():\n  $newName\n"
      )
    val replacement = PsiTreeUtil
      .findChildrenOfType(sample, classOf[BendReferenceElement])
      .stream()
      .filter(_.getText == newName)
      .findFirst()
      .orElse(null)
    if replacement == null then
      throw new IncorrectOperationException("Cannot create Bend name")
    replace(replacement)
  override def getReferences: Array[com.intellij.psi.PsiReference] =
    ReferenceProvidersRegistry.getReferencesFromProviders(this)
final class BendAlias(node: ASTNode)
    extends ASTWrapperPsiElement(node)
    with PsiNameIdentifierOwner:
  override def getNameIdentifier: PsiElement = this
  override def getName: String = getText
  override def setName(newName: String): PsiElement =
    if !newName.matches("[A-Za-z_][A-Za-z0-9_]*") || BendWords.reserved(newName)
    then throw new IncorrectOperationException("Invalid Bend import alias")
    val sample = com.intellij.psi.PsiFileFactory
      .getInstance(getProject)
      .createFileFromText(
        "rename.bend",
        com.dearlordylord.bend.idea.syntax.BendLanguage.instance,
        s"import ./module.bend as $newName\n"
      )
    val replacement = PsiTreeUtil.findChildOfType(sample, classOf[BendAlias])
    if replacement == null then
      throw new IncorrectOperationException("Cannot create Bend alias")
    replace(replacement)
final class BendHeader(node: ASTNode) extends ASTWrapperPsiElement(node)
final class BendDefinition(node: ASTNode) extends BendDeclaration(node)
final class BendDatatype(node: ASTNode) extends BendDeclaration(node)
final class BendLaw(node: ASTNode) extends BendDeclaration(node):
  override def headerText: String = getText.trim
  override def parameters: List[BendSourceParameter] = Nil
final class BendConstructor(node: ASTNode) extends BendDeclaration(node):
  override def headerText: String = getText.trim
  override def parameters: List[BendSourceParameter] =
    BendSourceParameter.fromConstructor(getText)

/** Text is retained exactly for later source signature and binder consumers. */
final case class BendSourceParameter(name: String, source: String)

object BendSourceParameter:
  def fromHeader(header: String, datatype: Boolean): List[BendSourceParameter] =
    val open = if datatype then '<' else '('
    val close = if datatype then '>' else ')'
    val at = header.indexOf(open)
    if at < 0 then Nil else parseDelimited(header, at, open, close)

  def fromConstructor(source: String): List[BendSourceParameter] =
    val at = source.indexOf('{')
    if at < 0 then Nil else parseDelimited(source, at, '{', '}')

  private def parseDelimited(
      source: String,
      at: Int,
      open: Char,
      close: Char
  ): List[BendSourceParameter] =
    val parts = scala.collection.mutable.ListBuffer.empty[String]
    val current = new StringBuilder
    val closes = scala.collection.mutable.ArrayBuffer(close)
    var p = at + 1
    while p < source.length && closes.nonEmpty do
      val c = source.charAt(p)
      if c == close && closes.size == 1 then
        parts += current.toString
        closes.clear()
      else if c == ',' && closes.size == 1 then
        parts += current.toString
        current.clear()
      else
        c match
          case '('                                => closes += ')'
          case '{'                                => closes += '}'
          case '['                                => closes += ']'
          case '<'                                => closes += '>'
          case _ if closes.lastOption.contains(c) =>
            closes.remove(closes.size - 1)
          case _ => ()
        current.append(c)
      p += 1
    if closes.nonEmpty && current.nonEmpty then parts += current.toString
    parts.toList.flatMap { raw =>
      val sourceText = raw.trim
      val beforeColon = sourceText.takeWhile(_ != ':').trim
      val name = beforeColon.reverse
        .takeWhile(c => c.isLetterOrDigit || c == '_' || c == '.')
        .reverse
      Option.when(name.nonEmpty && name != "_")(
        BendSourceParameter(name, sourceText)
      )
    }
