package com.dearlordylord.bend.idea.adapters.intellij

import com.dearlordylord.bend.idea.analysis.api.{
  BendBackgroundCheckControl,
  BendBackgroundCheckTicket,
  BendCheckService,
  BendGraphSnapshot
}
import com.dearlordylord.bend.idea.analysis.checking.BendCheckAction
import com.dearlordylord.bend.idea.analysis.checking.BendCheckAction.*
import com.dearlordylord.bend.idea.analysis.model.BendCheckSnapshot
import com.dearlordylord.bend.idea.model.FileId
import com.dearlordylord.bend.idea.toolchain.api.BendToolchainSettings
import com.dearlordylord.bend.idea.workspace.api.{
  BendImportLines,
  BendWorkspaceGraph
}
import com.dearlordylord.bend.idea.workspace.model.BendSourceRecord
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.{ApplicationManager, ReadAction}
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.{DocumentEvent, DocumentListener}
import com.intellij.openapi.fileEditor.{
  FileDocumentManager,
  FileEditorManager,
  FileEditorManagerListener
}
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.{VirtualFile, VirtualFileManager}
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import java.util.concurrent.{Executors, ScheduledFuture, TimeUnit}
import scala.collection.mutable
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

/** IntelliJ timer, source capture, and worker adapter for the immutable check
  * policy.
  */
