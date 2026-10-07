package com.dearlordylord.bend.idea.features.proofs

import com.dearlordylord.bend.idea.model.FileId
import com.dearlordylord.bend.idea.workspace.model.BendLoadedGraph
import com.dearlordylord.bend.idea.symbols.api.{
  BendSourceDeclarationFact,
  BendSourceHandle
}

private[proofs] final case class BendProofLinkRevision(
    psi: Long,
    vfs: Long,
    configuration: Long
)

/** Source links and their immutable loading inputs; no PSI or compiler status.
  */
private[proofs] final case class BendProofLinkInventory(
    graph: BendLoadedGraph,
    root: FileId,
    declarations: Map[BendSourceHandle, BendSourceDeclarationFact],
    lawsByFill: Map[BendSourceHandle, BendSourceDeclarationFact],
    fillsByLaw: Map[BendSourceHandle, List[BendSourceDeclarationFact]],
    largeSources: Set[FileId],
    capped: Boolean
):
  def nameCharacters: Long =
    declarations.valuesIterator.map(_.name.length.toLong).sum
  def sourceCharacters: Long =
    graph.files.iterator.map(_.source.text.length.toLong).sum

/** Project lifetime; globally bounded even when roots contain very long names.
  * Revisions are supplied and checked by the navigation capture owner.
  */
final class BendProofLinkCache extends com.intellij.openapi.Disposable:
  private var disposed = false

  override def dispose(): Unit = synchronized {
    disposed = true
    entries = Nil
    revision = None
  }
  private var revision: Option[BendProofLinkRevision] = None
  private var entries = List.empty[(String, BendProofLinkInventory)]

  private[proofs] def get(
      path: String,
      expected: BendProofLinkRevision
  ): Option[BendProofLinkInventory] = synchronized {
    if disposed then return None
    if !revision.contains(expected) then
      revision = Some(expected)
      entries = Nil
    val found = entries.find(_._1 == path)
    found.foreach(entry => entries = entry :: entries.filterNot(_._1 == path))
    found.map(_._2)
  }

  private[proofs] def put(
      path: String,
      expected: BendProofLinkRevision,
      inventory: BendProofLinkInventory
  ): Unit = synchronized {
    if disposed then return
    if !revision.contains(expected) then
      revision = Some(expected)
      entries = Nil
    val candidates = (path -> inventory) :: entries.filterNot(_._1 == path)
    // At most 32 roots, 16K declarations, 2M name and 4M source characters.
    entries = candidates
      .foldLeft(List.empty[(String, BendProofLinkInventory)]) { (kept, entry) =>
        if kept.size < 32 && kept
            .map(_._2.declarations.size.toLong)
            .sum + entry._2.declarations.size <= 16384 &&
          kept
            .map(_._2.nameCharacters)
            .sum + entry._2.nameCharacters <= 2000000 &&
          kept
            .map(_._2.sourceCharacters)
            .sum + entry._2.sourceCharacters <= 4000000
        then kept :+ entry
        else kept
      }
  }
