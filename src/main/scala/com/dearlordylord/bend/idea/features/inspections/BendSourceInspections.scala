package com.dearlordylord.bend.idea.features.inspections

import com.dearlordylord.bend.idea.symbols.api.*
import com.dearlordylord.bend.idea.syntax.BendFile
import com.dearlordylord.bend.idea.syntax.psi.{
  BendAlias,
  BendConstructor,
  BendDatatype
}
import com.intellij.codeInspection.{
  LocalInspectionTool,
  LocalQuickFix,
  ProblemDescriptor,
  ProblemHighlightType,
  ProblemsHolder
}
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.{PsiDocumentManager, PsiElementVisitor, PsiFile}

final class BendNamingInspection extends LocalInspectionTool:
  override def buildVisitor(
      holder: ProblemsHolder,
      onTheFly: Boolean
  ): PsiElementVisitor =
    new PsiElementVisitor:
      override def visitElement(element: com.intellij.psi.PsiElement): Unit =
        val name = element match
          case declaration: BendDatatype =>
            Option(declaration.getNameIdentifier)
          case declaration: BendConstructor =>
            Option(declaration.getNameIdentifier)
          case alias: BendAlias => Some(alias)
          case _                => None
        name.foreach { identifier =>
          val segment = identifier.getText.split('.').lastOption.getOrElse("")
          if segment.nonEmpty && !segment.matches("[A-Z][A-Za-z0-9]*") then
            holder.registerProblem(
              identifier,
              "Type, constructor and import alias names conventionally use PascalCase",
              ProblemHighlightType.WEAK_WARNING
            )
        }

final class BendUnusedImportAliasInspection extends LocalInspectionTool:
  override def buildVisitor(
      holder: ProblemsHolder,
      onTheFly: Boolean
  ): PsiElementVisitor =
    new PsiElementVisitor:
      override def visitFile(file: PsiFile): Unit =
        if file.isInstanceOf[BendFile] then
          BendUnusedImportAliases
            .in(file)
            .foreach(alias =>
              holder.registerProblem(
                alias,
                "Import alias has no source uses in this file; the import still loads declarations",
                ProblemHighlightType.WEAK_WARNING
              )
            )

final class BendCanonicalImportPathInspection extends LocalInspectionTool:
  override def buildVisitor(
      holder: ProblemsHolder,
      onTheFly: Boolean
  ): PsiElementVisitor =
    new PsiElementVisitor:
      override def visitFile(file: PsiFile): Unit =
        if file.isInstanceOf[BendFile] then
          BendCanonicalImportPaths.in(file).foreach { path =>
            holder.registerProblem(
              file,
              "Import path can use a canonical relative spelling",
              ProblemHighlightType.WEAK_WARNING,
              new TextRange(path.start, path.end),
              new BendCanonicalImportPathFix(path)
            )
          }

final class BendCanonicalImportPathFix(expected: BendCanonicalImportPath)
    extends LocalQuickFix:
  override def getFamilyName: String = "Use canonical Bend import path"
  override def applyFix(project: Project, descriptor: ProblemDescriptor): Unit =
    val file = descriptor.getPsiElement.getContainingFile
    if file == null || !BendCanonicalImportPaths.in(file).contains(expected)
    then return
    val manager = PsiDocumentManager.getInstance(project)
    val document = manager.getDocument(file)
    if document != null && document.getText.substring(
        expected.start,
        expected.end
      ) == expected.original
    then
      document.replaceString(expected.start, expected.end, expected.replacement)
      manager.commitDocument(document)
