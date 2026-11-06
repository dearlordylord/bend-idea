package com.dearlordylord.bend.idea.adapters.cli

import com.dearlordylord.bend.idea.adapters.process.{
  BendBoundedProcess,
  BendProcessOutcome
}
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

final class BendLintOfflineTest:
  @Test def preloadRejectsFetchInBunDescendantsAndCompilerBuilds(): Unit =
    val bun = Option(System.getenv("BEND_TEST_LINT_BUN"))
    org.junit.Assume.assumeTrue(
      "Set BEND_TEST_LINT_BUN for the real Bun guard test",
      bun.nonEmpty
    )
    val directory = Files.createTempDirectory("bend lint offline ")
    try
      val guard = directory.resolve("offline.mjs")
      val resource = getClass.getResourceAsStream("/lint/offline.mjs")
      try
        val _ = Files.copy(resource, guard)
      finally resource.close()
      val child = directory.resolve("child.mjs")
      val _ = Files.writeString(
        child,
        "try { await fetch('http://127.0.0.1:1/should-not-be-requested'); process.exit(3); } catch(e) { console.log(e.message); }"
      )
      val parent = directory.resolve("parent.mjs")
      val _ = Files.writeString(
        parent,
        "const child = Bun.spawn([process.execPath, process.argv[2]], { stdout: 'pipe' });" +
          "console.log(await new Response(child.stdout).text()); await child.exited;" +
          "try { Bun.spawn([process.execPath, '-o']); process.exit(4); } catch(e) { console.log(e.message); }"
      )
      val result = BendBoundedProcess.run(
        List(bun.get, parent.toString, child.toString),
        directory,
        Map("BUN_OPTIONS" -> "--preload=./offline.mjs"),
        stdoutOnly = true
      )
      result match
        case BendProcessOutcome.Exited(0, output) =>
          assertTrue(
            output,
            output.contains("Bend IDEA lint analysis is offline")
          )
          assertTrue(output, output.contains("Lint runtime is not prepared"))
        case other => fail(other.toString)
    finally
      val paths = Files.walk(directory)
      try
        paths
          .sorted(java.util.Comparator.reverseOrder())
          .forEach(path => {
            val _ = Files.deleteIfExists(path)
          })
      finally paths.close()
