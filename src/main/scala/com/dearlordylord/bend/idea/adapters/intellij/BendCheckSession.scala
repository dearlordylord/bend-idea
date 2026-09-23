package com.dearlordylord.bend.idea.adapters.intellij

import com.dearlordylord.bend.idea.adapters.cli.BendCliCheckBackend
import com.dearlordylord.bend.idea.adapters.cli.BendExternalInputs
import com.dearlordylord.bend.idea.analysis.api.BendCheckService
import com.dearlordylord.bend.idea.analysis.model.*
import com.dearlordylord.bend.idea.model.FileId
import com.dearlordylord.bend.idea.toolchain.api.BendToolchainSettings
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import scala.collection.mutable
import scala.jdk.CollectionConverters.*

/** One explicit worker per project; document changes invalidate root results. */
final class BendCheckSession(project: Project) extends BendCheckService, Disposable:
  private val backend = new BendCliCheckBackend
  private val generations = mutable.Map.empty[FileId, Long]
  private val results = mutable.Map.empty[FileId, BendCheckResult]
  private val subscriptions = mutable.Map.empty[FileId, () => Unit]
  private val rootOrder = mutable.Queue.empty[FileId]
  private val maxRoots = 128
  private var active: Option[(FileId, Long, () => Boolean)] = None
  private var started = false
  private var disposed = false

  project.getMessageBus.connect(this).subscribe(VirtualFileManager.VFS_CHANGES,
    new BulkFileListener:
      override def after(events: java.util.List[? <: VFileEvent]): Unit =
        val changed = events.asScala.map(_.getPath).toSet
        val affected = synchronized {
          val roots = results.iterator.collect { case (root, result) if
              changed(result.key.executable) ||
              result.key.basePath.exists(changed) => root }.toList
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
  }

  override def begin(snapshot: BendCheckSnapshot,
      subscribe: (() => Unit) => (() => Unit), isCurrent: () => Boolean): Boolean = synchronized {
    if disposed || active.nonEmpty then false
    else
      val root = snapshot.root
      if !subscriptions.contains(root) then
        if rootOrder.size >= maxRoots then
          val oldest = rootOrder.dequeue()
          subscriptions.remove(oldest).foreach(_())
          results.remove(oldest)
          generations.remove(oldest)
        rootOrder.enqueue(root)
      subscriptions.remove(root).foreach(_())
      subscriptions(root) = subscribe(() => sourceChanged(root))
      val next = generations.getOrElse(root, 0L) + 1
      generations(root) = next
      results.get(root).foreach(result => results(root) = result.copy(fresh = false))
      active = Some((root, next, isCurrent))
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
      active.foreach { case (root, _, _) =>
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
      case Some((_, generation, isCurrent)) =>
        try
          if canceled() || !isCurrent() || synchronized { disposed ||
              generations.get(root) != Some(generation) } then None
          else
            val checked = backend.check(snapshot, () => canceled() || synchronized { disposed ||
              generations.get(root) != Some(generation) })
            synchronized {
              val selection = ApplicationManager.getApplication.getService(classOf[BendToolchainSettings]).selection
              if disposed || canceled() || !isCurrent() ||
                  generations.get(root) != Some(generation) ||
                  selection.configurationRevision != snapshot.toolchain.configurationRevision ||
                  selection.executable != snapshot.toolchain.executable ||
                  checked.key.externalStamp != BendExternalInputs.stamp(checked.key.executable,
                    checked.key.basePath) then None
              else
                results(root) = checked
                Some(checked)
            }
        finally synchronized {
          if active.exists(a => a._1 == root && a._2 == generation) then
            active = None
            started = false
        }

  override def result(root: FileId): Option[BendCheckResult] =
    val (value, changed) = synchronized {
      results.get(root) match
        case Some(result) if result.fresh && result.key.externalStamp != BendExternalInputs.stamp(
            result.key.executable, result.key.basePath) =>
          val stale = result.copy(fresh = false)
          results(root) = stale
          (Some(stale), true)
        case current => (current, false)
    }
    if changed && !project.isDisposed then DaemonCodeAnalyzer.getInstance(project).restart()
    value

  override def busy: Boolean = synchronized { active.nonEmpty }

  override def dispose(): Unit = synchronized {
    disposed = true
    subscriptions.values.foreach(_())
    subscriptions.clear()
    rootOrder.clear()
    results.clear()
    generations.clear()
  }
