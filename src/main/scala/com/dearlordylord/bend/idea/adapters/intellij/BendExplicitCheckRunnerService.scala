package com.dearlordylord.bend.idea.adapters.intellij

import com.dearlordylord.bend.idea.analysis.api.*
import com.dearlordylord.bend.idea.analysis.model.*
import com.dearlordylord.bend.idea.model.FileId
import com.dearlordylord.bend.idea.toolchain.api.BendToolchainSettings
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
    run(path, taskTitle, goalRequested = false, None)(completed)

  override def checkGoal(
      path: String,
      taskTitle: String
  )(completed: BendExplicitCheckOutcome => Unit): Unit =
    run(path, taskTitle, goalRequested = true, None)(completed)

  override def checkNormalization(
      path: String,
      request: BendNormalizationRequest,
      taskTitle: String
  )(completed: BendExplicitCheckOutcome => Unit): Unit =
    run(path, taskTitle, goalRequested = false, Some(request))(completed)

  private def run(
      path: String,
      taskTitle: String,
      goalRequested: Boolean,
      normalizationRequest: Option[BendNormalizationRequest]
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
          val capture = new BendRootSnapshotCapture(project)
          val selection = ApplicationManager.getApplication
            .getService(classOf[BendToolchainSettings])
            .selection
          val initial = capture.initial(initialPath, selection)
          if initial.isEmpty then
            finish(
              BendExplicitCheckOutcome
                .Rejected(requestedId, "Bend source is unavailable")
            )
            return
          val root = initial.get
          val snapshot = root.copy(
            goalRequested = goalRequested,
            normalizationRequest = normalizationRequest
          )
          val document = documentAt(Path.of(initialPath))
          def sourceCurrent: Boolean = capture.rootCurrent(root)
          val reservation = checkService.begin(
            snapshot,
            callback => subscribe(document, callback),
            () => sourceCurrent
          )
          if reservation.isEmpty then
            finish(
              BendExplicitCheckOutcome.Rejected(
                root.root,
                "A Bend check is already running"
              )
            )
            return
          try
            val captured = capture.capture(
              initialPath,
              selection,
              () =>
                indicator.isCanceled || project.isDisposed || !sourceCurrent,
              _.copy(
                goalRequested = goalRequested,
                normalizationRequest = normalizationRequest
              )
            )
            if captured.isEmpty then
              checkService.cancel(root.root)
              finish(
                BendExplicitCheckOutcome.Rejected(
                  root.root,
                  "Source snapshot became unavailable during capture"
                )
              )
              return
            val result = checkService.check(
              captured.get,
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
                    .Rejected(root.root, "Check result is no longer current")
                )
          catch
            case NonFatal(_) if indicator.isCanceled =>
              checkService.cancel(root.root)
            case NonFatal(error) =>
              checkService.cancel(root.root)
              val detail =
                Option(error.getMessage).getOrElse(error.getClass.getSimpleName)
              finish(
                BendExplicitCheckOutcome.Rejected(
                  root.root,
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
