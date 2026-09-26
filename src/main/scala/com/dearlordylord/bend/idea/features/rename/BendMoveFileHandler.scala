package com.dearlordylord.bend.idea.features.rename

import com.dearlordylord.bend.idea.symbols.api.{
  BendImportedSymbolCatalog,
  BendSourcePathKind,
  BendSourcePathReference,
  BendSourcePathReferences
}
import com.dearlordylord.bend.idea.syntax.psi.BendForeignPaths
import com.dearlordylord.bend.idea.workspace.api.{
  BendImportLines,
  BendImportPaths,
  BendLoadingConfiguration,
  BendSourcePathEdits
}
import com.intellij.psi.{PsiDirectory, PsiElement, PsiFile}
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.refactoring.move.moveFilesOrDirectories.MoveFileHandler
import com.intellij.usageView.UsageInfo
import com.intellij.util.IncorrectOperationException
import scala.jdk.CollectionConverters.*

/** Native move preview for Bend module and foreign source paths. */
final class BendMoveFileHandler extends MoveFileHandler:
  override def canProcessElement(file: PsiFile): Boolean =
    val name = file.getName
    (name.endsWith(".bend") || name.endsWith(".c") || name.endsWith(".js")) &&
    file.isWritable && file.getVirtualFile != null &&
    ProjectRootManager
      .getInstance(file.getProject)
      .getFileIndex
      .isInContent(file.getVirtualFile)

  override def prepareMovedFile(
      file: PsiFile,
      destination: PsiDirectory,
      allRenames: java.util.Map[PsiElement, PsiElement]
  ): Unit = ()

  override def findUsages(
      file: PsiFile,
      destination: PsiDirectory,
      searchInComments: Boolean,
      searchInNonJavaFiles: Boolean
  ): java.util.List[UsageInfo] =
    val newPath = destination.getVirtualFile.getPath + "/" + file.getName
    val incoming = BendSourcePathReferences
      .to(file)
      .fold(
        message => throw new IncorrectOperationException(message),
        identity
      )
    val references = (incoming ++ file.getReferences.toList).distinct
    val planned = references.flatMap { reference =>
      val source = reference.getElement.getContainingFile
      if source == null || source.getVirtualFile == null then Nil
      else
        val movingSource = source == file
        val sourcePath =
          if movingSource then newPath else source.getVirtualFile.getPath
        val pathReference = reference match
          case path: BendSourcePathReference => Some(path)
          case _                             => None
        val original = pathReference.map(_.spelling).getOrElse("")
        val targetPath =
          if BendSourcePathReferences.refersTo(
              reference,
              file,
              file.getProject
            )
          then Some(newPath)
          else resolvedTargetPath(reference, source.getVirtualFile.getPath)
        targetPath match
          case None if movingSource =>
            throw new IncorrectOperationException(
              "Cannot move a Bend source with an unresolved path reference."
            )
          case None         => Nil
          case Some(target) =>
            val replacement = pathReference.flatMap {
              case path if path.pathKind == BendSourcePathKind.Module =>
                moduleSpelling(reference, sourcePath, target, original)
              case path if path.pathKind == BendSourcePathKind.Foreign =>
                BendSourcePathEdits
                  .relativeSpelling(sourcePath, target)
                  .map(value => (value, None))
            }
            replacement match
              case Some((value, namespace)) if value != original =>
                List(new BendMoveReferenceUsage(reference, value, namespace))
              case Some(_) => Nil
              case None    =>
                throw new IncorrectOperationException(
                  "Cannot safely rewrite a Bend path for this move."
                )
    }
    val namespaceConflicts = planned
      .flatMap(usage => usage.namespace.map((key, name) => (key, name)))
      .groupMap(_._1)(_._2)
      .values
      .exists(_.distinct.size > 1)
    if namespaceConflicts then
      throw new IncorrectOperationException(
        "This move would load one Bend source under conflicting namespaces."
      )
    planned.asJava

  override def detectConflicts(
      conflicts: com.intellij.util.containers.MultiMap[PsiElement, String],
      elements: Array[PsiElement],
      usages: Array[UsageInfo],
      destination: PsiDirectory
  ): Unit =
    super.detectConflicts(conflicts, elements, usages, destination)
    elements
      .collect { case file: PsiFile =>
        file
      }
      .foreach { file =>
        val destinationPath =
          destination.getVirtualFile.getPath + "/" + file.getName
        if exists(destinationPath) then
          conflicts.putValue(
            file,
            "A source already exists at " + destinationPath
          )
      }

  override def retargetUsages(
      usages: java.util.List[? <: UsageInfo],
      oldToNewMap: java.util.Map[PsiElement, PsiElement]
  ): Unit =
    usages.asScala
      .collect { case usage: BendMoveReferenceUsage => usage }
      .toList
      .groupBy(_.reference.getElement.getContainingFile)
      .foreach { case (file, entries) =>
        val manager =
          com.intellij.psi.PsiDocumentManager.getInstance(file.getProject)
        val document = manager.getDocument(file)
        if document == null then
          throw new IncorrectOperationException(
            "Cannot edit a Bend path in a source without a document."
          )
        entries
          .sortBy(_.reference.getRangeInElement.getStartOffset)
          .reverse
          .foreach { usage =>
            val range = usage.reference.getRangeInElement
            document.replaceString(
              range.getStartOffset,
              range.getEndOffset,
              usage.spelling
            )
          }
        manager.commitDocument(document)
      }

  override def updateMovedFile(file: PsiFile): Unit = ()

  private def exists(path: String): Boolean =
    try java.nio.file.Files.exists(java.nio.file.Path.of(path))
    catch case _: Exception => false

  private def resolvedTargetPath(
      reference: com.intellij.psi.PsiReference,
      sourcePath: String
  ): Option[String] =
    Option(reference.resolve())
      .flatMap(element => Option(element.getContainingFile))
      .flatMap(file => Option(file.getVirtualFile))
      .map(_.getPath)
      .orElse {
        reference match
          case path: BendSourcePathReference =>
            path.pathKind match
              case BendSourcePathKind.Foreign =>
                BendForeignPaths
                  .resolve(sourcePath, path.spelling)
                  .map(_.toString)
              case BendSourcePathKind.Module
                  if path.spelling != "Base" && !path.spelling.startsWith(
                    "0x"
                  ) =>
                val (_, cache) = reference.getElement.getProject
                  .getService(classOf[BendLoadingConfiguration])
                  .paths
                Some(BendImportPaths.target(sourcePath, cache, path.spelling))
              case BendSourcePathKind.Module => None
          case _ => None
      }

  private def moduleSpelling(
      reference: com.intellij.psi.PsiReference,
      sourcePath: String,
      targetPath: String,
      original: String
  ): Option[(String, Option[((String, String), String)])] =
    if original == "Base" || original.startsWith("0x") then None
    else
      BendSourcePathEdits.relativeSpelling(sourcePath, targetPath).flatMap {
        spelling =>
          val source = reference.getElement.getContainingFile
          val (base, cache) = source.getProject
            .getService(classOf[BendLoadingConfiguration])
            .paths
          val graph = source.getProject
            .getService(classOf[BendImportedSymbolCatalog])
            .loaded(source, base, cache)
          val range = reference.getRangeInElement
          val importLine = BendImportLines
            .parse(source.getText)
            .find(line =>
              BendImportLines.pathRange(source.getText, line).exists {
                case (start, end) =>
                  start == range.getStartOffset && end == range.getEndOffset
              }
            )
          val edge = graph.edges.find(edge =>
            edge.from == graph.root &&
              importLine.exists(_.offset == edge.importLine.offset)
          )
          val sourceNamespace = graph.files
            .find(_.source.id == graph.root)
            .map(_.namespace)
            .getOrElse("")
          val targetIdentity = Option(reference.resolve())
            .flatMap(element => Option(element.getContainingFile))
            .flatMap(file => Option(file.getVirtualFile))
            .map(_.getCanonicalPath)
            .getOrElse(targetPath)
          val namespace = BendImportPaths.namespace(sourceNamespace, spelling)
          edge.flatMap(_ =>
            importLine
              .flatMap(_.alias)
              .map(_ =>
                (
                  spelling,
                  Some(
                    ((source.getVirtualFile.getPath, targetIdentity), namespace)
                  )
                )
              )
          )
      }

private[rename] final class BendMoveReferenceUsage(
    val reference: com.intellij.psi.PsiReference,
    val spelling: String,
    val namespace: Option[((String, String), String)]
) extends UsageInfo(reference):
  override def getReference: com.intellij.psi.PsiReference = reference
