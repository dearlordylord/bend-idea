package com.dearlordylord.bend.idea.workspace.api

/** Read-only, bounded local path inventory for import completion. */
trait BendWorkspacePaths:
  def children(path: String, limit: Int): List[BendPathEntry]

final case class BendPathEntry(name: String, directory: Boolean)
