package com.dearlordylord.bend.idea.features.checking

import com.dearlordylord.bend.idea.analysis.api.BendCheckService
import com.dearlordylord.bend.idea.analysis.model.*
import com.dearlordylord.bend.idea.model.FileId
import com.dearlordylord.bend.idea.workspace.api.BendSourceCatalog
import com.dearlordylord.bend.idea.symbols.api.BendPhysicalTargets
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.{FileDocumentManager, OpenFileDescriptor}
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile

/** Navigation only: the originating root result must still be current. */
final class BendNavigateDiagnosticCause(
    result: BendCheckResult,
    cause: BendRelatedDiagnostic
) extends IntentionAction:
  override def getText: String = "Go to original compiler error"
  override def getFamilyName: String = "Bend compiler diagnostics"
  override def startInWriteAction(): Boolean = false

  private[checking] def target(project: Project): Option[(VirtualFile, Int)] =
    if !project.getService(classOf[BendCheckService]).isCurrent(result) then
      None
    else
      val location = cause.location match
        case BendLocation.SourceRange(id, range) =>
          Some((id, Left(range.start)))
        case BendLocation.SourceLine(id, line) => Some((id, Right(line)))
        case BendLocation.Line(line) => Some((result.key.root, Right(line)))
        case BendLocation.RootOnly   => None
      location.flatMap { case (id, position) =>
        for
          source <- result.sources.find(_.id == id)
          current <- project
            .getService(classOf[BendSourceCatalog])
            .source(source.path)
          if current.id == source.id && current.text == source.text && current.revision == source.revision
          physical <- BendPhysicalTargets.file(project, current.id)
          virtual <- Option(physical.getVirtualFile)
          canonical = Option(virtual.getCanonicalPath)
          actual = new FileId(
            canonical.getOrElse(virtual.getPath),
            canonical.isDefined
          )
          if actual == id
          document <- Option(
            FileDocumentManager.getInstance().getDocument(virtual)
          )
          if document.getText == source.text
          offset <- position match
            case Left(value) =>
              Option.when(value >= 0 && value <= document.getTextLength)(value)
            case Right(line) =>
              Option.when(line >= 0 && line < document.getLineCount)(
                document.getLineStartOffset(line)
              )
        yield (virtual, offset)
      }

  override def isAvailable(
      project: Project,
      editor: Editor,
      file: PsiFile
  ): Boolean =
    target(project).nonEmpty

  override def invoke(project: Project, editor: Editor, file: PsiFile): Unit =
    target(project).foreach { case (virtual, offset) =>
      new OpenFileDescriptor(project, virtual, offset).navigate(true)
    }
