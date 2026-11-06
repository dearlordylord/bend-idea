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
import scala.jdk.CollectionConverters.*

final class BendLintFreshnessTest:
  @Test def changedInputDuringCompilerProbeCannotBecomeTheTrustedBaseline()
      : Unit =
    probeMutation("src/linter/seam.ts", append = true)

  @Test def nativeArtifactAddedDuringProbeCannotRunOutsideTheInventory(): Unit =
    probeMutation(
      "cache/bend-lint/server/programs/unrecorded-native",
      append = false
    )

  private def probeMutation(relative: String, append: Boolean): Unit =
    val installed = Option(System.getenv("BEND_TEST_LINT_DIR"))
    val bun = Option(System.getenv("BEND_TEST_LINT_BUN"))
    org.junit.Assume.assumeTrue(
      "Set prepared lint inputs for the real freshness boundary",
      installed.nonEmpty && bun.nonEmpty
    )
    val directory = Files.createTempDirectory("bend-lint-freshness-")
    try
      val source = Path.of(installed.get)
      val copy = directory.resolve("tools")
      val stream = Files.walk(source)
      try
        stream.iterator().asScala.foreach { path =>
          val target = copy.resolve(source.relativize(path))
          if Files.isDirectory(path) then
            val _ = Files.createDirectories(target)
          else
            val _ = Files.copy(path, target)
        }
      finally stream.close()
      val executable = directory.resolve("compiler")
      val mutation = copy.resolve(relative)
      val operator = if append then ">>" else ">"
      val _ = Files.writeString(
        executable,
        s"#!/bin/sh\nprintf 'changed' $operator '$mutation'\nprintf 'Bend 2.0.37: compiler help\\n'\n"
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
      val result = BendLintBackend.run(
        snapshot,
        directory,
        root,
        Map.empty,
        Nil,
        () => false
      )
      assertEquals(result.details, BendLintOutcome.Unavailable, result.outcome)
      assertTrue(
        result.details,
        result.details.contains("changed before execution")
      )
      assertTrue(result.findings.isEmpty)
    finally
      val paths = Files.walk(directory)
      try
        paths
          .sorted(java.util.Comparator.reverseOrder())
          .forEach(path => {
            val _ = Files.deleteIfExists(path)
          })
      finally paths.close()
