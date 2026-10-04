package com.dearlordylord.bend.idea.workspace.api

import com.dearlordylord.bend.idea.workspace.model.BendSourceRecord

/** Read current source with open documents taking precedence over persisted VFS
  * content. Workspace graph consumers share this boundary.
  */
trait BendSourceCatalog:
  def source(path: String): Option[BendSourceRecord]

  /** Canonical directory identity; implementations keep filesystem access here.
    */
  def canonicalPath(path: String): String = path

  /** Offline name-to-hash lookup only; absent or malformed entries stay absent.
    */
  def cachedPackageHash(packageCache: String, name: String): Option[String] =
    None
