package com.dearlordylord.bend.idea.features.proofs

import com.dearlordylord.bend.idea.features.templates.api.BendSnippets
import com.dearlordylord.bend.idea.model.FileId
import com.dearlordylord.bend.idea.symbols.api.*
import com.dearlordylord.bend.idea.syntax.psi.{BendLaw, BendSourceParameter}
import com.dearlordylord.bend.idea.workspace.api.BendLoadingConfiguration
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.{PsiDocumentManager, PsiFile}

final case class BendProofFillTarget(
    path: String,
    name: String,
    root: PsiFile,
    lawHandle: BendSourceHandle,
    lawName: String,
    parameters: List[BendSourceParameter],
    lawSource: PsiFile,
    rootRevision: BendProofSourceRevision,
    lawRevision: BendProofSourceRevision,
    loadingPaths: (String, String)
):
  override def toString: String = s"def $name — $path"

final case class BendProofSourceRevision(
    text: String,
    documentStamp: Long,
    virtualFileStamp: Long
)

final case class BendProofFillInsertion(
    file: VirtualFile,
    placeholderStart: Int,
    placeholderEnd: Int
)

/** Root-aware fill planning and one-command source insertion. */
object BendProofFillGenerator:
  private val ValidQualifiedName =
    "[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*".r

  def candidates(
      law: BendLaw,
      rootPaths: List[String]
  ): List[BendProofFillTarget] =
    val sourceFile = law.getContainingFile
    val project = law.getProject
    val selected = BendSourceSymbols
      .declarations(sourceFile)
      .find(symbol =>
        symbol.category == BendSymbolCategory.Law &&
          symbol.handle.nameOffset == law.getNameIdentifier.getTextOffset
      )
    selected.toList
      .flatMap { selectedLaw =>
        rootPaths.flatMap { path =>
          rootFile(sourceFile, path).toList.flatMap { root =>
            if !writable(root) then Nil
            else
              val (basePath, cachePath) = project
                .getService(classOf[BendLoadingConfiguration])
                .paths
              val catalog = project.getService(
                classOf[BendImportedSymbolCatalog]
              )
              val (_, current, imported) = catalog.visibleWithCurrent(
                root,
                root.getTextLength,
                basePath,
                cachePath
              )
              val visible = current.map(s => s.name -> s) ++ imported
              visible.collect {
                case (visibleName, symbol)
                    if symbol.category == BendSymbolCategory.Law &&
                      symbol.handle == selectedLaw.handle &&
                      ValidQualifiedName.matches(visibleName) &&
                      !hasDefinition(root, visibleName) =>
                  BendProofFillTarget(
                    path,
                    visibleName,
                    root,
                    selectedLaw.handle,
                    selectedLaw.name,
                    selectedLaw.signature.parameters,
                    sourceFile,
                    revision(project, root),
                    revision(project, sourceFile),
                    (basePath, cachePath)
                  )
              }
          }
        }
      }
      .distinctBy(target => (target.path, target.name))

  /** Inserts at EOF as one undoable write command and returns the `?TODO` range
    * for the editor to select. It rechecks writability and collisions inside
    * the command before touching the document.
    */
  def insert(
      project: Project,
      target: BendProofFillTarget
  ): Either[String, BendProofFillInsertion] =
    if !lawIsStillVisible(project, target.root, target) then
      return Left(
        "The law or proof-root imports changed; generate the fill again"
      )
    WriteCommandAction
      .writeCommandAction(project)
      .compute[Either[
        String,
        BendProofFillInsertion
      ], RuntimeException](() =>
        val root = target.root
        Option(root.getVirtualFile) match
          case None => Left("The selected proof root has no writable file")
          case Some(file) if !writable(root) =>
            Left("The selected proof root is read-only")
          case Some(file)
              if !root.isValid || file.getPath != target.path ||
                !revisionMatches(project, root, target.rootRevision) ||
                !target.lawSource.isValid ||
                !revisionMatches(
                  project,
                  target.lawSource,
                  target.lawRevision
                ) ||
                project
                  .getService(classOf[BendLoadingConfiguration])
                  .paths != target.loadingPaths =>
            Left("Source changed after planning; generate the fill again")
          case Some(file) if hasDefinition(root, target.name) =>
            Left(s"${target.name} already has a definition in this proof root")
          case Some(file) =>
            Option(
              PsiDocumentManager.getInstance(project).getDocument(root)
            ) match
              case None =>
                Left("Could not open the selected proof root document")
              case Some(document) =>
                val rendered = BendSnippets.renderLawFill(
                  target.name,
                  target.parameters
                )
                val prefix = separator(document)
                val placeholderStart =
                  document.getTextLength + prefix.length + rendered
                    .indexOf("?TODO")
                document.insertString(document.getTextLength, prefix + rendered)
                PsiDocumentManager.getInstance(project).commitDocument(document)
                Right(
                  BendProofFillInsertion(
                    file,
                    placeholderStart,
                    placeholderStart + "?TODO".length
                  )
                )
      )

  private def separator(document: Document): String =
    val text = document.getText
    if text.isEmpty then ""
    else if text.endsWith("\n\n") then ""
    else if text.endsWith("\n") then "\n"
    else "\n\n"

  private def writable(file: PsiFile): Boolean =
    Option(file.getVirtualFile).exists(_.isWritable) && file.isWritable

  private def rootFile(origin: PsiFile, path: String): Option[PsiFile] =
    if Option(origin.getVirtualFile).exists(_.getPath == path) then Some(origin)
    else BendPhysicalTargets.file(origin.getProject, new FileId(path, false))

  private def hasDefinition(root: PsiFile, name: String): Boolean =
    BendSourceSymbols
      .declarations(root)
      .exists(symbol =>
        symbol.category == BendSymbolCategory.Definition && symbol.name == name
      )

  private def lawIsStillVisible(
      project: Project,
      root: PsiFile,
      target: BendProofFillTarget
  ): Boolean =
    try
      val (basePath, cachePath) = project
        .getService(classOf[BendLoadingConfiguration])
        .paths
      if (basePath, cachePath) != target.loadingPaths then return false
      val (_, current, imported) = project
        .getService(classOf[BendImportedSymbolCatalog])
        .visibleWithCurrent(root, root.getTextLength, basePath, cachePath)
      (current.map(symbol => symbol.name -> symbol) ++ imported).exists {
        case (visibleName, symbol) =>
          visibleName == target.name && symbol.handle == target.lawHandle &&
          symbol.name == target.lawName &&
          symbol.signature.parameters == target.parameters
      }
    catch case scala.util.control.NonFatal(_) => false

  private def revision(
      project: Project,
      file: PsiFile
  ): BendProofSourceRevision =
    val document = PsiDocumentManager.getInstance(project).getDocument(file)
    val sourceText = Option(document).map(_.getText).getOrElse(file.getText)
    val documentStamp = Option(document)
      .map(_.getModificationStamp)
      .getOrElse(file.getModificationStamp)
    val virtualStamp = Option(file.getVirtualFile)
      .map(_.getModificationStamp)
      .getOrElse(-1L)
    BendProofSourceRevision(sourceText, documentStamp, virtualStamp)

  private def revisionMatches(
      project: Project,
      file: PsiFile,
      expected: BendProofSourceRevision
  ): Boolean =
    file.isValid && revision(project, file) == expected
