package com.dearlordylord.bend.idea.workspace.api

import com.dearlordylord.bend.idea.workspace.model.{BendLoadedGraph, BendSourceRecord}
import com.dearlordylord.bend.idea.workspace.loading.BendGraphLoader
import com.dearlordylord.bend.idea.workspace.ports.BendSourceCatalog

/** Read-only graph query used by source symbols and later checker snapshots. */
trait BendWorkspaceGraph:
  def load(root: BendSourceRecord, basePath: String, packageCache: String): BendLoadedGraph

/** The adapter supplies a catalog; the workspace subsystem owns all loading policy. */
object BendWorkspaceGraph:
  def load(root: BendSourceRecord, basePath: String, packageCache: String,
      catalog: BendSourceCatalog): BendLoadedGraph =
    BendGraphLoader.load(root, BendGraphLoader.Config(basePath, packageCache), catalog)
