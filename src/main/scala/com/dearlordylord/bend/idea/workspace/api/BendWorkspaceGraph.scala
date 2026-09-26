package com.dearlordylord.bend.idea.workspace.api

import com.dearlordylord.bend.idea.workspace.model.{
  BendLoadedGraph,
  BendSourceRecord
}
import com.dearlordylord.bend.idea.workspace.loading.BendGraphLoader

/** Read-only graph query used by source symbols and later checker snapshots. */
trait BendWorkspaceGraph:
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
