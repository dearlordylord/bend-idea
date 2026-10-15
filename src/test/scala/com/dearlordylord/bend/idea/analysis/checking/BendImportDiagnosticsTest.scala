package com.dearlordylord.bend.idea.analysis.checking

import com.dearlordylord.bend.idea.analysis.model.*
import com.dearlordylord.bend.idea.model.FileId
import com.dearlordylord.bend.idea.toolchain.api.BendToolchainSelection
import com.dearlordylord.bend.idea.workspace.api.BendImportLines
import com.dearlordylord.bend.idea.workspace.model.*
import org.junit.Assert.*
import org.junit.Test

final class BendImportDiagnosticsTest:
  private def record(name: String, text: String): BendSourceRecord =
    BendSourceRecord(
      new FileId("/" + name, true),
      "/" + name,
      text,
      3L,
      BendImportLines.parse(text)
    )

  @Test def transitiveDiamondImportsRetainExactCauseAndPathRanges(): Unit =
    val root = record(
      "main.bend",
      "import ./middle.bend as M # root\nimport ./bad.bend as B\n"
    )
    val middle = record("middle.bend", "import ./bad.bend as B # middle\n")
    val bad = record("bad.bend", "def bad() -> Type:\n  missing\n")
    val files = List(root, middle, bad)
    def edge(from: BendSourceRecord, index: Int, to: BendSourceRecord) =
      BendLoadedEdge(
        from.id,
        from.imports(index),
        to.path,
        to.path,
        Some(to.id)
      )
    val graph = BendLoadedGraph(
      root.id,
      files.map(r => BendLoadedFile(r, r.path)),
      List(edge(root, 0, middle), edge(root, 1, bad), edge(middle, 0, bad)),
      Nil
    )
    val snapshot = BendCheckSnapshot(
      root.id,
      root.path,
      root.text,
      root.revision,
      BendToolchainSelection("/bend", "/base", "/cache", false, 0L),
      graph = Some(graph)
    )
    val cause = BendDiagnostic(
      "unknown name missing",
      BendLocation.SourceRange(bad.id, BendTextRange(21, 28))
    )
    val result = BendCheckResult(
      BendAnalysisKey.from(snapshot),
      BendCheckOutcome.Failed,
      BendCompleteness.Unknown,
      BendReliance.Unknown,
      List(cause),
      "error"
    )
    val projected = BendImportDiagnostics.project(snapshot, result)
    assertEquals(cause, projected.diagnostics.head)
    assertEquals(4, projected.diagnostics.size)
    projected.diagnostics.tail.foreach { diagnostic =>
      assertEquals(
        Some(BendRelatedDiagnostic(cause.message, cause.location)),
        diagnostic.related
      )
      diagnostic.location match
        case BendLocation.SourceRange(id, range) =>
          val text =
            files.find(_.id == id).get.text.substring(range.start, range.end)
          assertTrue(text == "./middle.bend" || text == "./bad.bend")
        case other => fail(s"Expected exact import range, got $other")
    }
    assertEquals(projected, BendImportDiagnostics.project(snapshot, projected))
    assertEquals(
      result.copy(outcome = BendCheckOutcome.Unavailable),
      BendImportDiagnostics.project(
        snapshot,
        result.copy(outcome = BendCheckOutcome.Unavailable)
      )
    )
    assertEquals(
      result.copy(diagnostics =
        List(BendDiagnostic("unmapped", BendLocation.RootOnly))
      ),
      BendImportDiagnostics.project(
        snapshot,
        result.copy(diagnostics =
          List(BendDiagnostic("unmapped", BendLocation.RootOnly))
        )
      )
    )

  private def repeated(count: Int): (BendCheckSnapshot, BendCheckResult) =
    val root =
      record("root.bend", List.fill(count)("import ./bad.bend as B\n").mkString)
    val bad = record("bad.bend", "def bad() -> Type:\n  missing\n")
    val graph = BendLoadedGraph(
      root.id,
      List(BendLoadedFile(root, ""), BendLoadedFile(bad, "bad")),
      root.imports.map(imp =>
        BendLoadedEdge(root.id, imp, bad.path, "bad", Some(bad.id))
      ),
      Nil
    )
    val snapshot = BendCheckSnapshot(
      root.id,
      root.path,
      root.text,
      root.revision,
      BendToolchainSelection("/bend", "/base", "/cache", false, 0L),
      graph = Some(graph)
    )
    val cause =
      BendDiagnostic("unknown name missing", BendLocation.SourceLine(bad.id, 1))
    val result = BendCheckResult(
      BendAnalysisKey.from(snapshot),
      BendCheckOutcome.Failed,
      BendCompleteness.Unknown,
      BendReliance.Unknown,
      List(cause),
      "error"
    )
    (snapshot, result)

  @Test def repeatedImportsHaveBoundedProjectionAndHonestOriginalErrors()
      : Unit =
    val (snapshot, result) = repeated(
      BendImportDiagnostics.MaximumProjections * 2
    )
    assertEquals(
      snapshot.graph.get.edges.size,
      snapshot.graph.get.validImportEdges.size
    )
    val projected = BendImportDiagnostics.project(snapshot, result)
    assertEquals(result.diagnostics.head, projected.diagnostics.head)
    assertEquals(
      BendImportDiagnostics.MaximumProjections + 1,
      projected.diagnostics.size
    )
    assertTrue(projected.importDiagnosticsTruncated)
    assertTrue(
      projected.status.contains("import diagnostic projection limited")
    )
    assertEquals(result.outcome, projected.outcome)

    val (oversized, original) = repeated(BendImportDiagnostics.MaximumEdges * 4)
    val limited = BendImportDiagnostics.project(oversized, original)
    assertEquals(original.diagnostics, limited.diagnostics)
    assertTrue(limited.importDiagnosticsTruncated)

  @Test def cancellationDiscardsPartialProjectionWithoutChangingCompilerJudgment()
      : Unit =
    val (snapshot, result) = repeated(1000)
    var polls = 0
    val projected = BendImportDiagnostics.project(
      snapshot,
      result,
      () => {
        polls += 1
        polls > 10
      }
    )
    assertTrue(polls > 10)
    assertEquals(result, projected)
    assertEquals(
      result,
      BendImportDiagnostics.project(snapshot, result, () => true)
    )

  @Test def bulkValidationMatchesScalarForInvalidEdgesAndNamespaces(): Unit =
    val (snapshot, _) = repeated(3)
    val graph = snapshot.graph.get
    val edges = graph.edges
    val wrongNamespace = edges(1).copy(namespace = "other")
    val graphWithProblems = graph.copy(
      edges = List(edges.head, wrongNamespace, edges(1), edges(2)),
      problems = List(
        BendGraphProblem.Missing(
          edges.head.from,
          edges.head.importLine,
          "missing"
        )
      )
    )
    assertEquals(
      List(edges(2) -> edges(2).target.get),
      graphWithProblems.validImportEdges
    )
    graphWithProblems.edges.foreach { edge =>
      assertEquals(
        graphWithProblems.validImportEdges
          .find(_._1.importLine.offset == edge.importLine.offset)
          .map(_._2),
        graphWithProblems.importTarget(edge.from, edge.importLine.offset)
      )
    }
