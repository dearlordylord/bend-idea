package com.dearlordylord.bend.idea.adapters.cli

import com.dearlordylord.bend.idea.analysis.model.*
import com.dearlordylord.bend.idea.analysis.api.BendGraphSnapshot
import com.dearlordylord.bend.idea.model.FileId
import com.dearlordylord.bend.idea.toolchain.api.BendToolchainSelection
import com.dearlordylord.bend.idea.workspace.api.{
  BendImportLines,
  BendSourceCatalog,
  BendWorkspaceGraph
}
import com.dearlordylord.bend.idea.workspace.model.BendSourceRecord
import java.nio.file.{Files, Path}
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Optional release smoke test for an installed Bend binary and matching Base.
  * Set BEND_TEST_CURRENT_COMPILER and BEND_TEST_CURRENT_BASE to exercise it.
  */
final class BendCurrentCompilerSmokeTest:
  @Test def currentCompilerChecksCompleteIncompleteFailedAndUnsafe(): Unit =
    val executable = Option(System.getenv("BEND_TEST_CURRENT_COMPILER"))
    val base = Option(System.getenv("BEND_TEST_CURRENT_BASE"))
    assumeTrue(
      "Current Bend smoke inputs are not configured",
      executable.nonEmpty && base.nonEmpty
    )
    val directory = Files.createTempDirectory("bend-current-smoke-")
    try
      val backend = new BendCliCheckBackend(directory)
      def check(name: String, source: String): BendCheckResult =
        val original = directory.resolve(name)
        backend.check(
          BendCheckSnapshot(
            new FileId(original.toString, false),
            original.toString,
            source,
            1L,
            BendToolchainSelection(
              executable.get,
              base.get,
              "",
              true,
              1L
            )
          )
        )

      val complete =
        check("complete.bend", "import Base\ndef main() -> U32:\n  1\n")
      assertEquals(complete.details, BendCheckOutcome.Success, complete.outcome)
      assertEquals(BendCompleteness.Complete, complete.completeness)
      assertEquals(BendReliance.None, complete.reliance)

      val todo =
        check("todo.bend", "import Base\ndef main() -> U32:\n  ?TODO\n")
      assertEquals(todo.details, BendCheckOutcome.Failed, todo.outcome)
      assertEquals(BendCompleteness.Incomplete, todo.completeness)

      val error =
        check("error.bend", "import Base\ndef main() -> U32:\n  missing_name\n")
      assertEquals(error.details, BendCheckOutcome.Failed, error.outcome)
      assertEquals(BendLocation.Line(2), error.diagnostics.head.location)

      val unsafe =
        check("unsafe.bend", "import Base\n@unsafe def main() -> U32:\n  1\n")
      assertEquals(unsafe.details, BendCheckOutcome.Success, unsafe.outcome)
      assertEquals(BendCompleteness.Complete, unsafe.completeness)
      assertEquals(BendReliance.UnsafeOrForeign, unsafe.reliance)

      val lawPath = directory.resolve("LAWS.bend")
      Files.writeString(
        lawPath,
        "import Base\nlaw same:\n  for x: Nat\n  {x == x : Nat}\n"
      )
      val proofPath = directory.resolve("PROOF.bend")
      Files.writeString(
        proofPath,
        "import Base\nimport ./LAWS.bend as Laws\n\ndef Laws.same(x):\n  {==}\n"
      )
      def record(path: Path): BendSourceRecord =
        val text = Files.readString(path)
        BendSourceRecord(
          new FileId(path.toRealPath().toString, true),
          path.toString,
          text,
          1L,
          BendImportLines.parse(text)
        )
      val proof = record(proofPath)
      val catalog = new BendSourceCatalog:
        override def source(path: String): Option[BendSourceRecord] =
          val file = Path.of(path)
          if Files.isRegularFile(file) then Some(record(file)) else None
      val graph = BendWorkspaceGraph.load(
        proof,
        base.get,
        directory.resolve("lib").toString,
        catalog
      )
      val proofResult = backend.check(
        BendGraphSnapshot.attach(
          BendCheckSnapshot(
            proof.id,
            proof.path,
            proof.text,
            proof.revision,
            BendToolchainSelection(
              executable.get,
              base.get,
              directory.resolve("lib").toString,
              true,
              1L
            )
          ),
          graph,
          Some(record(lawPath))
        )
      )
      assertEquals(
        proofResult.details,
        BendCheckOutcome.Success,
        proofResult.outcome
      )
      assertEquals(BendCompleteness.Complete, proofResult.completeness)
    finally
      val files = Files.walk(directory)
      try
        files
          .sorted(java.util.Comparator.reverseOrder())
          .forEach(p => {
            val _ = Files.deleteIfExists(p)
          })
      finally files.close()
