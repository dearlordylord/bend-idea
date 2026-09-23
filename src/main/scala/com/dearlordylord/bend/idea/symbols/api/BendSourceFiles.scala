package com.dearlordylord.bend.idea.symbols.api

import com.dearlordylord.bend.idea.syntax.BendLanguage
import com.dearlordylord.bend.idea.workspace.api.BendLoadingConfiguration
import com.intellij.openapi.fileEditor.{FileDocumentManager, FileEditorManager}
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.{LocalFileSystem, VirtualFile}
import com.intellij.psi.{PsiFile, PsiManager}
import com.intellij.psi.search.{GlobalSearchScope, LocalSearchScope, SearchScope}
import scala.collection.mutable

/** Bounded, current-document-aware source discovery shared by usage search and rename. */
final case class BendSourceFileScan(files: List[PsiFile], complete: Boolean)

object BendSourceFiles:
  private val MaxVisited = 4096
  private val MaxFiles = 512
  private val MaxText = 1024 * 1024

  def files(origin: PsiFile, scope: SearchScope): List[PsiFile] = scan(origin, scope).files

  def scan(origin: PsiFile, scope: SearchScope): BendSourceFileScan =
    scan(origin, scope, includeConfiguredLibraries = false)

  /** Rename may need configured library sources even when IntelliJ has not indexed them. */
  def scan(origin: PsiFile, scope: SearchScope,
      includeConfiguredLibraries: Boolean): BendSourceFileScan =
    val project = origin.getProject
    scope match
      case local: LocalSearchScope =>
        val candidates = local.getScope.iterator.map(_.getContainingFile).filter(_ != null)
          .filter(_.getLanguage == BendLanguage.instance).toList.distinct
        return BendSourceFileScan(candidates.filter(_.getTextLength <= MaxText).take(MaxFiles),
          candidates.size <= MaxFiles && candidates.forall(_.getTextLength <= MaxText))
      case _ => ()
    val seen = mutable.HashSet.empty[VirtualFile]
    val accepted = mutable.ListBuffer.empty[PsiFile]
    val queue = mutable.Queue.empty[VirtualFile]
    val queued = mutable.HashSet.empty[VirtualFile]
    var visited = 0
    var complete = true
    def enqueue(file: VirtualFile): Unit =
      if file != null && !queued.contains(file) then
        if visited + queue.size >= MaxVisited then complete = false
        else if queued.add(file) then queue.enqueue(file)
    Option(origin.getVirtualFile).foreach(enqueue)
    FileEditorManager.getInstance(project).getOpenFiles.iterator
      .filter(_.getName.endsWith(".bend")).foreach(enqueue)
    ProjectRootManager.getInstance(project).getContentRoots.foreach(enqueue)
    val (base, cache) = project.getService(classOf[BendLoadingConfiguration]).paths
    val local = LocalFileSystem.getInstance()
    val libraries = List(base, cache).flatMap(path => Option(local.findFileByPath(path)))
    libraries.filter(file => includeConfiguredLibraries || inScope(scope, file)).foreach(enqueue)
    def selected(file: VirtualFile): Boolean =
      inScope(scope, file) || includeConfiguredLibraries && libraries.exists { root =>
        file == root || root.isDirectory && file.getPath.startsWith(root.getPath.stripSuffix("/") + "/")
      }
    val manager = PsiManager.getInstance(project)
    val documents = FileDocumentManager.getInstance()
    while queue.nonEmpty && visited < MaxVisited && accepted.size < MaxFiles do
      ProgressManager.checkCanceled()
      val next = queue.dequeue()
      if seen.add(next) && next.isValid then
        visited += 1
        if next.isDirectory then
          next.getChildren.foreach(enqueue)
        else if next.getName.endsWith(".bend") && selected(next) then
          val document = documents.getCachedDocument(next)
          val length = if document == null then next.getLength else document.getTextLength.toLong
          if length > MaxText then complete = false
          else
            Option(manager.findFile(next)).filter(_.getLanguage == BendLanguage.instance)
              .filter(_.getTextLength <= MaxText).foreach(accepted += _)
    if queue.nonEmpty then complete = false
    if !accepted.contains(origin) && origin.getTextLength <= MaxText &&
        Option(origin.getVirtualFile).exists(inScope(scope, _)) then accepted.prepend(origin)
    BendSourceFileScan(accepted.toList, complete)

  private def inScope(scope: SearchScope, file: VirtualFile): Boolean = scope match
    case global: GlobalSearchScope => global.contains(file)
    case _: LocalSearchScope => false
    case _ => false
