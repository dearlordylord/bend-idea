package com.dearlordylord.bend.idea.symbols.api

import com.dearlordylord.bend.idea.model.FileId
import com.dearlordylord.bend.idea.workspace.api.BendBaseSource
import com.dearlordylord.bend.idea.syntax.BendLanguage
import com.dearlordylord.bend.idea.syntax.psi.*
import com.dearlordylord.bend.idea.syntax.lexer.{BendLexer, BendTokens, BendWords}
import com.dearlordylord.bend.idea.symbols.scope.{BendScope, BindingOrigin, ScopeToken}
import com.dearlordylord.bend.idea.symbols.declarations.{BendDeclarationSite, BendLawDeclarations, BendLogicalLaw}
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.openapi.project.Project
import com.intellij.psi.TokenType
import com.intellij.psi.util.PsiTreeUtil
import scala.jdk.CollectionConverters.*
import java.lang.ref.WeakReference

enum BendSymbolCategory:
  case Definition, Datatype, Law, Constructor, Binder

enum BendBindingKind:
  case Parameter, Lambda, Let, Pattern, Do

/** Snapshot-local locator; reacquire the PSI declaration after edits. */
final case class BendSourceHandle(file: FileId, category: BendSymbolCategory, nameOffset: Int)
final case class BendSourceSignature(source: String, parameters: List[BendSourceParameter])
final case class BendSourceSymbol(
  handle: BendSourceHandle,
  name: String,
  category: BendSymbolCategory,
  signature: BendSourceSignature,
  comments: String,
  declaration: BendDeclaration
)

/** Source-local identity and region. The clause specification is source text, not an inferred
  * type or compiler verdict; `where` may pack the fill parameter into a witness pair.
  */
final case class BendSourceBinding(handle: BendSourceHandle, name: String, source: String,
    origin: BendBindingKind, from: Int, until: Int,
    sourceSpecification: Option[String] = None,
    specification: Option[BendSourceHandle] = None):
  def kindLabel: String = origin.toString.toLowerCase

enum BendSourceResolution:
  case Unresolved
  case Resolved(symbol: BendSourceSymbol)
  case Ambiguous(candidates: List[BendSourceSymbol])
  case ResolvedBinder(binding: BendSourceBinding)

/** Source eligibility is separate from a navigable declaration location. */
final case class BendNavigationResolution(target: BendSourceResolution, eligible: Boolean)

