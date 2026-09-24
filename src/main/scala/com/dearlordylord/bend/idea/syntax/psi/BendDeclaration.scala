package com.dearlordylord.bend.idea.syntax.psi

import com.dearlordylord.bend.idea.syntax.lexer.BendWords
import com.intellij.extapi.psi.StubBasedPsiElementBase
import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.lang.ASTNode
import com.intellij.psi.{PsiElement, PsiNameIdentifierOwner}
import com.intellij.psi.StubBasedPsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.tree.IElementType
import com.intellij.util.IncorrectOperationException
import com.intellij.psi.impl.source.resolve.reference.ReferenceProvidersRegistry

/** One source declaration; the name element remains an IntelliJ rename target.
  */
sealed abstract class BendDeclaration(
    stub: BendDeclarationStub,
    elementType: IElementType,
    node: ASTNode
) extends StubBasedPsiElementBase[BendDeclarationStub](
      stub,
      if node == null then elementType else null,
      node
    )
    with StubBasedPsiElement[BendDeclarationStub]
    with PsiNameIdentifierOwner:
  override def getNameIdentifier: PsiElement =
    val header = PsiTreeUtil.getChildOfType(this, classOf[BendHeader])
    if header != null then PsiTreeUtil.getChildOfType(header, classOf[BendName])
    else PsiTreeUtil.getChildOfType(this, classOf[BendName])
  override def getName: String =
    val stored = getStub
    if stored != null then stored.name
    else Option(getNameIdentifier).map(_.getText).orNull
  override def getPresentation: com.intellij.navigation.ItemPresentation =
    val declaration = this
    new com.intellij.navigation.ItemPresentation:
      override def getPresentableText: String =
        val prefix = declaration match
          case _: BendDefinition  => "def "
          case _: BendLaw         => "law "
          case _: BendDatatype    => "type "
          case _: BendConstructor => ""
        prefix + Option(declaration.getName).getOrElse("")
      override def getLocationString: String =
        Option(declaration.getContainingFile)
          .flatMap(file => Option(file.getVirtualFile))
          .map(_.getPresentableUrl)
          .getOrElse("")
      override def getIcon(open: Boolean): javax.swing.Icon =
        Option(declaration.getContainingFile).map(_.getFileType.getIcon).orNull
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
    val _ = declaration.setName(newName)
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
final class BendDefinition private (stub: BendDeclarationStub, node: ASTNode)
    extends BendDeclaration(stub, BendElements.Definition, node):
  def this(node: ASTNode) = this(null, node)
  def this(stub: BendDeclarationStub) = this(stub, null)

final class BendDatatype private (stub: BendDeclarationStub, node: ASTNode)
    extends BendDeclaration(stub, BendElements.Datatype, node):
  def this(node: ASTNode) = this(null, node)
  def this(stub: BendDeclarationStub) = this(stub, null)

final class BendLaw private (stub: BendDeclarationStub, node: ASTNode)
    extends BendDeclaration(stub, BendElements.Law, node):
  def this(node: ASTNode) = this(null, node)
  def this(stub: BendDeclarationStub) = this(stub, null)
  override def headerText: String = getText.trim
  override def parameters: List[BendSourceParameter] =
    BendSourceParameter.fromLaw(getText)

final class BendConstructor private (stub: BendDeclarationStub, node: ASTNode)
    extends BendDeclaration(stub, BendElements.Constructor, node):
  def this(node: ASTNode) = this(null, node)
  def this(stub: BendDeclarationStub) = this(stub, null)
  override def headerText: String = getText.trim
  override def parameters: List[BendSourceParameter] =
    BendSourceParameter.fromConstructor(getText)

/** Source-declared parameter facts; these are never compiler-inferred types. */
final case class BendSourceParameter(
    name: String,
    source: String,
    quantity: Option[Char] = None,
    template: Boolean = false,
    implicitQuantity: Boolean = false
)

object BendSourceParameter:
  def fromHeader(header: String, datatype: Boolean): List[BendSourceParameter] =
    val open = if datatype then '<' else '('
    val close = if datatype then '>' else ')'
    val at = header.indexOf(open)
    if at < 0 then Nil
    else parseDelimited(header, at, open, close, datatype)

  def fromConstructor(source: String): List[BendSourceParameter] =
    val at = source.indexOf('{')
    if at < 0 then Nil else parseDelimited(source, at, '{', '}')

  /** Laws express their binders with one or more leading `for` clauses. */
  def fromLaw(source: String): List[BendSourceParameter] =
    source.linesIterator
      .takeWhile { line =>
        val content = line.dropWhile(c => c == ' ' || c == '\t')
        content != "exs" && !content.startsWith("exs ")
      }
      .flatMap { line =>
        val content = line.dropWhile(c => c == ' ' || c == '\t')
        Option.when(content.startsWith("for "))(content.drop(4))
      }
      .flatMap(parseBindings)
      .toList

  private def parseDelimited(
      source: String,
      at: Int,
      open: Char,
      close: Char,
      datatype: Boolean = false
  ): List[BendSourceParameter] =
    val until = matchingClose(source, at, close).getOrElse(source.length)
    val inside = source.substring(math.min(at + 1, until), until)
    parseBindings(inside).zipWithIndex.map { case (parameter, index) =>
      if datatype && index == 0 && !parameter.source.contains(':') then
        parameter.copy(implicitQuantity = true)
      else parameter
    }

  private def matchingClose(
      source: String,
      at: Int,
      close: Char
  ): Option[Int] =
    val closes = scala.collection.mutable.ArrayBuffer(close)
    var p = at + 1
    while p < source.length && closes.nonEmpty do
      val c = source.charAt(p)
      if c == closes.last then
        val _ = closes.remove(closes.size - 1)
      else
        val _ = c match
          case '(' => closes += ')'
          case '{' => closes += '}'
          case '[' => closes += ']'
          case '<' => closes += '>'
          case _   => ()
      if closes.isEmpty then return Some(p)
      p += 1
    None

  private def parseBindings(source: String): List[BendSourceParameter] =
    val parts = scala.collection.mutable.ListBuffer.empty[String]
    val current = new StringBuilder
    val closes = scala.collection.mutable.ArrayBuffer.empty[Char]
    var p = 0
    while p < source.length do
      val c = source.charAt(p)
      val _ = if closes.isEmpty && c == ',' then
        parts += current.toString
        current.clear()
      else
        val _ = c match
          case '('                                => closes += ')'
          case '{'                                => closes += '}'
          case '['                                => closes += ']'
          case '<'                                => closes += '>'
          case _ if closes.lastOption.contains(c) =>
            closes.remove(closes.size - 1)
          case _ => ()
        current.append(c)
      p += 1
    if current.nonEmpty then parts += current.toString
    parts.toList.flatMap(parseBinding)

  private def parseBinding(raw: String): Option[BendSourceParameter] =
    val sourceText = raw.trim
    if sourceText.isEmpty then None
    else
      val body = sourceText.dropWhile(c => c == '~' || c == '+' || c == '-')
      val beforeColon = body.takeWhile(_ != ':').trim
      val name = beforeColon.reverse
        .takeWhile(c => c.isLetterOrDigit || c == '_' || c == '.')
        .reverse
      val quantity = sourceText.headOption.filter(c => c == '+' || c == '-')
      Option.when(name.nonEmpty && name != "_")(
        BendSourceParameter(
          name,
          sourceText,
          quantity,
          sourceText.startsWith("~")
        )
      )
