package com.dearlordylord.bend.idea.workspace.api

import com.dearlordylord.bend.idea.workspace.model.BendSourceRecord

/** Read current source with modified documents (including closed path aliases)
  * and open editor revisions taking precedence over persisted VFS content.
  * Workspace graph consumers share this boundary.
  */
trait BendSourceCatalog:
  def source(path: String): Option[BendSourceRecord]

  /** The current source owner's path for native PSI reacquisition. Adapters may
    * answer without rereading its text; this uses the same precedence as
    * source.
    */
  def currentSourcePath(path: String): Option[String] = source(path).map(_.path)

  /** Canonical directory identity; implementations keep filesystem access here.
    */
  def canonicalPath(path: String): String = path

  /** Offline name-to-hash lookup only; absent or malformed entries stay absent.
    */
  def cachedPackageHash(packageCache: String, name: String): Option[String] =
    None
