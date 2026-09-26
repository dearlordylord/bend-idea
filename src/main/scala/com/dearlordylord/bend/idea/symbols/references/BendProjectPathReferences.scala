package com.dearlordylord.bend.idea.symbols.references

import com.dearlordylord.bend.idea.syntax.BendLanguage
import com.dearlordylord.bend.idea.syntax.psi.BendForeignPaths
import com.dearlordylord.bend.idea.workspace.api.{
  BendImportPaths,
  BendLoadingConfiguration,
  BendWorkspacePaths
}
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.psi.{PsiFile, PsiManager, PsiReference}
import com.intellij.openapi.project.Project

/** Bounded project-source search for path references; configured packages are
  * deliberately excluded because refactoring must not mutate them.
  */
object BendProjectPathReferences:
  private val MaxFiles = 4096

  def to(target: PsiFile): Either[String, List[PsiReference]] =
    val project = target.getProject
    project
      .getService(classOf[BendWorkspacePaths])
      .filesWithExtension("bend", MaxFiles)
      .map { paths =>
        val manager = PsiManager.getInstance(project)
        val roots =
          ProjectRootManager.getInstance(project).getContentRoots.toList
        paths
          .flatMap(path => findVirtualFile(path, roots))
          .flatMap(virtual => Option(manager.findFile(virtual)))
          .filter(_.getLanguage == BendLanguage.instance)
          .flatMap(_.getReferences)
          .filter(reference => refersTo(reference, target, project))
      }

  private def findVirtualFile(
      path: String,
      roots: List[com.intellij.openapi.vfs.VirtualFile]
  ): Option[com.intellij.openapi.vfs.VirtualFile] =
    Option(LocalFileSystem.getInstance().findFileByPath(path))
      .filter(_.isValid)
      .orElse {
        roots.iterator
          .flatMap { root =>
            val base = root.getPath.stripSuffix("/")
            if path == base then Some(root)
            else if path.startsWith(base + "/") then
              Option(root.findFileByRelativePath(path.drop(base.length + 1)))
            else None
          }
          .find(_.isValid)
      }

  def refersTo(
      reference: PsiReference,
      target: PsiFile,
      project: Project
  ): Boolean =
    if reference.resolve() == target then true
    else
      val sourcePath = Option(reference.getElement.getContainingFile)
        .flatMap(file => Option(file.getVirtualFile))
        .map(_.getPath)
      val spelling = pathText(reference)
      sourcePath.exists { source =>
        val referencedPath =
          if reference.isInstanceOf[BendForeignPathReference] then
            BendForeignPaths.resolve(source, spelling).map(_.toString)
          else if reference.isInstanceOf[BendModulePathReference] &&
            spelling != "Base" && !spelling.startsWith("0x")
          then
            val (_, cache) = project
              .getService(classOf[BendLoadingConfiguration])
              .paths
            Some(BendImportPaths.target(source, cache, spelling))
          else None
        referencedPath.exists(samePath(_, target.getVirtualFile.getPath))
      }

  def pathText(reference: PsiReference): String =
    val range = reference.getRangeInElement
    reference.getElement.getText
      .substring(range.getStartOffset, range.getEndOffset)

  private def samePath(left: String, right: String): Boolean =
    def canonical(value: String): String =
      try
        val path = java.nio.file.Path.of(value).toAbsolutePath.normalize()
        if java.nio.file.Files.exists(path) then path.toRealPath().toString
        else path.toString
      catch case _: Exception => value
    canonical(left) == canonical(right)