final class BendBackgroundChecking(project: Project) extends Disposable:
  private val executor =
    Executors.newSingleThreadScheduledExecutor((r: Runnable) => {
      val thread = new Thread(r, "bend-background-check")
      thread.setDaemon(true)
      thread
    })
  // These maps retain platform handles only. Tokens, queue state, retries and eviction live in policy.
  private val timers = mutable.Map.empty[FileId, (Long, ScheduledFuture[?])]
  private val rootFiles = mutable.Map.empty[FileId, VirtualFile]
  @volatile private var disposed = false

  executor.scheduleWithFixedDelay(
    new Runnable:
      override def run(): Unit =
        if !disposed && !project.isDisposed then
          synchronized { rootFiles.keys.toList }.foreach { root =>
            if service.result(root).exists(!_.fresh) then
              control.backgroundStaleObserved(root)
          }
    ,
    2000L,
    2000L,
    TimeUnit.MILLISECONDS
  )

  private def service: BendCheckService =
    project.getService(classOf[BendCheckService])
  private def control: BendBackgroundCheckControl = service
    .asInstanceOf[BendBackgroundCheckControl]
  private def settings: BendToolchainSettings =
    ApplicationManager.getApplication.getService(classOf[BendToolchainSettings])
  private def id(file: VirtualFile): FileId =
    val canonical = Option(file.getCanonicalPath)
    new FileId(canonical.getOrElse(file.getPath), canonical.isDefined)
  private def openRoots: List[VirtualFile] =
    ReadAction.compute(() =>
      FileEditorManager
        .getInstance(project)
        .getOpenFiles
        .toList
        .filter(f => f.isValid && f.getName.endsWith(".bend"))
    )

  private def request(file: VirtualFile): Unit =
    if disposed || project.isDisposed || !settings.selection.diagnosticsEnabled ||
      !file.isValid || !file.getName.endsWith(".bend")
    then return
    val root = ReadAction.compute(() => id(file))
    synchronized { rootFiles(root) = file }
    control.requestBackground(root)

  EditorFactory
    .getInstance()
    .getEventMulticaster
    .addDocumentListener(
      new DocumentListener:
        override def documentChanged(event: DocumentEvent): Unit =
          val source =
            FileDocumentManager.getInstance().getFile(event.getDocument)
          if source != null && source.getName.endsWith(".bend") then
            val sourceId = id(source)
            val affected = control.backgroundAffectedRoots(sourceId) + sourceId
            val candidates = synchronized {
              affected.flatMap(rootFiles.get).toList
            }
            (candidates :+ source).distinct.foreach(request)
      ,
      this
    )

  project.getMessageBus
    .connect(this)
    .subscribe(
      FileEditorManagerListener.FILE_EDITOR_MANAGER,
      new FileEditorManagerListener:
        override def fileOpened(
            source: FileEditorManager,
            file: VirtualFile
        ): Unit = request(file)
    )

  project.getMessageBus
    .connect(this)
    .subscribe(
      VirtualFileManager.VFS_CHANGES,
      new BulkFileListener:
        override def after(events: java.util.List[? <: VFileEvent]): Unit =
          val selection = settings.selection
          if events.asScala.exists { event =>
              val path = event.getPath
              path.endsWith(".bend") || path == selection.executable ||
              path == selection.baseSource || path
                .startsWith(selection.packageCache.stripSuffix("/") + "/")
            }
          then synchronized { rootFiles.values.toList }.foreach(request)
    )

  def configurationChanged(): Unit =
    val enabled = settings.selection.diagnosticsEnabled
    control.backgroundConfigurationChanged(enabled)
    if enabled then
      val known = synchronized { rootFiles.values.toList }
      (openRoots ++ known).distinct.foreach(request)

  private[intellij] def applyPolicyAction(action: BendCheckAction): Unit =
    action match
      case ScheduleBackground(root, token, delayMillis) =>
        val file = synchronized { rootFiles.get(root) }
        file.foreach { source =>
          if !disposed && !project.isDisposed then
            synchronized {
              timers.remove(root).foreach(_._2.cancel(false))
              val future = executor.schedule(
                new Runnable:
                  override def run(): Unit = runTimer(source, root, token)
                ,
                delayMillis,
                TimeUnit.MILLISECONDS
              )
              timers(root) = token -> future
            }
        }
      case CancelBackgroundTimer(root, token) =>
        synchronized {
          timers.get(root).filter(_._1 == token).foreach { case (_, future) =>
            future.cancel(false)
            val _ = timers.remove(root)
          }
        }
      case DropBackgroundRoot(root) =>
        synchronized {
          timers.remove(root).foreach(_._2.cancel(false))
          val _ = rootFiles.remove(root)
        }
      case _ => ()

  private def runTimer(file: VirtualFile, root: FileId, token: Long): Unit =
    synchronized {
      timers
        .get(root)
        .filter(_._1 == token)
        .foreach(_ => {
          val _ = timers.remove(root)
        })
    }
    control
      .backgroundTimerFired(root, token)
      .foreach(ticket => check(file, ticket))

  private def check(
      file: VirtualFile,
      ticket: BendBackgroundCheckTicket
  ): Unit =
    if disposed || project.isDisposed || !control.backgroundCurrent(ticket) then
      return
    val selection = settings.selection
    if !selection.diagnosticsEnabled then return
    val captured = ReadAction.compute(() => {
      if !file.isValid then None
      else
        Option(FileDocumentManager.getInstance().getDocument(file)).map {
          document =>
            (
              document,
              BendCheckSnapshot(
                ticket.root,
                file.getPath,
                document.getText,
                document.getModificationStamp,
                selection
              ),
              file.getName == "PROOF.bend"
            )
        }
    })
    if captured.isEmpty then
      control.backgroundAttemptFailed(ticket)
      return
    val (document, initial, proof) = captured.get
    def current: Boolean = !disposed && !project.isDisposed &&
      control.backgroundCurrent(ticket) &&
      ReadAction.compute(() =>
        file.isValid &&
          document.getModificationStamp == initial.sourceRevision
      ) &&
      settings.selection.configurationRevision == selection.configurationRevision &&
      settings.selection.executable == selection.executable
    if !current then
      if control.backgroundCurrent(ticket) then
        control.backgroundAttemptFailed(ticket)
      return

    val reservation = control.beginBackground(
      ticket,
      initial,
      callback => {
        val listener = new DocumentListener:
          override def documentChanged(event: DocumentEvent): Unit = callback()
        document.addDocumentListener(listener)
        () => document.removeDocumentListener(listener)
      },
      () => current
    )
    if reservation.isEmpty then return
    var enteredCheck = false
    try
      val source = BendSourceRecord(
        ticket.root,
        initial.path,
        initial.text,
        initial.sourceRevision,
        BendImportLines.parse(initial.text)
      )
      val graph = project
        .getService(classOf[BendWorkspaceGraph])
        .load(
          source,
          selection.baseSource,
          selection.packageCache,
          () => !current
        )
      val laws = if proof then
        project
          .getService(classOf[BendWorkspaceGraph])
          .siblingLaws(initial.path)
      else None
      val snapshot = BendGraphSnapshot.attach(initial, graph, laws)
      enteredCheck = true
      val checked = service.check(snapshot, reservation.get, () => !current)
      if checked.isEmpty && current then control.backgroundAttemptFailed(ticket)
    catch case NonFatal(_) => control.backgroundAttemptFailed(ticket)
    finally
      if !enteredCheck && control.backgroundCurrent(ticket) then
        control.backgroundAttemptFailed(ticket)

  override def dispose(): Unit =
    val shouldNotify = synchronized {
      if disposed then false
      else
        disposed = true
        timers.values.foreach(_._2.cancel(false))
        timers.clear()
        rootFiles.clear()
        executor.shutdownNow()
        true
    }
    if shouldNotify && !project.isDisposed then
      control.backgroundSchedulerDisposed()
