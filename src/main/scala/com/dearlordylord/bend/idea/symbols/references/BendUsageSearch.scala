package com.dearlordylord.bend.idea.symbols.references

import com.dearlordylord.bend.idea.model.FileId
import com.dearlordylord.bend.idea.symbols.api.*
import com.dearlordylord.bend.idea.syntax.BendLanguage
import com.dearlordylord.bend.idea.syntax.psi.{
  BendAlias,
  BendDeclaration,
  BendName,
  BendReferenceElement
}
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.{PsiElement, PsiFile, PsiReference}
import com.intellij.psi.search.{LocalSearchScope, SearchScope}
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.{Processor, QueryExecutor}
import scala.collection.mutable
import scala.jdk.CollectionConverters.*
import scala.util.boundary, boundary.break

/** Resolve every candidate reference through the same native references used by
  * navigation.
  */
final class BendUsageSearch
    extends QueryExecutor[PsiReference, ReferencesSearch.SearchParameters]:
  override def execute(
      parameters: ReferencesSearch.SearchParameters,
      consumer: Processor[? >: PsiReference]
  ): Boolean =
    // Find Usages invokes query executors on progress workers without read
    // access; target, reference, VFS, and PSI traversal below need that lock.
    ReadAction.compute(() => executeWithRead(parameters, consumer))

  private def executeWithRead(
      parameters: ReferencesSearch.SearchParameters,
      consumer: Processor[? >: PsiReference]
  ): Boolean =
    val target = parameters.getElementToSearch
    if target == null || Option(target.getContainingFile).forall(
        _.getLanguage != BendLanguage.instance
      )
    then return true
    val targetName = target match
      case declaration: BendDeclaration => declaration.getNameIdentifier
      case _                            => target
    val identity = BendUsageIdentity.of(targetName)
    if identity.isEmpty then return true
    val scope = parameters.getEffectiveSearchScope
    val candidates = BendSourceFiles.files(target.getContainingFile, scope)
    val identities =
      mutable.HashMap.empty[(PsiFile, Int), Option[BendUsageIdentity]]
    def identityOf(element: PsiElement): Option[BendUsageIdentity] =
      identities.getOrElseUpdate(
        (element.getContainingFile, element.getTextOffset),
        BendUsageIdentity.of(element)
      )
    boundary[Boolean]:
      for file <- candidates do
        ProgressManager.checkCanceled()
        val elements = PsiTreeUtil
          .findChildrenOfType(file, classOf[BendReferenceElement])
          .asScala
          .toList ++
          PsiTreeUtil.findChildrenOfType(file, classOf[BendName]).asScala.toList
        for element <- elements do
          ProgressManager.checkCanceled()
          if element.getText.contains(identity.get.searchWord) then
            for reference <- element.getReferences do
              val ownDeclaration = reference.getElement match
                case name: BendName =>
                  val sameFile =
                    name.getContainingFile == targetName.getContainingFile ||
                      (name.getContainingFile.getVirtualFile != null &&
                        name.getContainingFile.getVirtualFile == targetName.getContainingFile.getVirtualFile)
                  sameFile && name.getTextOffset == targetName.getTextOffset
                case _ => false
              if !ownDeclaration then
                val resolved = reference.resolve()
                if resolved != null && inScope(scope, reference) &&
                  identityOf(resolved).contains(identity.get) &&
                  !consumer.process(reference)
                then break(false)
      true

  private def inScope(scope: SearchScope, reference: PsiReference): Boolean =
    scope match
      case local: LocalSearchScope =>
        val element = reference.getElement
        val absolute =
          reference.getRangeInElement.shiftRight(element.getTextOffset)
        local.getScope.exists(owner =>
          owner.getContainingFile == element.getContainingFile &&
            owner.getTextRange.contains(absolute)
        )
      case _ => true

private[references] final case class BendUsageIdentity(
    file: FileId,
    category: String,
    offset: Int,
    searchWord: String
):
  override def equals(other: Any): Boolean = other match
    case that: BendUsageIdentity =>
      file == that.file && category == that.category && offset == that.offset
    case _ => false
  override def hashCode(): Int = (file, category, offset).hashCode()

private[references] object BendUsageIdentity:
  def of(element: PsiElement): Option[BendUsageIdentity] =
    val name = element match
      case declaration: BendDeclaration => declaration.getNameIdentifier
      case _                            => element
    if name == null then return None
    val file = name.getContainingFile
    if file == null || file.getLanguage != BendLanguage.instance then
      return None
    val offset = name.getTextOffset
    val symbols = BendSourceSymbols.declarations(file)
    symbols
      .find(_.handle.nameOffset == offset)
      .map { symbol =>
        // A law and its fill are separate source declarations. Their references
        // share a presentation identity, anchored at the law, when paired.
        val anchor = BendSourceSymbols.usageAnchor(file, symbol)
        BendUsageIdentity(
          anchor.file,
          anchor.category.toString,
          anchor.nameOffset,
          symbol.name
        )
      }
      .orElse {
        val source = file.getText
        val lineStart = source.lastIndexOf('\n', offset - 1) + 1
        val before = source.substring(lineStart, offset)
        val alias = before.matches(".*\\bimport\\s+.*\\bas\\s+")
        if alias || name.isInstanceOf[BendAlias] then
          Some(
            BendUsageIdentity(
              BendSourceSymbols.fileId(file),
              "Alias",
              offset,
              name.getText
            )
          )
        else
          // Local binders are source-local and retain their own declaration offset.
          BendSourceSymbols
            .bindingDeclaredAt(file, offset)
            .map(binding =>
              BendUsageIdentity(
                binding.handle.file,
                "Binder",
                offset,
                binding.name
              )
            )
      }
