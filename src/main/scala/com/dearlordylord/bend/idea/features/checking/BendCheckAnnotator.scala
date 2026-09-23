package com.dearlordylord.bend.idea.features.checking

import com.dearlordylord.bend.idea.analysis.api.BendCheckService
import com.dearlordylord.bend.idea.analysis.model.*
import com.dearlordylord.bend.idea.model.FileId
import com.dearlordylord.bend.idea.toolchain.api.BendToolchainSettings
import com.intellij.lang.annotation.{AnnotationHolder, ExternalAnnotator, HighlightSeverity}
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile

/** Projects an existing explicit root result into editor highlights and Problems. */
final class BendCheckAnnotator extends ExternalAnnotator[PsiFile, BendCheckResult]:
  override def collectInformation(file: PsiFile): PsiFile = file

  override def doAnnotate(file: PsiFile): BendCheckResult =
    val virtual = file.getVirtualFile
    if virtual == null then null
    else
      val id = new FileId(Option(virtual.getCanonicalPath).getOrElse(virtual.getPath),
        virtual.getCanonicalPath != null)
      val document = FileDocumentManager.getInstance().getDocument(virtual)
      val selection = ApplicationManager.getApplication.getService(classOf[BendToolchainSettings]).selection
      val current = file.getProject.getService(classOf[BendCheckService]).result(id)
      current.filter(r => r.fresh && document != null &&
        r.key.sourceRevision == document.getModificationStamp &&
        r.key.sourceFingerprint == BendAnalysisKey.sourceDigest(document.getText) &&
        r.key.configurationRevision == selection.configurationRevision &&
        r.key.executable == selection.executable).orNull

  override def apply(file: PsiFile, result: BendCheckResult, holder: AnnotationHolder): Unit =
    if result != null && result.outcome != BendCheckOutcome.Success then
      val severity = result.outcome match
        case BendCheckOutcome.Failed => HighlightSeverity.ERROR
        case _ => HighlightSeverity.WARNING
      result.diagnostics.foreach { diagnostic =>
        val range = diagnostic.location match
          case BendLocation.Line(number) if number >= 0 && number < file.getViewProvider.getDocument.getLineCount =>
            val doc = file.getViewProvider.getDocument
            val start = doc.getLineStartOffset(number)
            if start < file.getTextLength then
              new TextRange(start, math.max(start + 1, doc.getLineEndOffset(number)))
            else new TextRange(0, 1)
          case _ => new TextRange(0, math.max(1, math.min(file.getTextLength, 1)))
        if file.getTextLength > 0 then
          holder.newAnnotation(severity, diagnostic.message).range(range).create()
      }
