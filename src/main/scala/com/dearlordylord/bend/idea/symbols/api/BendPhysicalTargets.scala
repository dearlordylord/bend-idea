package com.dearlordylord.bend.idea.symbols.api

import com.dearlordylord.bend.idea.model.FileId
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.{PsiElement, PsiFile, PsiManager}
import com.intellij.psi.util.PsiTreeUtil
import com.dearlordylord.bend.idea.syntax.psi.*

/** Reacquire a captured declaration from its actual VFS source, not cached
  * parsed PSI. Shared by navigation, documentation and proof links.
  */
object BendPhysicalTargets:
  def file(project: Project, id: FileId): Option[PsiFile] =
    val local = Option(LocalFileSystem.getInstance().findFileByPath(id.value))
    val projectFile = ProjectRootManager
      .getInstance(project)
      .getContentRoots
      .iterator
      .flatMap { root =>
        val rootPath = root.getPath.stripSuffix("/")
        if id.value == rootPath then Some(root)
        else if id.value.startsWith(rootPath + "/") then
          Option(
            root.findFileByRelativePath(id.value.drop(rootPath.length + 1))
          )
        else None
      }
      .take(1)
      .toList
      .headOption
    projectFile
      .orElse(local)
      .flatMap(v => Option(PsiManager.getInstance(project).findFile(v)))

  def declaration(file: PsiFile, symbol: BendSourceSymbol): Option[PsiElement] =
    declaration(
      file,
      symbol.handle,
      symbol.name,
      symbol.category
    )

  def declaration(
      file: PsiFile,
      symbol: BendSourceDeclarationFact
  ): Option[PsiElement] =
    declaration(file, symbol.handle, symbol.name, symbol.category)

  private def declaration(
      file: PsiFile,
      handle: BendSourceHandle,
      symbolName: String,
      symbolCategory: BendSymbolCategory
  ): Option[PsiElement] =
    val virtual = Option(file.getVirtualFile)
    val physicalId = virtual.map { v =>
      val canonical = Option(v.getCanonicalPath)
      new FileId(canonical.getOrElse(v.getPath), canonical.isDefined)
    }
    if !physicalId.contains(handle.file) &&
      BendSourceSymbols.fileId(file) != handle.file
    then None
    else
      Option(file.findElementAt(handle.nameOffset))
        .flatMap(element =>
          Option(
            PsiTreeUtil.getParentOfType(
              element,
              classOf[BendDeclaration],
              false
            )
          )
        )
        .filter(declaration =>
          Option(declaration.getNameIdentifier).exists(identifier =>
            identifier.getTextOffset == handle.nameOffset &&
              identifier.getText == symbolName
          ) && category(declaration).contains(symbolCategory)
        )
        .flatMap(declaration => Option(declaration.getNameIdentifier))

  private def category(
      declaration: BendDeclaration
  ): Option[BendSymbolCategory] = declaration match
    case _: BendDefinition  => Some(BendSymbolCategory.Definition)
    case _: BendDatatype    => Some(BendSymbolCategory.Datatype)
    case _: BendLaw         => Some(BendSymbolCategory.Law)
    case _: BendConstructor => Some(BendSymbolCategory.Constructor)
