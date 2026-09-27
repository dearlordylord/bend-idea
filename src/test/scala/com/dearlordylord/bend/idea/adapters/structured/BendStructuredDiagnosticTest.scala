package com.dearlordylord.bend.idea.adapters.structured

import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

final class BendStructuredDiagnosticTest:
  @Test def malformedSpanFallsBackWithoutThrowing(): Unit =
    val directory = Files.createTempDirectory("bend-structured-json-")
    try
      val executable = directory.resolve("helper")
      Files.writeString(
        executable,
        """#!/bin/sh
          |if [ "${1:-}" = "--idea-structured-capabilities" ]; then
          |  printf '%s\n' '{"protocol":1,"compiler":"bend-2.0.25-pinned","operations":["diagnostic"]}'
          |else
          |  printf '%s\n' '{"protocol":1,"kind":"first-error","message":"Error: example","span":"invalid"}'
          |fi
          |""".stripMargin
      )
      assertTrue(executable.toFile.setExecutable(true))
      assertEquals(
        None,
        BendStructuredDiagnostic.firstError(
          executable.toString,
          directory,
          directory.resolve("main.bend"),
          Map.empty,
          "Error: example",
          Nil,
          () => false
        )
      )
    finally
      val files = Files.walk(directory)
      try
        files
          .sorted(java.util.Comparator.reverseOrder())
          .forEach(p => {
            val _ = Files.deleteIfExists(p)
          })
      finally files.close()
