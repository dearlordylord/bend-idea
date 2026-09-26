package com.dearlordylord.bend.idea.symbols.references

import com.dearlordylord.bend.idea.symbols.api.BendImportedSymbolCatalog
import com.dearlordylord.bend.idea.symbols.api.BendPhysicalTargets
import com.dearlordylord.bend.idea.symbols.api.BendSourcePathKind
import com.dearlordylord.bend.idea.symbols.api.BendSourcePathReference
import com.dearlordylord.bend.idea.workspace.api.BendLoadingConfiguration
import com.dearlordylord.bend.idea.workspace.api.BendSourcePathEdits
import com.intellij.openapi.util.TextRange
import com.intellij.psi.{PsiElement, PsiFile, PsiReferenceBase}

/** The file owns the complete path range even when the lexer splits its
  * punctuation.
  */
final class BendModulePathReference(
    file: PsiFile,
    range: TextRange,
    importOffset: Int
) extends PsiReferenceBase[PsiFile](file, range, true)
    with BendSourcePathReference:
  override def pathKind: BendSourcePathKind = BendSourcePathKind.Module

  override def spelling: String = getElement.getText.substring(
    getRangeInElement.getStartOffset,
    getRangeInElement.getEndOffset
  )

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

  override def handleElementRename(newElementName: String): PsiElement =
    val spelling = getElement.getText.substring(
      getRangeInElement.getStartOffset,
      getRangeInElement.getEndOffset
    )
    if spelling == "Base" || spelling.startsWith("0x") then getElement
    else
      val fileName =
        if newElementName.endsWith(".bend") then newElementName
        else s"$newElementName.bend"
      BendSourcePathEdits
        .withFileName(spelling, fileName)
        .map(BendPathReferenceEditing.replace(this, _))
        .getOrElse(getElement)
