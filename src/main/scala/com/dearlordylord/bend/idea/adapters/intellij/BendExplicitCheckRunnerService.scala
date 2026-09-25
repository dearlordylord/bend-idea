package com.dearlordylord.bend.idea.adapters.intellij

import com.dearlordylord.bend.idea.analysis.api.*
import com.dearlordylord.bend.idea.analysis.model.*
import com.dearlordylord.bend.idea.model.FileId
import com.dearlordylord.bend.idea.toolchain.api.BendToolchainSettings
import com.dearlordylord.bend.idea.workspace.api.{
  BendImportLines,
  BendSourceCatalog,
  BendWorkspaceGraph
}
import com.dearlordylord.bend.idea.workspace.model.BendSourceRecord
import com.intellij.openapi.application.{ApplicationManager, ReadAction}
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.event.{DocumentEvent, DocumentListener}
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.{ProgressIndicator, ProgressManager, Task}
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import java.nio.file.Path
import scala.util.control.NonFatal

/** Platform-owned snapshot capture and lifecycle for explicit check requests.
  */
final class BendExplicitCheckRunnerService(project: Project)
    extends BendExplicitCheckRunner:
  override def check(
      path: String,
      taskTitle: String
  )(completed: BendExplicitCheckOutcome => Unit): Unit =
    val initialPath = Path.of(path).toAbsolutePath.normalize().toString
    val canonical =
      Option(
        LocalFileSystem.getInstance().findFileByNioFile(Path.of(initialPath))
      ).flatMap(file => Option(file.getCanonicalPath))
    val requestedId =
      new FileId(canonical.getOrElse(initialPath), canonical.isDefined)
    val checkService = project.getService(classOf[BendCheckService])
    def finish(outcome: BendExplicitCheckOutcome): Unit =
      ApplicationManager.getApplication.invokeLater(() => {
        if !project.isDisposed then
          val delivered = outcome match
            case BendExplicitCheckOutcome.Published(result)
                if !checkService.isCurrent(result) =>
              BendExplicitCheckOutcome.Rejected(
                result.key.root,
                "Check result became stale before delivery"
              )
            case other => other
          completed(delivered)
      })
    ProgressManager
      .getInstance()
      .run(new Task.Backgroundable(project, taskTitle, true):
        override def onCancel(): Unit = checkService.cancel(requestedId)

        override def run(indicator: ProgressIndicator): Unit =
          val catalog = project.getService(classOf[BendSourceCatalog])
          val selection = ApplicationManager.getApplication
            .getService(classOf[BendToolchainSettings])
            .selection
          val initial = catalog.source(initialPath)
          if initial.isEmpty then
            finish(
              BendExplicitCheckOutcome
                .Rejected(requestedId, "Bend source is unavailable")
            )
            return
          val root = initial.get
          val snapshot = BendCheckSnapshot(
            root.id,
            root.path,
            root.text,
            root.revision,
            selection
          )
          val document = documentAt(Path.of(initialPath))
          def sourceCurrent: Boolean = catalog
            .source(initialPath)
            .exists(current =>
              current.id == root.id && current.revision == root.revision && current.text == root.text
            )
          val reservation = checkService.begin(
            snapshot,
            callback => subscribe(document, callback),
            () => sourceCurrent
          )
          if reservation.isEmpty then
            finish(
              BendExplicitCheckOutcome.Rejected(
                root.id,
                "A Bend check is already running"
              )
            )
            return
          try
            val graphService = project.getService(classOf[BendWorkspaceGraph])
            val rootSource = BendSourceRecord(
              root.id,
              root.path,
              root.text,
              root.revision,
              BendImportLines.parse(root.text)
            )
            val graph = graphService.load(
              rootSource,
              selection.baseSource,
              selection.packageCache,
              () => indicator.isCanceled || project.isDisposed
            )
            val siblingLaws = Option(Path.of(root.path).getFileName)
              .map(_.toString)
              .filter(_ == "PROOF.bend")
              .flatMap(_ => graphService.siblingLaws(root.path))
            val captured =
              BendGraphSnapshot.attach(snapshot, graph, siblingLaws)
            val result = checkService.check(
              captured,
              reservation.get,
              () => indicator.isCanceled || project.isDisposed || !sourceCurrent
            )
            result match
              case Some(value) =>
                finish(BendExplicitCheckOutcome.Published(value))
              case None if indicator.isCanceled => ()
              case None                         =>
                finish(
                  BendExplicitCheckOutcome
                    .Rejected(root.id, "Check result is no longer current")
                )
          catch
            case NonFatal(_) if indicator.isCanceled =>
              checkService.cancel(root.id)
            case NonFatal(error) =>
              checkService.cancel(root.id)
              val detail =
                Option(error.getMessage).getOrElse(error.getClass.getSimpleName)
              finish(
                BendExplicitCheckOutcome.Rejected(
                  root.id,
                  s"Could not check selected root: $detail"
                )
              ))

  private def subscribe(
      document: Option[Document],
      callback: () => Unit
  ): () => Unit =
    document match
      case None        => () => ()
      case Some(value) =>
        val listener = new DocumentListener:
          override def documentChanged(event: DocumentEvent): Unit = callback()
        value.addDocumentListener(listener)
        () => value.removeDocumentListener(listener)

  private[intellij] def documentAt(path: Path): Option[Document] =
    ReadAction.compute(() =>
      Option(LocalFileSystem.getInstance().findFileByNioFile(path))
        .flatMap(file =>
          Option(FileDocumentManager.getInstance().getDocument(file))
        )
    )
