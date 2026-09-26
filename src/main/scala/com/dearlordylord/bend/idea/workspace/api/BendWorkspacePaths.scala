package com.dearlordylord.bend.idea.workspace.api

/** Read-only, bounded local path inventory for import completion. */
trait BendWorkspacePaths:
  def children(path: String, limit: Int): List[BendPathEntry]

  /** Bounded project-local candidates by source filename, including open files.
    */
  def filesNamed(name: String, limit: Int): BendNamedPathInventory

  /** Complete, bounded project-local path inventory for refactoring. */
  def filesWithExtension(
      extension: String,
      limit: Int
  ): Either[String, List[String]]

final case class BendPathEntry(name: String, directory: Boolean)

enum BendPathInventoryStatus:
  case Complete, Capped, IndexUnavailable, IndexUnavailableAndCapped

object BendPathInventoryStatus:
  def of(indexAvailable: Boolean, capped: Boolean): BendPathInventoryStatus =
    (indexAvailable, capped) match
      case (true, false)  => BendPathInventoryStatus.Complete
      case (true, true)   => BendPathInventoryStatus.Capped
      case (false, false) => BendPathInventoryStatus.IndexUnavailable
      case (false, true)  => BendPathInventoryStatus.IndexUnavailableAndCapped

  def markCapped(status: BendPathInventoryStatus): BendPathInventoryStatus =
    status match
      case BendPathInventoryStatus.Complete => BendPathInventoryStatus.Capped
      case BendPathInventoryStatus.Capped   => BendPathInventoryStatus.Capped
      case BendPathInventoryStatus.IndexUnavailable =>
        BendPathInventoryStatus.IndexUnavailableAndCapped
      case BendPathInventoryStatus.IndexUnavailableAndCapped =>
        BendPathInventoryStatus.IndexUnavailableAndCapped

  def notice(status: BendPathInventoryStatus): Option[String] = status match
    case BendPathInventoryStatus.Complete => None
    case BendPathInventoryStatus.Capped   =>
      Some(
        "Proof root suggestions were capped; use Browse to select another root."
      )
    case BendPathInventoryStatus.IndexUnavailable =>
      Some(
        "Project indexing is unavailable; showing saved and open proof roots."
      )
    case BendPathInventoryStatus.IndexUnavailableAndCapped =>
      Some(
        "Project indexing is unavailable and proof root suggestions were capped."
      )

final case class BendNamedPathInventory(
    paths: List[String],
    status: BendPathInventoryStatus
)
