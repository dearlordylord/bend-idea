package com.dearlordylord.bend.idea.adapters.structured

import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

final class BendStructuredProtocolTest:
  @Test def opaqueIdentityStillRequiresExactProtocolAndAdvertisedOperation()
      : Unit =
    val directory = Files.createTempDirectory("bend-semantic-capabilities-")
    val executable = directory.resolve("capabilities")
    try
      val cases = List(
        ("1", "\"bend-next-local\"", "[\"diagnostic\"]", true),
        ("99", "\"bend-next-local\"", "[\"diagnostic\"]", false),
        ("\"1\"", "\"bend-next-local\"", "[\"diagnostic\"]", false),
        ("1.5", "\"bend-next-local\"", "[\"diagnostic\"]", false),
        ("1", "\" \"", "[\"diagnostic\"]", false),
        ("1", "42", "[\"diagnostic\"]", false),
        ("1", "\"bend-next-local\"", "[\"goal-first-named-hole\"]", false),
        ("1", "\"bend-next-local\"", "null", false)
      )
      cases.foreach { case (protocol, compiler, operations, expected) =>
        val response =
          s"""{"protocol":$protocol,"compiler":$compiler,"operations":$operations}"""
        Files.writeString(
          executable,
          s"#!/bin/sh\nprintf '%s\\n' '$response'\n"
        )
        assertTrue(executable.toFile.setExecutable(true))
        assertEquals(
          response,
          expected,
          BendStructuredProtocol.supports(
            executable.toString,
            directory,
            Map.empty,
            "diagnostic",
            () => false
          )
        )
      }
    finally
      val _ = Files.deleteIfExists(executable)
      val _ = Files.deleteIfExists(directory)
