package com.dearlordylord.bend.idea.symbols.api

import com.dearlordylord.bend.idea.model.FileId
import com.dearlordylord.bend.idea.syntax.psi.*
import com.dearlordylord.bend.idea.syntax.lexer.{BendLexer, BendTokens, BendWords}
import com.dearlordylord.bend.idea.symbols.scope.{BendScope, BindingOrigin, ScopeToken}
import com.intellij.psi.PsiFile
import com.intellij.psi.TokenType
import com.intellij.psi.util.PsiTreeUtil
import scala.jdk.CollectionConverters.*
import java.lang.ref.WeakReference

enum BendSymbolCategory:
  case Definition, Datatype, Law, Constructor, Binder

enum BendBindingKind:
  case Parameter, Lambda, Let

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

/** Source-local identity and region. The original binder syntax is presentation data, not a resource-use judgment. */
final case class BendSourceBinding(handle: BendSourceHandle, name: String, source: String,
    origin: BendBindingKind, from: Int, until: Int):
  def kindLabel: String = origin.toString.toLowerCase

enum BendSourceResolution:
  case Unresolved
  case Resolved(symbol: BendSourceSymbol)
  case Ambiguous(candidates: List[BendSourceSymbol])
  case ResolvedBinder(binding: BendSourceBinding)

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

  /** Earlier source declarations only. Type and constructor retain separate identities. */
  def visibleCandidates(file: PsiFile, offset: Int): List[BendSourceSymbol] =
    declarations(file).filter(_.handle.nameOffset < offset)

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
      val lexer = new BendLexer()
      lexer.start(source, range.getStartOffset, range.getEndOffset, 0)
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
      val id = fileId(file)
      BendScope.visible(BendScope.bindings(source, tokens.result(), headerEnd, range.getEndOffset + 1), offset)
        .map(b => BendSourceBinding(BendSourceHandle(id, BendSymbolCategory.Binder, b.nameOffset),
          b.name, b.source, b.origin match
            case BindingOrigin.Parameter => BendBindingKind.Parameter
            case BindingOrigin.Lambda => BendBindingKind.Lambda
            case BindingOrigin.Let => BendBindingKind.Let,
          b.from, b.until))
    }

  /** Source-order lookup for current-file declarations. Later scope rules extend eligibility here. */
  def resolveCurrentFile(file: PsiFile, offset: Int, spelling: String,
      category: Option[BendSymbolCategory] = None): BendSourceResolution =
    if category.forall(_ == BendSymbolCategory.Binder) then
      visibleBindings(file, offset).find(_.name == spelling) match
        case Some(binding) => return BendSourceResolution.ResolvedBinder(binding)
        case None => ()
    val matches = visibleCandidates(file, offset).filter(s =>
      s.name == spelling && category.forall(_ == s.category))
    matches match
      case Nil => BendSourceResolution.Unresolved
      case one :: Nil => BendSourceResolution.Resolved(one)
      case several => BendSourceResolution.Ambiguous(several)
