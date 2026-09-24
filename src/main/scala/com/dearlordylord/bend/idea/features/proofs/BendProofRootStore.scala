package com.dearlordylord.bend.idea.features.proofs

import com.intellij.openapi.components.{
  PersistentStateComponent,
  State,
  Storage,
  StoragePathMacros
}
import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic
import java.nio.file.Path

trait BendProofRootListener:
  def rootsChanged(): Unit

object BendProofRootListener:
  val Topic: Topic[BendProofRootListener] = new Topic[BendProofRootListener](
    "Bend proof roots changed",
    classOf[BendProofRootListener]
  )

final class BendProofRootState:
  var paths: Array[String] = Array.empty

/** Project-local roots live in workspace.xml and survive IDE restarts. */
@State(
  name = "BendProofRoots",
  storages = Array(new Storage(StoragePathMacros.WORKSPACE_FILE))
)
final class BendProofRootStore(project: Project)
    extends PersistentStateComponent[BendProofRootState]:
  private var state = new BendProofRootState

  override def getState: BendProofRootState = synchronized {
    val copy = new BendProofRootState
    copy.paths = state.paths.clone()
    copy
  }

  override def loadState(next: BendProofRootState): Unit = synchronized {
    state = new BendProofRootState
    state.paths = Option(next.paths).getOrElse(Array.empty[String]).clone()
  }

  def selectedPaths: List[String] = synchronized { state.paths.toList }

  def select(path: String): Unit =
    val normalized = Path.of(path).toAbsolutePath.normalize().toString
    val changed = synchronized {
      val previous = state.paths.toList
      state.paths =
        (normalized :: state.paths.toList.filterNot(_ == normalized))
          .take(20)
          .toArray
      previous != state.paths.toList
    }
    if changed && !project.isDisposed then
      project.getMessageBus
        .syncPublisher(BendProofRootListener.Topic)
        .rootsChanged()
