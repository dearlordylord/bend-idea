package com.dearlordylord.bend.idea.adapters.cli

import com.dearlordylord.bend.idea.analysis.model.*
import com.dearlordylord.bend.idea.model.FileId
import com.dearlordylord.bend.idea.toolchain.api.{
  BendLintOptions,
  BendToolchainSelection
}
import java.nio.file.{Files, Path}
import org.junit.Assert.*
import org.junit.Test

final class BendLintFreshnessTest:
  @Test def lintDoesNotInvokeOrRequireTheConfiguredCompiler(): Unit =
    fixture { (snapshot, directory, root, _, marker) =>
      val text = snapshot.text
      val mapping = BendSourceMapping(
        snapshot.root,
        1L,
        root.toString,
        root.toString,
        "",
        text,
        text,
        Set.empty,
        Nil,
        text,
        Nil
      )
      val result = BendLintBackend.run(
        snapshot,
        directory,
        root,
        Map.empty,
        List(mapping),
        () => false
      )
      assertEquals(result.details, BendLintOutcome.Completed, result.outcome)
      assertFalse(
        "Lint must not invoke the project's compiler",
        Files.exists(marker)
      )
    }

  @Test def changedInputAtProcessBoundaryCannotBePublished(): Unit =
    mutation("src/linter/seam.ts", append = true)

  @Test def nativeArtifactAddedAtProcessBoundaryInvalidatesPublication(): Unit =
    mutation(
      "cache/bend-lint/server/programs/unrecorded-native",
      append = false
    )

  private def mutation(relative: String, append: Boolean): Unit =
    fixture { (snapshot, directory, root, tools, _) =>
      val baseline = BendLintBackend.run(
        snapshot,
        directory,
        root,
        Map.empty,
        Nil,
        () => false
      )
      assertEquals(
        baseline.details,
        BendLintOutcome.Completed,
        baseline.outcome
      )
      Files.delete(directory.resolve("idea-lint-offline.mjs"))
      val changed = new java.util.concurrent.atomic.AtomicBoolean(false)
      val result = BendLintBackend.run(
        snapshot,
        directory,
        root,
        Map.empty,
        Nil,
        () => {
          if Files.isRegularFile(directory.resolve("idea-lint-offline.mjs")) &&
            changed.compareAndSet(false, true)
          then
            val options =
              if append then Array(java.nio.file.StandardOpenOption.APPEND)
              else Array(java.nio.file.StandardOpenOption.CREATE_NEW)
            val _ =
              Files.writeString(tools.resolve(relative), "changed", options*)
          false
        }
      )
      assertTrue("The process-boundary mutation must execute", changed.get())
      assertEquals(result.details, BendLintOutcome.Unavailable, result.outcome)
      assertTrue(
        result.details,
        result.details.contains("changed during execution")
      )
      assertTrue(result.findings.isEmpty)
    }

  private def fixture(
      run: (BendCheckSnapshot, Path, Path, Path, Path) => Unit
  ): Unit =
    val bun = Option(System.getenv("BEND_TEST_INSTALL_BUN"))
    org.junit.Assume.assumeTrue(
      "Set BEND_TEST_INSTALL_BUN for an isolated verified tool installation",
      bun.nonEmpty
    )
    val directory = Files.createTempDirectory("bend-lint-freshness-")
    try
      val installed = new BendLintInstallation(directory.resolve("managed"))
        .install(bun.get, () => false)
      val copy = installed match
        case com.dearlordylord.bend.idea.toolchain.api.BendLintInstallationResult
              .Installed(_, path, _) =>
          Path.of(path)
        case other => throw new AssertionError(other.toString)
      val executable = directory.resolve("compiler")
      val marker = directory.resolve("compiler-invoked")
      val _ = Files.writeString(
        executable,
        s"#!/bin/sh\nprintf invoked > '$marker'\nexit 99\n"
      )
      val _ = executable.toFile.setExecutable(true)
      val root = directory.resolve("source.bend")
      val text = "def Value() -> Type:\n  Type\n"
      val _ = Files.writeString(root, text)
      val snapshot = BendCheckSnapshot(
        new FileId(root.toString, true),
        root.toString,
        text,
        1L,
        BendToolchainSelection(
          executable.toString,
          "",
          "",
          true,
          1L,
          lint = BendLintOptions(true, bun.get, copy.toString)
        ),
        lintRequested = true
      )
      run(snapshot, directory, root, copy, marker)
    finally
      val paths = Files.walk(directory)
      try
        paths
          .sorted(java.util.Comparator.reverseOrder())
          .forEach(path => {
            val _ = Files.deleteIfExists(path)
          })
      finally paths.close()
