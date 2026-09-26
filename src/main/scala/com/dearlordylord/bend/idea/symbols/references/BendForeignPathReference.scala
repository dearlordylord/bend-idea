package com.dearlordylord.bend.idea.symbols.references

import com.dearlordylord.bend.idea.syntax.psi.BendForeignPath
import com.dearlordylord.bend.idea.syntax.psi.BendForeignPaths
import com.dearlordylord.bend.idea.symbols.api.BendSourcePathKind
import com.dearlordylord.bend.idea.symbols.api.BendSourcePathReference
import com.dearlordylord.bend.idea.workspace.api.BendSourcePathEdits
import com.intellij.openapi.vfs.{LocalFileSystem, VirtualFile}
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.psi.{PsiElement, PsiFile, PsiManager, PsiReferenceBase}
import java.nio.file.Path

/** A foreign path is a source-relative file reference, never an instruction to
  * load or execute the referenced asset.
  */
final class BendForeignPathReference(
    file: PsiFile,
    path: BendForeignPath
) extends PsiReferenceBase[PsiFile](file, path.range, true)
    with BendSourcePathReference:
  override def pathKind: BendSourcePathKind = BendSourcePathKind.Foreign

  override def spelling: String = path.spelling

  override def resolve(): PsiElement =
    Option(getElement.getVirtualFile)
      .flatMap(source =>
        BendForeignPaths
          .resolve(source.getPath, path.spelling)
          .flatMap(findVirtualFile(getElement.getProject, _))
      )
      .map(PsiManager.getInstance(getElement.getProject).findFile)
      .orNull

  override def getVariants: Array[Object] = Array.empty

  override def handleElementRename(newElementName: String): PsiElement =
    val extension = if path.spelling.endsWith(".js") then ".js" else ".c"
    val fileName =
      if newElementName.endsWith(extension) then newElementName
      else s"$newElementName$extension"
    BendSourcePathEdits
      .withFileName(path.spelling, fileName)
      .map(BendPathReferenceEditing.replace(this, _))
      .getOrElse(getElement)

  private def findVirtualFile(
      project: com.intellij.openapi.project.Project,
      path: Path
  ): Option[VirtualFile] =
    val local = Option(
      LocalFileSystem.getInstance().findFileByNioFile(path)
    ).filter(file => file.isValid && !file.isDirectory)
    local.orElse {
      ProjectRootManager
        .getInstance(project)
        .getContentRoots
        .iterator
        .flatMap { root =>
          try
            val base = Path.of(root.getPath).toAbsolutePath.normalize()
            val candidate = path.toAbsolutePath.normalize()
            if candidate.startsWith(base) then
              Option(
                root.findFileByRelativePath(
                  base.relativize(candidate).toString.replace('\\', '/')
                )
              )
            else None
          catch case _: Exception => None
        }
        .find(file => file.isValid && !file.isDirectory)
    }
