package com.dearlordylord.bend.idea.workspace.api

/** Read-only, bounded local path inventory for import completion. */
trait BendWorkspacePaths:
  def children(path: String, limit: Int): List[BendPathEntry]

  /** Bounded project-local candidates by source filename, including open files.
    */
  def filesNamed(name: String, limit: Int): List[String]

  /** Complete, bounded project-local path inventory for refactoring. */
  def filesWithExtension(
      extension: String,
      limit: Int
  ): Either[String, List[String]]

final case class BendPathEntry(name: String, directory: Boolean)
