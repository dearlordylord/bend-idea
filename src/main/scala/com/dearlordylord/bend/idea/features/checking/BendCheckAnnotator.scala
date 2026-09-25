package com.dearlordylord.bend.idea.features.checking

import com.dearlordylord.bend.idea.analysis.api.BendCheckService
import com.dearlordylord.bend.idea.analysis.model.*
import com.dearlordylord.bend.idea.model.FileId
import com.dearlordylord.bend.idea.toolchain.api.BendToolchainSettings
import com.intellij.lang.annotation.{
  AnnotationHolder,
  ExternalAnnotator,
  HighlightSeverity
}
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile

/** Projects an existing explicit root result into editor highlights and
  * Problems.
  */
final class BendCheckAnnotator
    extends ExternalAnnotator[PsiFile, List[BendCheckResult]]:
  override def collectInformation(file: PsiFile): PsiFile = file

  override def doAnnotate(file: PsiFile): List[BendCheckResult] =
    val captured = ReadAction.compute(() => {
      val virtual = file.getVirtualFile
      if virtual == null then None
      else
        val canonical = Option(virtual.getCanonicalPath)
        val id =
          new FileId(canonical.getOrElse(virtual.getPath), canonical.isDefined)
        val document = FileDocumentManager.getInstance().getDocument(virtual)
        Option(document).map(doc => (id, doc.getModificationStamp, doc.getText))
    })
    captured match
      case None                       => Nil
      case Some((id, revision, text)) =>
        val selection = ApplicationManager.getApplication
          .getService(classOf[BendToolchainSettings])
          .selection
        file.getProject
          .getService(classOf[BendCheckService])
          .resultsFor(id)
          .filter { result =>
            val captured = result.sources.find(_.id == id)
            val sourceCurrent = captured match
              case Some(source) =>
                source.text == text && source.revision == revision
              case None =>
                result.key.root == id && result.key.sourceRevision == revision &&
                result.key.sourceFingerprint == BendAnalysisKey.sourceDigest(
                  text
                )
            result.fresh && sourceCurrent &&
            result.key.configurationRevision == selection.configurationRevision &&
            result.key.executable == selection.executable
          }

  override def apply(
      file: PsiFile,
      results: List[BendCheckResult],
      holder: AnnotationHolder
  ): Unit =
    if results == null then return
    val virtual = file.getVirtualFile
    if virtual == null then return
    val id = new FileId(
      Option(virtual.getCanonicalPath).getOrElse(virtual.getPath),
      virtual.getCanonicalPath != null
    )
    results
      .filter(result => BendCheckAnnotator.shouldHighlight(result.outcome))
      .foreach { result =>
        result.diagnostics.foreach { diagnostic =>
          BendCheckAnnotator
            .sourceLine(diagnostic.location, result.key.root, id)
            .flatMap(line => lineRange(file, line))
            .foreach { range =>
              val _ = holder
                .newAnnotation(HighlightSeverity.ERROR, diagnostic.message)
                .range(range)
                .create()
            }
        }
      }

  private def lineRange(file: PsiFile, line: Int): Option[TextRange] =
    Option(file.getViewProvider.getDocument)
      .filter(document => line >= 0 && line < document.getLineCount)
      .flatMap { document =>
        val start = document.getLineStartOffset(line)
        if start >= file.getTextLength then None
        else
          Some(
            new TextRange(
              start,
              math.max(start + 1, document.getLineEndOffset(line))
            )
          )
      }

object BendCheckAnnotator:
  private[checking] def shouldHighlight(outcome: BendCheckOutcome): Boolean =
    outcome == BendCheckOutcome.Failed

  private[checking] def sourceLine(
      location: BendLocation,
      resultRoot: FileId,
      target: FileId
  ): Option[Int] =
    location match
      case BendLocation.Line(number) if resultRoot == target && number >= 0 =>
        Some(number)
      case BendLocation.SourceLine(source, number)
          if source == target && number >= 0 =>
        Some(number)
      case _ => None
