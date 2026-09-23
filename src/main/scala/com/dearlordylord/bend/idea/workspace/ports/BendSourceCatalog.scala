package com.dearlordylord.bend.idea.workspace.ports

import com.dearlordylord.bend.idea.workspace.model.BendSourceRecord

/** The adapter reads current documents before persisted VFS content. */
trait BendSourceCatalog:
  def source(path: String): Option[BendSourceRecord]
