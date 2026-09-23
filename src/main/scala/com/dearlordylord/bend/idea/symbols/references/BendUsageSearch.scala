package com.dearlordylord.bend.idea.symbols.references

import com.dearlordylord.bend.idea.model.FileId
import com.dearlordylord.bend.idea.symbols.api.*
import com.dearlordylord.bend.idea.syntax.BendLanguage
import com.dearlordylord.bend.idea.syntax.psi.{BendDeclaration, BendName, BendReferenceElement}
import com.dearlordylord.bend.idea.workspace.api.BendLoadingConfiguration
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.fileEditor.{FileDocumentManager, FileEditorManager}
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.{PsiElement, PsiFile, PsiManager, PsiReference}
import com.intellij.psi.search.{GlobalSearchScope, LocalSearchScope, SearchScope}
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.{Processor, QueryExecutor}
import scala.collection.mutable
import scala.jdk.CollectionConverters.*
import scala.util.boundary, boundary.break

/** Resolve every candidate reference through the same native references used by navigation. */
final class BendUsageSearch extends QueryExecutor[PsiReference, ReferencesSearch.SearchParameters]:
  override def execute(parameters: ReferencesSearch.SearchParameters,
      consumer: Processor[? >: PsiReference]): Boolean =
    val target = parameters.getElementToSearch
    if target == null || Option(target.getContainingFile).forall(_.getLanguage != BendLanguage.instance) then
      return true
    val targetName = target match
      case declaration: BendDeclaration => declaration.getNameIdentifier
      case _ => target
    val identity = BendUsageIdentity.of(targetName)
    if identity.isEmpty then return true
    val scope = parameters.getEffectiveSearchScope
    val candidates = BendUsageFiles.files(target.getContainingFile, scope)
    val identities = mutable.HashMap.empty[(PsiFile, Int), Option[BendUsageIdentity]]
    def identityOf(element: PsiElement): Option[BendUsageIdentity] =
      identities.getOrElseUpdate((element.getContainingFile, element.getTextOffset),
        BendUsageIdentity.of(element))
    boundary[Boolean]:
      for file <- candidates do
        ProgressManager.checkCanceled()
        val elements = PsiTreeUtil.findChildrenOfType(file, classOf[BendReferenceElement]).asScala.toList ++
        PsiTreeUtil.findChildrenOfType(file, classOf[BendName]).asScala.toList
        for element <- elements do
          ProgressManager.checkCanceled()
          if element.getText.contains(identity.get.searchWord) then
            for reference <- element.getReferences do
              if !reference.getElement.isInstanceOf[BendName] ||
                  reference.getElement.getTextOffset != targetName.getTextOffset ||
                  reference.getElement.getContainingFile != targetName.getContainingFile then
                val resolved = reference.resolve()
                if resolved != null && inScope(scope, reference) &&
                    identityOf(resolved).contains(identity.get) &&
                    !consumer.process(reference) then break(false)
      true

  private def inScope(scope: SearchScope, reference: PsiReference): Boolean = scope match
    case local: LocalSearchScope =>
      val element = reference.getElement
      val absolute = reference.getRangeInElement.shiftRight(element.getTextOffset)
      local.getScope.exists(owner => owner.getContainingFile == element.getContainingFile &&
        owner.getTextRange.contains(absolute))
    case _ => true

private[references] final case class BendUsageIdentity(file: FileId, category: String,
    offset: Int, searchWord: String):
  override def equals(other: Any): Boolean = other match
    case that: BendUsageIdentity => file == that.file && category == that.category && offset == that.offset
    case _ => false
  override def hashCode(): Int = (file, category, offset).hashCode()

