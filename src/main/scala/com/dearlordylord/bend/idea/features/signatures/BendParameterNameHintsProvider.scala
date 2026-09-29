package com.dearlordylord.bend.idea.features.signatures

import com.dearlordylord.bend.idea.symbols.api.{
  BendApplicationKind,
  BendImportedSymbolCatalog,
  BendSourceApplications,
  BendSourceCallSignatures
}
import com.dearlordylord.bend.idea.syntax.psi.BendReferenceElement
import com.dearlordylord.bend.idea.workspace.api.BendLoadingConfiguration
import com.intellij.codeInsight.hints.{InlayInfo, InlayParameterHintsProvider}
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.{PsiElement, PsiFile}
import com.intellij.psi.util.PsiModificationTracker
import scala.jdk.CollectionConverters.*

private object BendParameterNameHintsProvider:
  final case class Revision(
      source: Long,
      psi: Long,
      vfs: Long,
      loading: Long
  )
  final case class Rendered(
      revision: Revision,
      byCallee: Map[Int, List[InlayInfo]]
  )
  val renderedKey: Key[Rendered] = Key.create("bend.parameter.name.hints")

/** Optional source-name hints for calls whose declaration resolves uniquely. */
final class BendParameterNameHintsProvider extends InlayParameterHintsProvider:
  override def getParameterHints(
      element: PsiElement,
      file: PsiFile
  ): java.util.List[InlayInfo] =
    element match
      case _: BendReferenceElement if file != null =>
        rendered(file)
          .getOrElse(
            element.getTextOffset,
            Nil
          )
          .asJava
      case _ => java.util.Collections.emptyList[InlayInfo]()

  private def revision(file: PsiFile): BendParameterNameHintsProvider.Revision =
    val project = file.getProject
    BendParameterNameHintsProvider.Revision(
      file.getModificationStamp,
      PsiModificationTracker.getInstance(project).getModificationCount,
      VirtualFileManager.getInstance().getModificationCount,
      project
        .getService(classOf[BendLoadingConfiguration])
        .configurationRevision
    )

  private def rendered(file: PsiFile): Map[Int, List[InlayInfo]] =
    val before = revision(file)
    val cached = file.getUserData(BendParameterNameHintsProvider.renderedKey)
    if cached != null && cached.revision == before then cached.byCallee
    else
      val project = file.getProject
      val (basePath, packageCache) =
        project.getService(classOf[BendLoadingConfiguration]).paths
      val applications = BendSourceApplications.forCallees(file)
      val catalog = project.getService(classOf[BendImportedSymbolCatalog])
      val snapshot = catalog.navigationSnapshot(file, basePath, packageCache)
      val candidates = applications.valuesIterator
        .filter(application =>
          application.kind == BendApplicationKind.Function ||
            application.kind == BendApplicationKind.Constructor
        )
        .toList
      val byCallee = BendSourceCallSignatures
        .hintsForCallees(snapshot, candidates)
        .view
        .mapValues(_.map { case (name, offset) =>
          new InlayInfo(s"$name:", offset)
        })
        .toMap
      if revision(file) != before then Map.empty
      else
        file.putUserData(
          BendParameterNameHintsProvider.renderedKey,
          BendParameterNameHintsProvider.Rendered(before, byCallee)
        )
        byCallee

  override def getDefaultBlackList: java.util.Set[String] =
    java.util.Collections.emptySet[String]()

  override def getMainCheckboxText: String = "Show Bend parameter names"

  override def getDescription: String =
    "Show source parameter names for uniquely resolved Bend calls."

  override def getSettingsPreview: String = "combine(add: 1, scale: 2)"
