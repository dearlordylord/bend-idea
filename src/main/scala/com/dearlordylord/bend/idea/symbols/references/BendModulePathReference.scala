package com.dearlordylord.bend.idea.symbols.references

import com.dearlordylord.bend.idea.symbols.api.BendImportedSymbolCatalog
import com.dearlordylord.bend.idea.workspace.api.BendLoadingConfiguration
import com.intellij.openapi.util.TextRange
import com.intellij.psi.{PsiElement, PsiFile, PsiReferenceBase}

/** The file owns the complete path range even when the lexer splits its
  * punctuation.
  */
final class BendModulePathReference(
    file: PsiFile,
    range: TextRange,
    importOffset: Int
) extends PsiReferenceBase[PsiFile](file, range, true):
  override def resolve(): PsiElement =
    val project = getElement.getProject
    val (base, cache) =
      project.getService(classOf[BendLoadingConfiguration]).paths
    val graph = project
      .getService(classOf[BendImportedSymbolCatalog])
      .loaded(getElement, base, cache)
    graph
      .importTarget(graph.root, importOffset)
      .flatMap(BendPhysicalTargets.file(project, _))
      .orNull

  override def getVariants: Array[Object] = Array.empty
