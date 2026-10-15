package com.dearlordylord.bend.idea.adapters.intellij

import com.dearlordylord.bend.idea.workspace.api.{
  BendSourceCatalog,
  BendWorkspaceGraph
}
import com.dearlordylord.bend.idea.workspace.model.{
  BendLoadedGraph,
  BendSourceRecord
}
import com.intellij.openapi.project.Project
import java.nio.file.Path

final class BendWorkspaceGraphService(project: Project)
    extends BendWorkspaceGraph:
  override def current(
      graph: BendLoadedGraph,
      packageCache: String,
      canceled: () => Boolean
  ): Boolean = BendWorkspaceGraph.current(
    graph,
    packageCache,
    project.getService(classOf[BendSourceCatalog]),
    canceled
  )

  override def contentsCurrent(
      graph: BendLoadedGraph,
      packageCache: String,
      canceled: () => Boolean
  ): Boolean = BendWorkspaceGraph.contentsCurrent(
    graph,
    packageCache,
    project.getService(classOf[BendSourceCatalog]),
    canceled
  )

  override def load(
      root: BendSourceRecord,
      basePath: String,
      packageCache: String,
      canceled: () => Boolean
  ): BendLoadedGraph =
    BendWorkspaceGraph.load(
      root,
      basePath,
      packageCache,
      project.getService(classOf[BendSourceCatalog]),
      canceled
    )

  override def siblingLaws(proofPath: String): Option[BendSourceRecord] =
    project
      .getService(classOf[BendSourceCatalog])
      .source(Path.of(proofPath).resolveSibling("LAWS.bend").toString)
