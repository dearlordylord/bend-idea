package com.dearlordylord.bend.idea.features.rename

import com.dearlordylord.bend.idea.symbols.api.BendSourcePathReferences
import com.intellij.psi.{PsiElement, PsiFile, PsiReference}
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.psi.search.SearchScope
import com.intellij.refactoring.rename.RenamePsiElementProcessor
import com.intellij.util.IncorrectOperationException
import scala.jdk.CollectionConverters.*

/** Enables previewable rename refactoring for writable Bend and foreign files.
  */
final class BendSourceFileRenameProcessor extends RenamePsiElementProcessor:
  override def canProcessElement(element: PsiElement): Boolean =
    element match
      case file: PsiFile =>
        val name = file.getName
        (name.endsWith(".bend") || name.endsWith(".c") || name.endsWith(
          ".js"
        )) &&
        file.isWritable && file.getVirtualFile != null &&
        ProjectRootManager
          .getInstance(file.getProject)
          .getFileIndex
          .isInContent(file.getVirtualFile)
      case _ => false

  override def findReferences(
      element: PsiElement,
      searchScope: SearchScope,
      searchTextOccurrences: Boolean
  ): java.util.Collection[PsiReference] =
    element match
      case file: PsiFile =>
        BendSourcePathReferences
          .to(file)
          .fold(
            message => throw new IncorrectOperationException(message),
            identity
          )
          .asJava
      case _ => java.util.List.of()

  override def showRenamePreviewButton(element: PsiElement): Boolean = true
