package com.dearlordylord.bend.idea.workspace.api

import com.dearlordylord.bend.idea.workspace.model.{
  BendLoadedGraph,
  BendSourceRecord
}
import com.dearlordylord.bend.idea.workspace.loading.BendGraphLoader

/** Read-only graph query used by source symbols and later checker snapshots. */
trait BendWorkspaceGraph:
  /** Validate observed loading inputs without rebuilding the graph. Adapters
    * without this capability conservatively decline cached source links.
    */
  def current(
      graph: BendLoadedGraph,
      packageCache: String,
      canceled: () => Boolean = () => false
  ): Boolean = false

  def load(
      root: BendSourceRecord,
      basePath: String,
      packageCache: String,
      canceled: () => Boolean = () => false
  ): BendLoadedGraph

  /** Capture the sibling required by the compiler's PROOF filename guard. */
  def siblingLaws(proofPath: String): Option[BendSourceRecord]

/** The adapter supplies a catalog; the workspace subsystem owns all loading
  * policy.
  */
object BendWorkspaceGraph:
  /** Shared observation validation for checks and cached source inventories.
    * The catalog retains current-buffer precedence and owns all external I/O.
    */
  def current(
      graph: BendLoadedGraph,
      packageCache: String,
      catalog: BendSourceCatalog,
      canceled: () => Boolean = () => false
  ): Boolean =
    val observedPaths = (graph.files.map(_.source.path) ++ graph.edges.map(
      _.requestedPath
    )).distinct
    val observations = observedPaths.iterator
      .takeWhile(_ => !canceled())
      .map(path => path -> catalog.source(path))
      .toMap
    !canceled() && graph.files.forall(file =>
      observations
        .get(file.source.path)
        .flatten
        .exists(record =>
          record.id == file.source.id && record.revision == file.source.revision && record.text == file.source.text
        )
    ) && graph.edges.forall(edge =>
      observations.get(edge.requestedPath).flatten.map(_.id) == edge.target
    ) && (graph.packageCacheIdentity.isEmpty || catalog.canonicalPath(
      packageCache
    ) == graph.packageCacheIdentity) &&
    graph.cachedPackages.forall(entry =>
      !canceled() && catalog.cachedPackageHash(
        graph.packageCacheIdentity,
        entry.name
      ) == entry.hash
    ) && !canceled()

  def load(
      root: BendSourceRecord,
      basePath: String,
      packageCache: String,
      catalog: BendSourceCatalog,
      canceled: () => Boolean = () => false
  ): BendLoadedGraph =
    BendGraphLoader.load(
      root,
      BendGraphLoader.Config(basePath, packageCache),
      catalog,
      canceled = canceled
    )
