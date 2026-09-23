package com.dearlordylord.bend.idea.symbols.api

import com.dearlordylord.bend.idea.model.FileId
import com.dearlordylord.bend.idea.syntax.psi.*
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import scala.jdk.CollectionConverters.*
import java.lang.ref.WeakReference

enum BendSymbolCategory:
  case Definition, Datatype, Law, Constructor

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

enum BendSourceResolution:
  case Unresolved
  case Resolved(symbol: BendSourceSymbol)
  case Ambiguous(candidates: List[BendSourceSymbol])

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

  /** Source-order lookup for current-file declarations. Later scope rules extend eligibility here. */
  def resolveCurrentFile(file: PsiFile, offset: Int, spelling: String,
      category: Option[BendSymbolCategory] = None): BendSourceResolution =
    val matches = visibleCandidates(file, offset).filter(s =>
      s.name == spelling && category.forall(_ == s.category))
    matches match
      case Nil => BendSourceResolution.Unresolved
      case one :: Nil => BendSourceResolution.Resolved(one)
      case several => BendSourceResolution.Ambiguous(several)
