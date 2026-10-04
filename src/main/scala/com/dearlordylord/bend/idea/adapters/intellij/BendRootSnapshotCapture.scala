package com.dearlordylord.bend.idea.adapters.intellij

import com.dearlordylord.bend.idea.adapters.cli.BendExternalInputs
import com.dearlordylord.bend.idea.analysis.api.BendGraphSnapshot
import com.dearlordylord.bend.idea.analysis.model.BendCheckSnapshot
import com.dearlordylord.bend.idea.toolchain.api.{
  BendToolchainSelection,
  BendToolchainSettings
}
import com.dearlordylord.bend.idea.workspace.api.{
  BendSourceCatalog,
  BendWorkspaceGraph
}
import com.dearlordylord.bend.idea.workspace.model.BendSourceRecord
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import java.nio.file.Path

/** The IntelliJ boundary for one consistent, current root and loading graph.
  * The selected path stays separate from each catalog record's canonical ID.
  */
private[intellij] final class BendRootSnapshotCapture(
    project: Project,
    catalog: BendSourceCatalog,
    graphLoader: BendWorkspaceGraph
):
  def this(project: Project) = this(
    project,
    project.getService(classOf[BendSourceCatalog]),
    project.getService(classOf[BendWorkspaceGraph])
  )
  private def selection = ApplicationManager.getApplication
    .getService(classOf[BendToolchainSettings])
    .selection

  def initial(
      path: String,
      selected: BendToolchainSelection
  ): Option[BendCheckSnapshot] =
    catalog.source(path).map(root => initialFrom(path, root, selected))

  private def initialFrom(
      path: String,
      root: BendSourceRecord,
      selected: BendToolchainSelection
  ): BendCheckSnapshot =
    BendCheckSnapshot(
      root.id,
      root.path,
      root.text,
      root.revision,
      selected,
      selectedPath = path,
      externalInputStamp = BendExternalInputs.stamp(
        selected.executable,
        Some(selected.baseSource)
      )
    )

  def capture(
      path: String,
      selected: BendToolchainSelection,
      canceled: () => Boolean,
      decorate: BendCheckSnapshot => BendCheckSnapshot = identity
  ): Option[BendCheckSnapshot] =
    (0 until 2).iterator
      .map { _ =>
        if canceled() then None
        else
          catalog.source(path).flatMap { root =>
            val initial = decorate(initialFrom(path, root, selected))
            val graph = graphLoader.load(
              root,
              selected.baseSource,
              selected.packageCache,
              canceled
            )
            val laws = siblingPath(root.path).flatMap(catalog.source)
            val snapshot = BendGraphSnapshot.attach(initial, graph, laws)
            Option.when(!canceled() && current(snapshot))(snapshot)
          }
      }
      .collectFirst { case Some(snapshot) => snapshot }

  def current(snapshot: BendCheckSnapshot): Boolean =
    if !rootCurrent(snapshot) then false
    else
      val now = selection
      val sources = snapshot.graph.toList.flatMap(_.files.map(_.source))
      val present = sources.forall(sameSource)
      val observedEdges = snapshot.graph.forall(
        _.edges
          .forall(edge =>
            catalog.source(edge.requestedPath).map(_.id) == edge.target
          )
      )
      val packageInputs = snapshot.graph.forall(graph =>
        (graph.packageCacheIdentity.isEmpty || catalog.canonicalPath(
          snapshot.toolchain.packageCache
        ) == graph.packageCacheIdentity) &&
          graph.cachedPackages.forall(entry =>
            catalog.cachedPackageHash(
              graph.packageCacheIdentity,
              entry.name
            ) == entry.hash
          )
      )
      val laws =
        snapshot.graph.isEmpty || siblingPath(snapshot.path).forall(path =>
          (snapshot.siblingLaws, catalog.source(path)) match
            case (None, None)                => true
            case (Some(before), Some(after)) => sameRecord(before, after)
            case _                           => false
        )
      val external = snapshot.externalInputStamp == BendExternalInputs.stamp(
        now.executable,
        Some(now.baseSource)
      )
      present && observedEdges && packageInputs && laws && external &&
      selection == now

  /** Root and configuration currency for worker cancellation. External and
    * graph changes are checked at publication so a background retry remains
    * eligible after a compiler replacement.
    */
  def rootCurrent(snapshot: BendCheckSnapshot): Boolean =
    if project.isDisposed then false
    else
      val now = selection
      now.configurationRevision == snapshot.toolchain.configurationRevision &&
      now.executable == snapshot.toolchain.executable &&
      now.baseSource == snapshot.toolchain.baseSource &&
      now.packageCache == snapshot.toolchain.packageCache &&
      catalog
        .source(snapshot.path)
        .exists(record =>
          record.id == snapshot.root && record.revision == snapshot.sourceRevision &&
            record.text == snapshot.text
        )

  private def sameSource(before: BendSourceRecord): Boolean =
    catalog.source(before.path).exists(sameRecord(before, _))

  private def sameRecord(a: BendSourceRecord, b: BendSourceRecord): Boolean =
    a.id == b.id && a.revision == b.revision && a.text == b.text

  private def siblingPath(path: String): Option[String] =
    Option(Path.of(path).getFileName)
      .filter(_.toString == "PROOF.bend")
      .map(_ => Path.of(path).resolveSibling("LAWS.bend").toString)
