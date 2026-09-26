package com.dearlordylord.bend.idea.features.proofs

import java.nio.file.Path
import com.dearlordylord.bend.idea.workspace.api.{
  BendNamedPathInventory,
  BendPathInventoryStatus
}

final case class BendProofRootInventory(
    paths: List[String],
    status: BendPathInventoryStatus
)

/** Persisted path spelling is kept separate from the canonical root identity.
  */
final case class BendProofRootChoice(path: Option[String]):
  override def toString: String = path match
    case None        => "Browse for a Bend proof root…"
    case Some(value) =>
      val name = Path.of(value).getFileName.toString
      s"$name — $value"

object BendProofRootSelection:
  def inventory(
      saved: List[String],
      discovered: BendNamedPathInventory,
      limit: Int
  ): BendProofRootInventory =
    val candidates = (saved ++ discovered.paths).distinct
    val capped = candidates.size > limit
    BendProofRootInventory(
      candidates.take(limit.max(0)),
      if capped then BendPathInventoryStatus.markCapped(discovered.status)
      else discovered.status
    )

  def candidates(
      currentPath: Option[String],
      saved: List[String],
      conventional: List[String]
  ): List[String] =
    def normalized(path: String): Option[String] =
      try Option(Path.of(path).toAbsolutePath.normalize().toString)
      catch case _: java.nio.file.InvalidPathException => None
    def proofFile(path: String): Boolean =
      Path.of(path).getFileName.toString == "PROOF.bend"

    val currentProof = currentPath.toList.filter(proofFile)
    val savedPaths = saved.flatMap(normalized)
    val suggested = conventional.flatMap(normalized).filter(proofFile)
    val preferredSibling = currentPath.toList
      .filter(path => Path.of(path).getFileName.toString == "LAWS.bend")
      .flatMap(path =>
        suggested.filter(
          _.equals(Path.of(path).resolveSibling("PROOF.bend").toString)
        )
      )
    (savedPaths ++ currentProof.flatMap(
      normalized
    ) ++ preferredSibling ++ suggested).distinct

  def choices(paths: List[String]): List[BendProofRootChoice] =
    paths.map(path => BendProofRootChoice(Some(path))) :+
      BendProofRootChoice(None)
