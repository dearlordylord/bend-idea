package com.dearlordylord.bend.idea.features.editing

import com.dearlordylord.bend.idea.syntax.psi.BendFoldingSurface
import com.intellij.lang.ASTNode
import com.intellij.lang.folding.{FoldingBuilderEx, FoldingDescriptor}
import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement

final class BendFoldingBuilder extends FoldingBuilderEx:
  override def buildFoldRegions(root: PsiElement, document: Document,
      quick: Boolean): Array[FoldingDescriptor] =
    val file = root.getContainingFile
    if file == null then FoldingDescriptor.EMPTY_ARRAY
    else BendFoldingSurface.ranges(file).map(range =>
      new FoldingDescriptor(file.getNode, TextRange.create(range.from, range.until))).toArray

  override def getPlaceholderText(node: ASTNode): String = "..."
  override def isCollapsedByDefault(node: ASTNode): Boolean = false
