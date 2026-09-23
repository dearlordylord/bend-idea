package com.dearlordylord.bend.idea.adapters.intellij

import com.dearlordylord.bend.idea.workspace.api.BendWorkspaceGraph
import com.dearlordylord.bend.idea.workspace.model.{BendLoadedGraph, BendSourceRecord}
import com.dearlordylord.bend.idea.workspace.ports.BendSourceCatalog
import com.intellij.openapi.project.Project

final class BendWorkspaceGraphService(project: Project) extends BendWorkspaceGraph:
  override def load(root: BendSourceRecord, basePath: String,
      packageCache: String): BendLoadedGraph =
    BendWorkspaceGraph.load(root, basePath, packageCache,
      project.getService(classOf[BendSourceCatalog]))
