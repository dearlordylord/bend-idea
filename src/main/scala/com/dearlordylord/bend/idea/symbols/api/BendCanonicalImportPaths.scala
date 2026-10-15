package com.dearlordylord.bend.idea.symbols.api

import com.dearlordylord.bend.idea.workspace.api.*
import com.intellij.psi.PsiFile
import com.intellij.openapi.progress.ProgressManager

final case class BendCanonicalImportPath(
    start: Int,
    end: Int,
    original: String,
    replacement: String
)

/** Uses the loading owner to prove a proposed spelling selects the same source.
  * No path normalization or filesystem policy is duplicated by the inspection.
  */
object BendCanonicalImportPaths:
  def in(file: PsiFile): List[BendCanonicalImportPath] =
    val project = file.getProject
    val (base, cache) =
      project.getService(classOf[BendLoadingConfiguration]).paths
    val workspace = project.getService(classOf[BendWorkspaceGraph])
    val graph = project
      .getService(classOf[BendImportedSymbolCatalog])
      .loaded(file, base, cache)
    if graph.problems.nonEmpty || graph.sourceInventoryCapped then return Nil
    graph.source(graph.root).toList.filter(_.text == file.getText).flatMap {
      root =>
        root.imports.flatMap { imp =>
          ProgressManager.checkCanceled()
          if imp.spelling == "Base" || BendImportPaths.isPackageImport(
              imp.spelling
            )
          then Nil
          else
            (for
              target <- graph.importTarget(graph.root, imp.offset)
              relative <- BendSourcePathEdits.relativeSpelling(
                root.path,
                target.value
              )
              replacement =
                if relative.startsWith("../") then relative else "./" + relative
              if replacement != imp.spelling
              range <- BendImportLines.pathRange(root.text, imp)
              text = root.text
                .take(range._1) + replacement + root.text.drop(range._2)
              candidate = root.copy(
                text = text,
                imports = BendImportLines.parse(text)
              )
              changed = workspace.load(
                candidate,
                base,
                cache,
                () => { ProgressManager.checkCanceled(); false }
              )
              if changed.problems.isEmpty && !changed.sourceInventoryCapped
              if changed.importTarget(changed.root, imp.offset).contains(target)
              if workspace.contentsCurrent(
                graph,
                cache,
                () => { ProgressManager.checkCanceled(); false }
              )
              if graph.files.map(f =>
                (f.source.id, f.namespace)
              ) == changed.files.map(f => (f.source.id, f.namespace))
            yield BendCanonicalImportPath(
              range._1,
              range._2,
              imp.spelling,
              replacement
            )).toList
        }
    }
