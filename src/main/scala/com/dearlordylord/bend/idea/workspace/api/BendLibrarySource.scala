package com.dearlordylord.bend.idea.workspace.api

/** One captured source revision. The identity is a canonical file path when available. */
final case class BendBaseSource(identity: String, text: String, sourceRevision: Long,
    configurationRevision: Long)

enum BendBaseState:
  case Available(source: BendBaseSource)
  case Missing(path: String)

/** The project service captures current documents before persisted VFS content. */
trait BendLibrarySource:
  def base(path: String, configurationRevision: Long): BendBaseState
