package com.dearlordylord.bend.idea.symbols.index

import com.intellij.openapi.project.Project
import com.intellij.openapi.module.Module
import com.intellij.openapi.vfs.{LocalFileSystem, VfsUtilCore, VirtualFile}
import com.intellij.psi.search.GlobalSearchScope
import com.dearlordylord.bend.idea.workspace.api.BendLoadingConfiguration
import java.nio.file.Path

/** Configured VFS roots shared by Go to Symbol scope and IntelliJ's library index registration. */
object BendConfiguredSymbolRoots:
  def roots(project: Project): List[VirtualFile] =
    val (basePath, cachePath) = project.getService(classOf[BendLoadingConfiguration]).paths
    val local = LocalFileSystem.getInstance()
    val base = Option(local.findFileByPath(basePath)).filter(file =>
      file.isValid && !file.isDirectory && file.getName.endsWith(".bend")).toList
    val cache = Option(local.findFileByPath(cachePath)).filter(_.isValid).toList
    (base ++ cache).distinct

  def searchScope(scope: GlobalSearchScope, project: Project): GlobalSearchScope =
    val configured = roots(project)
    val projectWide = scope == GlobalSearchScope.allScope(project) ||
      scope == GlobalSearchScope.projectScope(project)
    // Keep caller-defined scopes narrow; add configured libraries only for workspace-wide searches.
    if configured.isEmpty || !projectWide then scope
    else new ConfiguredRootsScope(project, scope, configured)

  def watchRoots(project: Project): List[VirtualFile] =
    val (basePath, cachePath) = project.getService(classOf[BendLoadingConfiguration]).paths
    val local = LocalFileSystem.getInstance()
    List(basePath, cachePath).flatMap { path =>
      val resolved = Option(local.findFileByPath(path)).filter(_.isValid)
      resolved match
        case Some(file) if file.isDirectory => Some(file)
        case Some(file) => Option(file.getParent)
        case None =>
          try Option(Path.of(path).toAbsolutePath.normalize().getParent)
            .flatMap(parent => Option(local.findFileByPath(parent.toString)))
          catch case _: java.nio.file.InvalidPathException => None
    }.distinct

  private final class ConfiguredRootsScope(project: Project, delegate: GlobalSearchScope,
      roots: List[VirtualFile]) extends GlobalSearchScope(project):
    override def contains(file: VirtualFile): Boolean =
      delegate.contains(file) || roots.exists(root => VfsUtilCore.isAncestor(root, file, false))

    override def isSearchInModuleContent(module: Module): Boolean =
      delegate.isSearchInModuleContent(module)

    override def isSearchInLibraries: Boolean = true

    override def compare(file1: VirtualFile, file2: VirtualFile): Int =
      delegate.compare(file1, file2)
