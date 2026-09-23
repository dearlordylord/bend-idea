package com.dearlordylord.bend.idea.adapters.intellij

import com.dearlordylord.bend.idea.analysis.api.{BendCheckService, BendGraphSnapshot}
import com.dearlordylord.bend.idea.analysis.model.BendCheckSnapshot
import com.dearlordylord.bend.idea.model.FileId
import com.dearlordylord.bend.idea.toolchain.api.BendToolchainSettings
import com.dearlordylord.bend.idea.workspace.api.{BendImportLines, BendWorkspaceGraph}
import com.dearlordylord.bend.idea.workspace.model.BendSourceRecord
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.{DocumentEvent, DocumentListener}
import com.intellij.openapi.fileEditor.{FileDocumentManager, FileEditorManager, FileEditorManagerListener}
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.{VirtualFile, VirtualFileManager}
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import java.util.concurrent.{Executors, ScheduledFuture, TimeUnit}
import scala.collection.mutable
import scala.jdk.CollectionConverters.*

/** Project-owned debounce and worker lifetime. Captures and processes run off the UI thread. */
final class BendBackgroundChecking(project: Project) extends Disposable:
  private val executor = Executors.newSingleThreadScheduledExecutor((r: Runnable) => {
    val thread = new Thread(r, "bend-background-check")
    thread.setDaemon(true)
    thread
  })
  private val queued = mutable.Map.empty[FileId, ScheduledFuture[?]]
  private val tickets = mutable.Map.empty[FileId, Long]
  private val knownRoots = mutable.LinkedHashMap.empty[FileId, VirtualFile]
  private val runningRoots = mutable.Set.empty[FileId]
  private var disposed = false
  private val quietMillis = 500L
  executor.scheduleWithFixedDelay(new Runnable:
    override def run(): Unit =
      if !disposed && !project.isDisposed then
        synchronized { knownRoots.toList }.foreach { case (root, file) =>
          val stale = service.result(root).exists(!_.fresh)
          val pending = synchronized { queued.get(root).exists(!_.isDone) }
          if stale && !pending && !service.busy then schedule(file)
        }
  , 2000L, 2000L, TimeUnit.MILLISECONDS)

  private def service: BendCheckService = project.getService(classOf[BendCheckService])
  private def settings: BendToolchainSettings =
    ApplicationManager.getApplication.getService(classOf[BendToolchainSettings])
  private def id(file: VirtualFile): FileId =
    val canonical = Option(file.getCanonicalPath)
    new FileId(canonical.getOrElse(file.getPath), canonical.isDefined)
  private def openRoots: List[VirtualFile] =
    ReadAction.compute(() => FileEditorManager.getInstance(project).getOpenFiles.toList.filter(f =>
      f.isValid && f.getName.endsWith(".bend")))

  EditorFactory.getInstance().getEventMulticaster.addDocumentListener(new DocumentListener:
    override def documentChanged(event: DocumentEvent): Unit =
      val source = FileDocumentManager.getInstance().getFile(event.getDocument)
      if source != null && source.getName.endsWith(".bend") then
        val sourceId = id(source)
        val affected = service.resultsFor(sourceId).map(_.key.root).toSet ++
          synchronized { runningRoots.toSet } + sourceId
        synchronized { knownRoots.toList }.collect {
          case (root, file) if affected(root) => file
        }.foreach(schedule)
        if !synchronized { knownRoots.contains(sourceId) } then schedule(source)
  , this)

  project.getMessageBus.connect(this).subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER,
    new FileEditorManagerListener:
      override def fileOpened(source: FileEditorManager, file: VirtualFile): Unit =
        if file.getName.endsWith(".bend") then schedule(file)
  )

  project.getMessageBus.connect(this).subscribe(VirtualFileManager.VFS_CHANGES,
    new BulkFileListener:
      override def after(events: java.util.List[? <: VFileEvent]): Unit =
        val selection = settings.selection
        if events.asScala.exists { event =>
          val path = event.getPath
          path.endsWith(".bend") || path == selection.executable ||
            path == selection.baseSource || path.startsWith(selection.packageCache.stripSuffix("/") + "/")
        } then
          // A newly created missing import is absent from the old graph's files.
          synchronized { knownRoots.values.toList }.foreach(schedule)
  )

  def configurationChanged(): Unit =
    if !settings.selection.diagnosticsEnabled then
      val roots = synchronized {
        queued.values.foreach(_.cancel(false))
        queued.clear()
        knownRoots.keys.toList
      }
      roots.foreach(service.cancel)
    else
      openRoots.foreach(schedule)
      synchronized { knownRoots.values.toList }.foreach(schedule)

  private def schedule(file: VirtualFile): Unit =
    if disposed || project.isDisposed || !settings.selection.diagnosticsEnabled then return
    val root = ReadAction.compute(() => id(file))
    val evicted = synchronized {
      var removed: Option[FileId] = None
      if !knownRoots.contains(root) && knownRoots.size >= 128 then
        val oldest = knownRoots.head._1
        knownRoots.remove(oldest)
        queued.remove(oldest).foreach(_.cancel(false))
        tickets.remove(oldest)
        removed = Some(oldest)
      knownRoots(root) = file
      val ticket = tickets.getOrElse(root, 0L) + 1
      tickets(root) = ticket
      queued.remove(root).foreach(_.cancel(false))
      queued(root) = executor.schedule(new Runnable:
        override def run(): Unit = check(file, root, ticket)
      , quietMillis, TimeUnit.MILLISECONDS)
      removed
    }
    (evicted.toList :+ root).foreach(service.cancel)

  private def retry(file: VirtualFile, root: FileId, ticket: Long, attempt: Int): Unit =
    synchronized {
      if !disposed && tickets.get(root).contains(ticket) then
        queued(root) = executor.schedule(new Runnable:
          override def run(): Unit = check(file, root, ticket, attempt)
        , quietMillis, TimeUnit.MILLISECONDS)
    }

  private def check(file: VirtualFile, root: FileId, ticket: Long,
      attempt: Int = 0): Unit =
    if disposed || project.isDisposed || !settings.selection.diagnosticsEnabled ||
        synchronized { tickets.get(root) != Some(ticket) } then return
    val selection = settings.selection
    val captured = ReadAction.compute(() => {
      if !file.isValid then None
      else Option(FileDocumentManager.getInstance().getDocument(file)).map { document =>
        (document, BendCheckSnapshot(root, file.getPath, document.getText,
          document.getModificationStamp, selection), file.getName == "PROOF.bend")
      }
    })
    if captured.isEmpty then return
    val (document, initial, proof) = captured.get
    def current: Boolean = !disposed && !project.isDisposed &&
      ReadAction.compute(() => file.isValid &&
        document.getModificationStamp == initial.sourceRevision) &&
      settings.selection.configurationRevision == selection.configurationRevision &&
      synchronized { tickets.get(root).contains(ticket) }
    if !current then return
    synchronized { runningRoots += root }
    if !service.begin(initial, callback => {
      val listener = new DocumentListener:
        override def documentChanged(event: DocumentEvent): Unit = callback()
      document.addDocumentListener(listener)
      () => document.removeDocumentListener(listener)
    }, () => current, background = true) then
      synchronized { runningRoots -= root }
      // An explicit check has the slot. Retry after it finishes, without
      // occupying another worker or delaying that user action.
      retry(file, root, ticket, attempt)
      return
    try
      val source = BendSourceRecord(root, initial.path, initial.text, initial.sourceRevision,
        BendImportLines.parse(initial.text))
      val graph = project.getService(classOf[BendWorkspaceGraph]).load(source,
        selection.baseSource, selection.packageCache, () => !current)
      val laws = if proof then
        project.getService(classOf[BendWorkspaceGraph]).siblingLaws(initial.path)
      else None
      val snapshot = BendGraphSnapshot.attach(initial, graph, laws)
      val checked = service.check(snapshot, () => !current)
      // A same-ticket result can be rejected when the executable or Base is
      // replaced without a VFS event during the first check. Capture again
      // with fresh external stamps; the bound prevents a persistent race loop.
      if checked.isEmpty && current && attempt < 2 then
        retry(file, root, ticket, attempt + 1)
      if current then ApplicationManager.getApplication.invokeLater(() => {
        if !project.isDisposed then DaemonCodeAnalyzer.getInstance(project).restart()
      })
    finally synchronized { runningRoots -= root }

  override def dispose(): Unit =
    val roots = synchronized {
      disposed = true
      queued.values.foreach(_.cancel(false))
      queued.clear()
      val remembered = tickets.keys.toList
      tickets.clear()
      runningRoots.clear()
      knownRoots.clear()
      executor.shutdownNow()
      remembered
    }
    roots.foreach(service.cancel)
