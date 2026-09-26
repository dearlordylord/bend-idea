package com.dearlordylord.bend.idea.adapters.cli

import com.dearlordylord.bend.idea.toolchain.api.{
  BendCompilerInfo,
  BendCompilerInfoResult
}
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

final class BendCompilerInfoProbeServiceTest:
  @Test def probeReadsHelpWithoutInvokingSource(): Unit =
    val directory = Files.createTempDirectory("bend-compiler-info-")
    try
      val executable = directory.resolve("bend")
      val arguments = directory.resolve("arguments.txt")
      Files.writeString(
        executable,
        "#!/bin/sh\n" +
          s"printf '%s\\n' \"$$*\" > '${arguments.toString}'\n" +
          "printf '%s\\n' 'Bend 2.0.16: check, run and build.'\n"
      )
      assertTrue(executable.toFile.setExecutable(true))

      val result = new BendCompilerInfoProbeService().inspect(
        executable.toString,
        () => false
      )

      assertEquals(
        BendCompilerInfoResult.Detected(
          BendCompilerInfo(Some("2.0.16"))
        ),
        result
      )
      assertEquals("--help", Files.readString(arguments).trim)
    finally
      val paths = Files.walk(directory)
      try
        paths
          .sorted(java.util.Comparator.reverseOrder())
          .forEach(path => {
            val _ = Files.deleteIfExists(path)
          })
      finally paths.close()
