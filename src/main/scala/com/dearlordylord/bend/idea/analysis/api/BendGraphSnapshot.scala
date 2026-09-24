package com.dearlordylord.bend.idea.analysis.api

import com.dearlordylord.bend.idea.analysis.model.{
  BendAnalysisKey,
  BendCheckSnapshot
}
import com.dearlordylord.bend.idea.workspace.model.{
  BendLoadedGraph,
  BendSourceRecord
}

/** Fingerprints the captured graph, including missing-edge observations and
  * PROOF guard.
  */
object BendGraphSnapshot:
  def attach(
      root: BendCheckSnapshot,
      graph: BendLoadedGraph,
      siblingLaws: Option[BendSourceRecord]
  ): BendCheckSnapshot =
    val parts = graph.files.map(file =>
      s"${file.source.id.value}:${file.source.revision}:${file.namespace}:" +
        BendAnalysisKey.sourceDigest(file.source.text)
    ) ++
      graph.edges.map(edge =>
        s"${edge.from.value}:${edge.importLine.spelling}:${edge.requestedPath}:${edge.namespace}:" +
          edge.target.fold("missing")(_.value)
      ) ++
      graph.problems.map(_.toString) ++
      List(
        siblingLaws.fold("no-sibling-laws")(law =>
          s"sibling-laws:${law.id.value}:${law.revision}:" + BendAnalysisKey
            .sourceDigest(law.text)
        )
      )
    root.copy(
      graph = Some(graph),
      siblingLaws = siblingLaws,
      inputFingerprint = BendAnalysisKey.sourceDigest(parts.mkString("\u0000"))
    )
