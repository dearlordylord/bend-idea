package com.dearlordylord.bend.idea.symbols.index

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.{LocalFileSystem, VirtualFile}
import com.intellij.psi.search.GlobalSearchScope
import com.dearlordylord.bend.idea.workspace.api.BendLoadingConfiguration
import java.nio.file.Path
import scala.jdk.CollectionConverters.*

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
    if configured.isEmpty then scope
    else scope.union(GlobalSearchScope.filesWithLibrariesScope(project, configured.asJava))

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
