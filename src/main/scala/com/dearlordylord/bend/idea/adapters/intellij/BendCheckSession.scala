package com.dearlordylord.bend.idea.adapters.intellij

import com.dearlordylord.bend.idea.adapters.cli.BendCliCheckBackend
import com.dearlordylord.bend.idea.adapters.cli.BendExternalInputs
import com.dearlordylord.bend.idea.analysis.api.BendCheckService
import com.dearlordylord.bend.idea.analysis.checking.{BendPublicationFacts, BendPublicationPolicy}
import com.dearlordylord.bend.idea.analysis.model.BendCheckingStatus
import com.dearlordylord.bend.idea.analysis.model.*
import com.dearlordylord.bend.idea.workspace.model.BendSourceRecord
import com.dearlordylord.bend.idea.workspace.ports.BendSourceCatalog
import com.dearlordylord.bend.idea.model.FileId
import com.dearlordylord.bend.idea.toolchain.api.BendToolchainSettings
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.{DocumentEvent, DocumentListener}
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import scala.collection.mutable
import scala.jdk.CollectionConverters.*
import java.nio.file.Path

/** One explicit worker per project; document changes invalidate root results. */
final class BendCheckSession(project: Project) extends BendCheckService, Disposable:
  private val backend = new BendCliCheckBackend
  private val generations = mutable.Map.empty[FileId, Long]
  private val results = mutable.Map.empty[FileId, BendCheckResult]
  private val captures = mutable.Map.empty[FileId, BendCheckSnapshot]
  private val pendingCaptures = mutable.Map.empty[FileId, BendCheckSnapshot]
  private val subscriptions = mutable.Map.empty[FileId, () => Unit]
  private val rootOrder = mutable.Queue.empty[FileId]
  private val maxRoots = 128
  private var active: Option[(FileId, Long, () => Boolean, Boolean)] = None
  private var started = false
  private var disposed = false

  EditorFactory.getInstance().getEventMulticaster.addDocumentListener(new DocumentListener:
    override def documentChanged(event: DocumentEvent): Unit =
      val file = FileDocumentManager.getInstance().getFile(event.getDocument)
      if file != null then
        val canonical = Option(file.getCanonicalPath)
        val identity = new FileId(canonical.getOrElse(file.getPath), canonical.isDefined)
        val roots = synchronized {
          (captures.toList ++ pendingCaptures.toList).collect { case (root, snapshot) if
            root != identity && (snapshot.graph.exists(_.files.exists(_.source.id == identity)) ||
              snapshot.siblingLaws.exists(_.id == identity)) => root
          }.distinct
        }
        roots.foreach(sourceChanged)
  , this)

  project.getMessageBus.connect(this).subscribe(VirtualFileManager.VFS_CHANGES,
    new BulkFileListener:
      override def after(events: java.util.List[? <: VFileEvent]): Unit =
        val changed = events.asScala.map(_.getPath).toSet
        val affected = synchronized {
          val roots = results.iterator.collect { case (root, result) if
              changed(result.key.executable) ||
              result.key.basePath.exists(changed) ||
              captures.get(root).flatMap(_.graph).exists(graph =>
                graph.files.exists(file => changed(file.source.path) ||
                  changed(file.source.id.value)) ||
                graph.edges.exists(edge => changed(edge.requestedPath))) ||
              captures.get(root).exists(snapshot =>
                Path.of(snapshot.path).getFileName.toString == "PROOF.bend" &&
                  changed(Path.of(snapshot.path).resolveSibling("LAWS.bend").toString)) => root }.toList
          roots.foreach { root =>
            generations(root) = generations.getOrElse(root, 0L) + 1
            results(root) = results(root).copy(fresh = false)
          }
          roots.nonEmpty
        }
        if affected then ApplicationManager.getApplication.invokeLater(() => {
          if !project.isDisposed then DaemonCodeAnalyzer.getInstance(project).restart()
        })
  )

  private def sourceChanged(root: FileId): Unit = synchronized {
    if !disposed then
      generations(root) = generations.getOrElse(root, 0L) + 1
      results.get(root).foreach(result => results(root) = result.copy(fresh = false))
      ApplicationManager.getApplication.invokeLater(() => {
        if !project.isDisposed then DaemonCodeAnalyzer.getInstance(project).restart()
      })
  }

  override def begin(snapshot: BendCheckSnapshot,
      subscribe: (() => Unit) => (() => Unit), isCurrent: () => Boolean,
      background: Boolean = false): Boolean = synchronized {
    if !background && active.exists(_._4) then
      val previous = active.get._1
      generations(previous) = generations.getOrElse(previous, 0L) + 1
      results.get(previous).foreach(result => results(previous) = result.copy(fresh = false))
      if !started then active = None
      else
        // Manual checks are launched from a background task. The old process
        // observes the changed generation and releases the one project slot.
        val deadline = System.nanoTime() + 5_000_000_000L
        while active.nonEmpty && !disposed && System.nanoTime() < deadline do wait(50)
    if disposed || active.nonEmpty then false
    else
      val root = snapshot.root
      if !subscriptions.contains(root) then
        if rootOrder.size >= maxRoots then
          val oldest = rootOrder.dequeue()
          subscriptions.remove(oldest).foreach(_())
          results.remove(oldest)
          captures.remove(oldest)
          generations.remove(oldest)
        rootOrder.enqueue(root)
      subscriptions.remove(root).foreach(_())
      subscriptions(root) = subscribe(() => sourceChanged(root))
      val next = generations.getOrElse(root, 0L) + 1
      generations(root) = next
      results.get(root).foreach(result => results(root) = result.copy(fresh = false))
      active = Some((root, next, isCurrent, background))
      started = false
      true
  }

  override def cancel(root: FileId): Unit = synchronized {
    active.filter(_._1 == root).foreach { _ =>
      generations(root) = generations.getOrElse(root, 0L) + 1
      results.get(root).foreach(result => results(root) = result.copy(fresh = false))
      if !started then active = None
    }
  }

  override def configurationChanged(): Unit = synchronized {
    if !disposed then
      results.keys.toList.foreach { root =>
        generations(root) = generations.getOrElse(root, 0L) + 1
        results(root) = results(root).copy(fresh = false)
      }
      active.foreach { case (root, _, _, _) =>
        generations(root) = generations.getOrElse(root, 0L) + 1
      }
      if active.nonEmpty && !started then active = None
  }

  override def check(snapshot: BendCheckSnapshot, canceled: () => Boolean): Option[BendCheckResult] =
    val root = snapshot.root
    val reservation = synchronized {
      val current = if started then None else active.filter(_._1 == root)
      if current.nonEmpty then started = true
      current
    }
    reservation match
      case None => None
      case Some((_, generation, isCurrent, _)) =>
        synchronized { pendingCaptures(root) = snapshot }
        try
          if canceled() || !isCurrent() || synchronized { disposed ||
              generations.get(root) != Some(generation) } then None
          else
            val checked = backend.check(snapshot, () => canceled() || synchronized { disposed ||
              generations.get(root) != Some(generation) })
            val inputsCurrent = graphCurrent(snapshot)
            val sourceCurrent = isCurrent()
            val workerCanceled = canceled()
            val selection = ApplicationManager.getApplication
              .getService(classOf[BendToolchainSettings]).selection
            val externalCurrent = checked.key.externalStamp == BendExternalInputs.stamp(
              checked.key.executable, checked.key.basePath)
            val published = synchronized {
              val facts = BendPublicationFacts(generation, generations.get(root),
                sourceCurrent, inputsCurrent,
                selection.configurationRevision == snapshot.toolchain.configurationRevision &&
                  selection.executable == snapshot.toolchain.executable,
                externalCurrent, workerCanceled, disposed)
              if !BendPublicationPolicy.mayPublish(facts) then None
              else
                results(root) = checked
                captures(root) = snapshot
                Some(checked)
            }
            published
        finally synchronized {
          pendingCaptures.remove(root)
          if active.exists(a => a._1 == root && a._2 == generation) then
            active = None
            started = false
            notifyAll()
        }

  override def result(root: FileId): Option[BendCheckResult] =
    val captured = synchronized { captures.get(root) }
    val graphChanged = if captured.exists(s => !graphCurrent(s)) then synchronized {
      results.get(root) match
        case Some(r) if r.fresh && captured.exists(snapshot =>
            captures.get(root).exists(_ eq snapshot)) =>
          results(root) = r.copy(fresh = false)
          generations(root) = generations.getOrElse(root, 0L) + 1
          true
        case _ => false
    }
    else false
    val inspected = synchronized { results.get(root) }
    val externalCurrent = inspected.forall(result => result.key.externalStamp ==
      BendExternalInputs.stamp(result.key.executable, result.key.basePath))
    val (value, changed) = synchronized {
      results.get(root) match
        case Some(result) if result.fresh && inspected.contains(result) && !externalCurrent =>
          val stale = result.copy(fresh = false)
          results(root) = stale
          (Some(stale), true)
        case current => (current, false)
    }
    if (changed || graphChanged) && !project.isDisposed then
      DaemonCodeAnalyzer.getInstance(project).restart()
    value

  override def resultsFor(source: FileId): List[BendCheckResult] =
    // External annotators run under an IDE read action. Do not traverse disk
    // here; VFS and document events stale root-owned results.
    synchronized { results.values.toList }.filter { checked =>
      checked.key.root == source || checked.sources.exists(_.id == source)
    }

  private def graphCurrent(snapshot: BendCheckSnapshot): Boolean =
    snapshot.graph match
      case None => true
      case Some(graph) =>
        val catalog = project.getService(classOf[BendSourceCatalog])
        def read(path: String): Option[BendSourceRecord] = catalog.source(path)
        val dependencies = graph.files.filterNot(_.source.id == snapshot.root).forall { file =>
          read(file.source.path).exists(current =>
            current.id == file.source.id && current.revision == file.source.revision &&
              current.text == file.source.text)
        }
        val observed = graph.edges.filter(_.target.isEmpty).forall(edge =>
          read(edge.requestedPath).isEmpty)
        val laws = if Path.of(snapshot.path).getFileName.toString == "PROOF.bend" then
          val current = read(Path.of(snapshot.path).resolveSibling("LAWS.bend").toString)
          (snapshot.siblingLaws, current) match
            case (None, None) => true
            case (Some(before), Some(after)) =>
              before.id == after.id && before.revision == after.revision && before.text == after.text
            case _ => false
        else true
        dependencies && observed && laws

  override def busy: Boolean = synchronized { active.nonEmpty }

  override def status(root: FileId): BendCheckingStatus =
    val enabled = ApplicationManager.getApplication
      .getService(classOf[BendToolchainSettings]).selection.diagnosticsEnabled
    val (running, checked) = synchronized {
      (active.exists(_._1 == root), results.get(root))
    }
    BendCheckingStatus.of(enabled, running, checked)

  override def dispose(): Unit = synchronized {
    disposed = true
    active.foreach { case (root, _, _, _) =>
      generations(root) = generations.getOrElse(root, 0L) + 1
    }
    active = None
    notifyAll()
    subscriptions.values.foreach(_())
    subscriptions.clear()
    pendingCaptures.clear()
    rootOrder.clear()
    results.clear()
    captures.clear()
    generations.clear()
  }