private[references] object BendUsageIdentity:
  def of(element: PsiElement): Option[BendUsageIdentity] =
    val name = element match
      case declaration: BendDeclaration => declaration.getNameIdentifier
      case _ => element
    if name == null then return None
    val file = name.getContainingFile
    if file == null || file.getLanguage != BendLanguage.instance then return None
    val offset = name.getTextOffset
    val symbols = BendSourceSymbols.declarations(file)
    symbols.find(_.handle.nameOffset == offset).map { symbol =>
      // A law and its fill are separate source declarations. Their references
      // share a presentation identity, anchored at the law, when paired.
      val anchor = BendSourceSymbols.usageAnchor(file, symbol)
      BendUsageIdentity(anchor.file, anchor.category.toString, anchor.nameOffset, symbol.name)
    }.orElse {
      val source = file.getText
      val lineStart = source.lastIndexOf('\n', offset - 1) + 1
      val before = source.substring(lineStart, offset)
      val alias = before.matches(".*\\bimport\\s+.*\\bas\\s+")
      if alias then Some(BendUsageIdentity(BendSourceSymbols.fileId(file), "Alias", offset, name.getText))
      else
        // Local binders are source-local and retain their own declaration offset.
        BendSourceSymbols.bindingDeclaredAt(file, offset)
          .map(binding => BendUsageIdentity(binding.handle.file, "Binder", offset, binding.name))
    }

/** No project-wide PSI collection or index read. Traversal and content size are capped. */
private[references] object BendUsageFiles:
  private val MaxVisited = 4096
  private val MaxFiles = 512
  private val MaxText = 1024 * 1024

  def files(origin: PsiFile, scope: SearchScope): List[PsiFile] =
    val project = origin.getProject
    scope match
      case local: LocalSearchScope =>
        return local.getScope.iterator.map(_.getContainingFile).filter(_ != null)
          .filter(f => f.getLanguage == BendLanguage.instance && f.getTextLength <= MaxText)
          .take(MaxFiles).toList.distinct
      case _ => ()
    val seen = mutable.HashSet.empty[VirtualFile]
    val accepted = mutable.ListBuffer.empty[PsiFile]
    val queue = mutable.Queue.empty[VirtualFile]
    val queued = mutable.HashSet.empty[VirtualFile]
    var visited = 0
    def enqueue(file: VirtualFile): Unit =
      if file != null && visited + queue.size < MaxVisited && queued.add(file) then
        queue.enqueue(file)
    Option(origin.getVirtualFile).foreach(enqueue)
    FileEditorManager.getInstance(project).getOpenFiles.iterator
      .filter(_.getName.endsWith(".bend")).foreach(enqueue)
    ProjectRootManager.getInstance(project).getContentRoots.foreach(enqueue)
    // A configured Base file can live outside content roots.
    val (base, cache) = project.getService(classOf[BendLoadingConfiguration]).paths
    val local = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
    Option(local.findFileByPath(base)).foreach(enqueue)
    Option(local.findFileByPath(cache)).foreach(enqueue)
    val manager = PsiManager.getInstance(project)
    val documents = FileDocumentManager.getInstance()
    while queue.nonEmpty && visited < MaxVisited && accepted.size < MaxFiles do
      ProgressManager.checkCanceled()
      val next = queue.dequeue()
      if seen.add(next) && next.isValid then
        visited += 1
        if next.isDirectory then
          val children = next.getChildren.iterator
          while children.hasNext && visited + queue.size < MaxVisited do enqueue(children.next())
        else if next.getName.endsWith(".bend") && inScope(scope, next) then
          // A cached document has the current size even when the saved VFS file is large.
          val document = documents.getCachedDocument(next)
          val textLength = if document == null then next.getLength else document.getTextLength.toLong
          if textLength <= MaxText then
            Option(manager.findFile(next)).filter(_.getLanguage == BendLanguage.instance)
              .filter(_.getTextLength <= MaxText).foreach(accepted += _)
    if !accepted.contains(origin) && origin.getTextLength <= MaxText &&
        Option(origin.getVirtualFile).exists(inScope(scope, _)) then
      accepted.prepend(origin)
    accepted.toList

  private def inScope(scope: SearchScope, file: VirtualFile): Boolean = scope match
    case global: GlobalSearchScope => global.contains(file)
    case _: LocalSearchScope => false
    case _ => false