/** One semantic owner for source declarations. Later scope and import rules extend this API. */
object BendSourceSymbols:
  private var transientIds = List.empty[(WeakReference[AnyRef], Long)]
  private var nextTransientId = 0L

  private def transientId(key: AnyRef): Long = synchronized {
    transientIds = transientIds.filter(_._1.get != null)
    transientIds.find { case (reference, _) => reference.get eq key } match
      case Some((_, id)) => id
      case None =>
        nextTransientId += 1
        transientIds = (new WeakReference(key), nextTransientId) :: transientIds
        nextTransientId
  }

  def fileId(file: PsiFile): FileId =
    val original = file.getOriginalFile
    val virtual = Option(original.getVirtualFile)
    virtual.filter(_.isInLocalFileSystem) match
      case Some(v) =>
        Option(v.getCanonicalPath) match
          case Some(path) => new FileId(path, true)
          case None => new FileId(java.nio.file.Paths.get(v.getPath).normalize().toString, false)
      case None =>
        val key: AnyRef = virtual.getOrElse(original)
        new FileId(s"buffer:${transientId(key)}", false)

  def declarations(file: PsiFile): List[BendSourceSymbol] =
    val id = fileId(file)
    PsiTreeUtil.findChildrenOfType(file, classOf[BendDeclaration]).asScala.toList
      .sortBy(_.getTextRange.getStartOffset)
      .flatMap { declaration =>
        Option(declaration.getNameIdentifier).map { name =>
          val category = declaration match
            case _: BendDefinition => BendSymbolCategory.Definition
            case _: BendDatatype => BendSymbolCategory.Datatype
            case _: BendLaw => BendSymbolCategory.Law
            case _: BendConstructor => BendSymbolCategory.Constructor
          BendSourceSymbol(
            BendSourceHandle(id, category, name.getTextOffset),
            name.getText,
            category,
            BendSourceSignature(declaration.headerText, declaration.parameters),
            declaration.sourceComments,
            declaration
          )
        }
      }

  /** Parse captured Base text with the same tolerant declaration model as editor files. */
  def baseDeclarations(project: Project, source: BendBaseSource): List[BendSourceSymbol] =
    loadedDeclarations(project, new FileId(source.identity, true), source.text)

  /** A captured dependency contributes its own declarations, not imported aliases. */
  def loadedDeclarations(project: Project, identity: FileId, text: String): List[BendSourceSymbol] =
    BendLawDeclarations.completionCandidates(sourceDeclarations(project, identity, text))(site)

  /** All captured declarations for a graph source, with its canonical source identity. */
  def sourceDeclarations(project: Project, identity: FileId, text: String): List[BendSourceSymbol] =
    val file = PsiFileFactory.getInstance(project).createFileFromText("loaded.bend", BendLanguage.instance, text)
    declarations(file).map(symbol => symbol.copy(handle = symbol.handle.copy(file = identity)))

  /** Same-file source links only; a fill never implies a checked or proved law. */
  def logicalLaws(file: PsiFile): List[BendLogicalLaw[BendSourceSymbol]] =
    BendLawDeclarations.relationships(declarations(file))(site)

  private[api] def site(symbol: BendSourceSymbol): BendDeclarationSite =
    val signature = symbol.signature.source
    val open = signature.indexOf('(')
    var close = -1
    var depth = 0
    var at = open
    while at >= 0 && at < signature.length && close < 0 do
      signature.charAt(at) match
        case '(' => depth += 1
        case ')' =>
          depth -= 1
          if depth == 0 then close = at
        case _ => ()
      at += 1
    BendDeclarationSite(symbol.name, symbol.handle.nameOffset,
      symbol.category == BendSymbolCategory.Law,
      symbol.category == BendSymbolCategory.Definition,
      close >= 0 && signature.substring(close + 1).contains("->"),
      symbol.signature.parameters.forall(p => p.source == p.name))

  /** Earlier source declarations only. Type and constructor retain separate identities. */
  def visibleCandidates(file: PsiFile, offset: Int): List[BendSourceSymbol] =
    BendLawDeclarations.completionCandidates(declarations(file).filter(_.handle.nameOffset < offset))(site)

  /** Lexical binders from the current declaration, in nearest-first order. */
  def visibleBindings(file: PsiFile, offset: Int): List[BendSourceBinding] =
    val owner = PsiTreeUtil.findChildrenOfType(file, classOf[BendDeclaration]).asScala
      .filter(d => d.getTextRange.getStartOffset <= offset && offset <= d.getTextRange.getEndOffset)
      .toList.sortBy(_.getTextLength).headOption
    owner.toList.flatMap { declaration =>
      val range = declaration.getTextRange
      val source = file.getText
      val header = PsiTreeUtil.getChildOfType(declaration, classOf[BendHeader])
      val headerEnd = Option(header).map(_.getTextRange.getEndOffset).getOrElse(range.getStartOffset)
      val tokens = scopeTokens(source, range.getStartOffset, range.getEndOffset)
      val id = fileId(file)
      val scoped = BendScope.visible(BendScope.bindings(source, tokens, headerEnd,
        range.getEndOffset + 1, declaration.isInstanceOf[BendLaw]), offset)
        .map(b => BendSourceBinding(BendSourceHandle(id, BendSymbolCategory.Binder, b.nameOffset),
          b.name, b.source, b.origin match
            case BindingOrigin.Parameter => BendBindingKind.Parameter
            case BindingOrigin.Lambda => BendBindingKind.Lambda
            case BindingOrigin.Let => BendBindingKind.Let
            case BindingOrigin.Pattern => BendBindingKind.Pattern
            case BindingOrigin.Do => BendBindingKind.Do,
          b.from, b.until))
      if declaration.isInstanceOf[BendLaw] then
        val types = BendScope.lawClauses(source, tokens).map(c => c.nameOffset -> c.sourceSpecification).toMap
        scoped.map(binding => types.get(binding.handle.nameOffset) match
          case Some(specification) => binding.copy(sourceSpecification = Some(specification))
          case None => binding)
      else if !declaration.isInstanceOf[BendDefinition] then scoped
      else
        val link = logicalLaws(file).find(_.fills.exists(_.declaration eq declaration))
        link match
          case None => scoped
          case Some(relationship) =>
            val lawRange = relationship.law.declaration.getTextRange
            val clauses = BendScope.lawClauses(source,
              scopeTokens(source, lawRange.getStartOffset, lawRange.getEndOffset))
            val fillParameters = scoped.filter(_.origin == BendBindingKind.Parameter)
              .sortBy(_.handle.nameOffset)
            val types = fillParameters.zip(clauses).map { case (parameter, clause) =>
              parameter.handle -> (clause.sourceSpecification, relationship.law.handle)
            }.toMap
            scoped.map(binding => types.get(binding.handle) match
              case Some((sourceSpecification, lawHandle)) => binding.copy(
                sourceSpecification = Some(sourceSpecification), specification = Some(lawHandle))
              case None => binding)
    }

  private def scopeTokens(source: String, startOffset: Int, endOffset: Int): Vector[ScopeToken] =
    val lexer = new BendLexer()
    lexer.start(source, startOffset, endOffset, 0)
    val tokens = Vector.newBuilder[ScopeToken]
    while lexer.getTokenType != null do
      val start = lexer.getTokenStart
      val end = lexer.getTokenEnd
      val kind = lexer.getTokenType
      if kind != TokenType.WHITE_SPACE && kind != BendTokens.Comment then
        val spelling = source.substring(start, end)
        val name = (kind == BendTokens.Identifier || kind == BendTokens.FunctionName ||
          kind == BendTokens.TypeName || kind == BendTokens.NamespaceName) &&
          spelling.matches("[A-Za-z_][A-Za-z0-9_.]*") && !BendWords.reserved(spelling)
        tokens += ScopeToken(spelling, start, end, name)
      lexer.advance()
    tokens.result()

  /** Source-order lookup for current-file declarations. Later scope rules extend eligibility here. */
  def resolveCurrentFile(file: PsiFile, offset: Int, spelling: String,
      category: Option[BendSymbolCategory] = None): BendSourceResolution =
    if category.forall(_ == BendSymbolCategory.Binder) then
      visibleBindings(file, offset).find(_.name == spelling) match
        case Some(binding) => return BendSourceResolution.ResolvedBinder(binding)
        case None => ()
    val candidates = if category.isDefined then
      declarations(file).filter(_.handle.nameOffset < offset)
    else visibleCandidates(file, offset)
    val matches = candidates.filter(s =>
      s.name == spelling && category.forall(_ == s.category))
    matches match
      case Nil => BendSourceResolution.Unresolved
      case one :: Nil => BendSourceResolution.Resolved(one)
      case several => BendSourceResolution.Ambiguous(several)

  /** Classification from the tolerant declaration header and local annotation syntax. */
  def referenceCategory(file: PsiFile, offset: Int, spelling: String): Option[BendSymbolCategory] =
    val source = file.getText
    if offset < 0 || offset + spelling.length > source.length then None
    else
      val after = source.substring(offset + spelling.length).dropWhile(_.isWhitespace)
      if after.startsWith("{") then Some(BendSymbolCategory.Constructor)
      else
        val header = Option(file.findElementAt(offset))
          .flatMap(element => Option(PsiTreeUtil.getParentOfType(element, classOf[BendHeader])))
        val prefix = header match
          case Some(value) => source.substring(value.getTextOffset, offset)
          case None => source.substring(source.lastIndexOf('\n', offset - 1) + 1, offset)
        if prefix.contains("->") || prefix.contains(":") then Some(BendSymbolCategory.Datatype)
        else None
