package com.dearlordylord.bend.idea.features.checking

import com.dearlordylord.bend.idea.analysis.api.BendCheckService
import com.dearlordylord.bend.idea.analysis.model.*
import com.dearlordylord.bend.idea.model.FileId
import com.dearlordylord.bend.idea.toolchain.api.BendToolchainSettings
import com.intellij.lang.annotation.{AnnotationHolder, ExternalAnnotator, HighlightSeverity}
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile

/** Projects an existing explicit root result into editor highlights and Problems. */
final class BendCheckAnnotator extends ExternalAnnotator[PsiFile, List[BendCheckResult]]:
  override def collectInformation(file: PsiFile): PsiFile = file

  override def doAnnotate(file: PsiFile): List[BendCheckResult] =
    val captured = ReadAction.compute(() => {
      val virtual = file.getVirtualFile
      if virtual == null then None
      else
        val canonical = Option(virtual.getCanonicalPath)
        val id = new FileId(canonical.getOrElse(virtual.getPath), canonical.isDefined)
        val document = FileDocumentManager.getInstance().getDocument(virtual)
        Option(document).map(doc => (id, doc.getModificationStamp, doc.getText))
    })
    captured match
      case None => Nil
      case Some((id, revision, text)) =>
        val selection = ApplicationManager.getApplication.getService(classOf[BendToolchainSettings]).selection
        file.getProject.getService(classOf[BendCheckService]).resultsFor(id).filter { result =>
          val captured = result.sources.find(_.id == id)
          val sourceCurrent = captured match
            case Some(source) => source.text == text && source.revision == revision
            case None => result.key.root == id && result.key.sourceRevision == revision &&
              result.key.sourceFingerprint == BendAnalysisKey.sourceDigest(text)
          result.fresh && sourceCurrent &&
            result.key.configurationRevision == selection.configurationRevision &&
            result.key.executable == selection.executable
        }

  override def apply(file: PsiFile, results: List[BendCheckResult], holder: AnnotationHolder): Unit =
    if results == null then return
    val virtual = file.getVirtualFile
    if virtual == null then return
    val id = new FileId(Option(virtual.getCanonicalPath).getOrElse(virtual.getPath),
      virtual.getCanonicalPath != null)
    results.filter(_.outcome != BendCheckOutcome.Success).foreach { result =>
      val severity = result.outcome match
        case BendCheckOutcome.Failed => HighlightSeverity.ERROR
        case _ => HighlightSeverity.WARNING
      result.diagnostics.filter { diagnostic => diagnostic.location match
        case BendLocation.SourceLine(source, _) => source == id
        case _ => result.key.root == id
      }.foreach { diagnostic =>
        val range = diagnostic.location match
          case BendLocation.Line(number) if number >= 0 && number < file.getViewProvider.getDocument.getLineCount =>
            val doc = file.getViewProvider.getDocument
            val start = doc.getLineStartOffset(number)
            if start < file.getTextLength then
              new TextRange(start, math.max(start + 1, doc.getLineEndOffset(number)))
            else new TextRange(0, 1)
          case BendLocation.SourceLine(_, number) if number >= 0 &&
              number < file.getViewProvider.getDocument.getLineCount =>
            val doc = file.getViewProvider.getDocument
            val start = doc.getLineStartOffset(number)
            if start < file.getTextLength then
              new TextRange(start, math.max(start + 1, doc.getLineEndOffset(number)))
            else new TextRange(0, 1)
          case _ => new TextRange(0, math.max(1, math.min(file.getTextLength, 1)))
        if file.getTextLength > 0 then
          holder.newAnnotation(severity, diagnostic.message).range(range).create()
      }
    }
