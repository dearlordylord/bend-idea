package com.dearlordylord.bend.idea.symbols.references

import com.dearlordylord.bend.idea.model.FileId
import com.dearlordylord.bend.idea.symbols.api.{BendSourceSymbol, BendSourceSymbols}
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.{PsiElement, PsiFile, PsiManager}

/** Reacquire a captured declaration from its actual VFS source, not cached parsed PSI. */
object BendPhysicalTargets:
  def file(project: Project, id: FileId): Option[PsiFile] =
    val local = Option(LocalFileSystem.getInstance().findFileByPath(id.value))
    val projectFile = ProjectRootManager.getInstance(project).getContentRoots.iterator
      .flatMap { root =>
        val rootPath = root.getPath.stripSuffix("/")
        if id.value == rootPath then Some(root)
        else if id.value.startsWith(rootPath + "/") then
          Option(root.findFileByRelativePath(id.value.drop(rootPath.length + 1)))
        else None
      }.take(1).toList.headOption
    projectFile.orElse(local).flatMap(v => Option(PsiManager.getInstance(project).findFile(v)))

  def declaration(file: PsiFile, symbol: BendSourceSymbol): Option[PsiElement] =
    val virtual = Option(file.getVirtualFile)
    val physicalId = virtual.map { v =>
      val canonical = Option(v.getCanonicalPath)
      new FileId(canonical.getOrElse(v.getPath), canonical.isDefined)
    }
    if !physicalId.contains(symbol.handle.file) &&
        BendSourceSymbols.fileId(file) != symbol.handle.file then None
    else
      // A nonlocal VFS file may have a buffer-local PSI ID. Its VFS ID still
      // matches the captured graph source, and the category/offset/name
      // identify the exact declaration in that current source revision.
      BendSourceSymbols.declarations(file).find(s =>
        s.category == symbol.category && s.handle.nameOffset == symbol.handle.nameOffset &&
          s.name == symbol.name).map(_.declaration.getNameIdentifier)
