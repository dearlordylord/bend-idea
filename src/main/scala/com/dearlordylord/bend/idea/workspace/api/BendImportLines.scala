package com.dearlordylord.bend.idea.workspace.api

/** Loader-compatible recognition of the one built-in library import.
  * The pinned loader scans trimmed source lines before parsing declarations.
  */
object BendImportLines:
  private val Base = "^import\\s+Base\\s*(?:#.*)?$".r
  private val AnyImport = "^import(\\s.*|)$".r

  def importsBase(source: String): Boolean =
    source.linesIterator.map(_.trim)
      .takeWhile(line => line.isEmpty || line.startsWith("#") || AnyImport.matches(line))
      .exists(Base.matches)
