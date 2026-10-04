package com.dearlordylord.bend.idea.adapters.cli

import com.dearlordylord.bend.idea.analysis.model.*
import com.dearlordylord.bend.idea.model.FileId
import com.dearlordylord.bend.idea.toolchain.api.BendToolchainSelection
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.{Files, Path}
import java.util.concurrent.atomic.AtomicBoolean

/** Uses the pinned compiler source via Bun and controlled negative CLI stubs.
  */
final class BendCliCheckBackendTest:
  private lazy val compiler = RealBendCompilerFixture.inputs

  private def withCompiler(run: Path => Unit): Unit =
    val directory = Files.createTempDirectory("bend-check-test-")
    try
      val executable = directory.resolve("bend")
      val _ = compiler.writeLauncher(executable)
      run(executable)
    finally
      val paths = Files.walk(directory)
      try
        paths
          .sorted(java.util.Comparator.reverseOrder())
          .forEach(p => {
            val _ = Files.deleteIfExists(p)
          })
      finally paths.close()

  private def snapshot(
      executable: Path,
      text: String,
      original: Path
  ): BendCheckSnapshot =
    BendCheckSnapshot(
      new FileId(original.toString, false),
      original.toString,
      text,
      4L,
      BendToolchainSelection(
        executable.toString,
        compiler.base.toString,
        "",
        true,
        1L
      )
    )

  private def backend(executable: Path): BendCliCheckBackend =
    new BendCliCheckBackend(executable.getParent)

  private def tempCount(directory: Path): Long =
    val paths = Files.list(directory)
    try
      paths
        .filter(_.getFileName.toString.startsWith("bend-editor-check-"))
        .count()
    finally paths.close()

  @Test def unknownIncompleteKindCannotBecomeStructuredSuccess(): Unit =
    withCompiler { real =>
      val executable = real.resolveSibling("protocol-bend")
      Files.writeString(
        executable,
        """#!/bin/sh
      |if [ "${1:-}" = "--idea-check-capabilities" ]; then
      |  printf '%s\n' '{"checkProtocol":1,"compiler":"test","checkOnly":true,"locations":"compiler-source-utf16","operations":["check"]}'
      |else
      |  printf '%s\n' '{"checkProtocol":1,"outcome":"success","completeness":"complete","reliance":"none","details":"complete","diagnostics":[],"incompleteKind":"future-kind"}'
      |fi
      |""".stripMargin
      )
      assertTrue(executable.toFile.setExecutable(true))
      val checked = backend(executable).check(
        snapshot(
          executable,
          "def main() -> Type:\n  Type\n",
          real.resolveSibling("main.bend")
        )
      )
      assertEquals(BendCheckOutcome.Unavailable, checked.outcome)
    }

  @Test def changedCompilerSourcesKeepRealCliCheckingAvailable(): Unit =
    withCompiler { real =>
      val copy = real.resolveSibling("updated-compiler")
      val sources = Files.walk(compiler.compilerDirectory.resolve("bend2"))
      try
        sources.forEach { source =>
          val target = copy
            .resolve("bend2")
            .resolve(
              compiler.compilerDirectory.resolve("bend2").relativize(source)
            )
          if Files.isDirectory(source) then Files.createDirectories(target)
          else Files.copy(source, target)
          ()
        }
      finally sources.close()
      val main = copy.resolve("bend2/main.ts")
      Files.writeString(
        main,
        Files
          .readString(main)
          .replace(
            "const VERSION = \"2.0.35\";",
            "const VERSION = \"2.99.0\";"
          ) +
          "\n// changed build identity\n"
      )
      val launcher = real.resolveSibling("bend-structured-launcher.sh")
      List("bend-structured-launcher.sh", "bend-structured-helper.ts").foreach {
        name =>
          val resource = getClass.getResourceAsStream("/semantic/" + name)
          try Files.copy(resource, real.resolveSibling(name))
          finally resource.close()
      }
      val executable = real.resolveSibling("updated-bend")
      Files.writeString(
        executable,
        s"""#!/bin/sh
        |export BEND_IDEA_BUN='${compiler.bunExecutable}'
        |export BEND_IDEA_BEND_DIR='${copy.resolve("bend2")}'
        |exec sh '${launcher}' "$$@"
        |""".stripMargin
      )
      assertTrue(executable.toFile.setExecutable(true))
      val cases = List(
        (
          "def main() -> Type:\n  Type\n",
          BendCheckOutcome.Success,
          BendCompleteness.Complete
        ),
        (
          "import Base\ndef main() -> U32:\n  ?need\n",
          BendCheckOutcome.Failed,
          BendCompleteness.Incomplete
        ),
        (
          "import Base\ndef main() -> U32:\n  missing_name\n",
          BendCheckOutcome.Failed,
          BendCompleteness.Unknown
        ),
        (
          "import Base\ndef main() -> IO(Unit):\n  do IO<Unit>:\n    IO.print(\"SIDE_EFFECT_MARKER\")\n",
          BendCheckOutcome.Success,
          BendCompleteness.Complete
        )
      )
      cases.foreach { case (source, outcome, completeness) =>
        val checked = backend(executable).check(
          snapshot(executable, source, real.resolveSibling("main.bend"))
        )
        assertEquals(checked.details, outcome, checked.outcome)
        assertEquals(completeness, checked.completeness)
        assertFalse(checked.details.contains("SIDE_EFFECT_MARKER"))
        assertTrue(checked.goal.isEmpty)
        assertEquals(0L, tempCount(real.getParent))
      }
    }

  @Test def advertisedCheckWithoutProtocolNeverFallsBackToText(): Unit =
    withCompiler { real =>
      val executable = real.resolveSibling("missing-protocol")
      val marker = real.resolveSibling("protocol-source-invoked")
      Files.writeString(
        executable,
        s"""#!/bin/sh
      |if [ "$${1:-}" = "--idea-check-capabilities" ]; then
      |  printf '%s\n' '{"compiler":"test","checkOnly":true,"locations":"compiler-source-utf16","operations":["check"]}'
      |elif [ "$${1:-}" = "--help" ]; then
      |  printf '%s\n' '  bend <file.bend> --check-only check the file and its imports; run nothing'
      |else
      |  touch '${marker.toString}'
      |  printf '%s\n' 'All terms check.'
      |fi
      |""".stripMargin
      )
      assertTrue(executable.toFile.setExecutable(true))
      val checked = backend(executable).check(
        snapshot(
          executable,
          "def main() -> Type:\n  Type\n",
          real.resolveSibling("main.bend")
        )
      )
      assertEquals(BendCheckOutcome.Unavailable, checked.outcome)
      assertFalse(Files.exists(marker))
    }

  @Test def missingCapabilityArrayIsUnavailableWithoutThrowing(): Unit =
    withCompiler { real =>
      val executable = real.resolveSibling("null-operations")
      Files.writeString(
        executable,
        """#!/bin/sh
      |printf '%s\n' '{"checkProtocol":1,"compiler":"test","checkOnly":true,"locations":"compiler-source-utf16"}'
      |""".stripMargin
      )
      assertTrue(executable.toFile.setExecutable(true))
      val checked = backend(executable).check(
        snapshot(
          executable,
          "def main() -> Type:\n  Type\n",
          real.resolveSibling("main.bend")
        )
      )
      assertEquals(BendCheckOutcome.Unavailable, checked.outcome)
    }

  @Test def pinnedProtocolChecksProofsHolesFailuresAndRelianceWithoutHelpOrRunningMain()
      : Unit = withCompiler { legacy =>
    val helper = legacy.resolveSibling("structured-bend")
    val _ = compiler.writeStructuredLauncher(helper, structuredChecks = true)
    val executable = legacy.resolveSibling("protocol-bend")
    Files.writeString(
      executable,
      s"""#!/bin/sh
      |if [ "$${1:-}" = "--help" ]; then
      |  touch "$$0.help-invoked"
      |  exit 1
      |fi
      |exec '${helper.toString}' "$$@"
      |""".stripMargin
    )
    assertTrue(executable.toFile.setExecutable(true))
    val cases = List(
      (
        "proof",
        "import Base\nlaw same:\n  for x: Nat\n  {x == x : Nat}\ndef same(x):\n  {==}\n",
        BendCheckOutcome.Success,
        BendCompleteness.Complete,
        BendReliance.None
      ),
      (
        "todo",
        "import Base\ndef main() -> U32:\n  ?TODO\n",
        BendCheckOutcome.Failed,
        BendCompleteness.Incomplete,
        BendReliance.Unknown
      ),
      (
        "named",
        "import Base\ndef main() -> U32:\n  ?need\n",
        BendCheckOutcome.Failed,
        BendCompleteness.Incomplete,
        BendReliance.Unknown
      ),
      (
        "error",
        "import Base\ndef main() -> U32:\n  missing_name\n",
        BendCheckOutcome.Failed,
        BendCompleteness.Unknown,
        BendReliance.Unknown
      ),
      (
        "unsafe",
        "import Base\n@unsafe def main() -> U32:\n  1\n",
        BendCheckOutcome.Success,
        BendCompleteness.Complete,
        BendReliance.UnsafeOrForeign
      ),
      (
        "foreign",
        "import Base\nlaw foreign:\n  IO(Unit)\ndef foreign():\n  import \"./never-executed.js\"\ndef main() -> IO(Unit):\n  foreign()\n",
        BendCheckOutcome.Success,
        BendCompleteness.Complete,
        BendReliance.UnsafeOrForeign
      ),
      (
        "effect",
        "import Base\ndef main() -> IO(Unit):\n  do IO<Unit>:\n    IO.print(\"SIDE_EFFECT_MARKER\")\n",
        BendCheckOutcome.Success,
        BendCompleteness.Complete,
        BendReliance.None
      )
    )
    cases.foreach { case (name, source, outcome, completeness, reliance) =>
      val original = legacy.resolveSibling(name + ".bend")
      Files.writeString(original, "# original disk content\n")
      val before = tempCount(legacy.getParent)
      val checked =
        backend(executable).check(snapshot(executable, source, original))
      assertEquals(checked.details, outcome, checked.outcome)
      assertEquals(name, completeness, checked.completeness)
      assertEquals(name, reliance, checked.reliance)
      assertFalse(checked.details.contains("SIDE_EFFECT_MARKER"))
      assertEquals("# original disk content\n", Files.readString(original))
      assertEquals(before, tempCount(legacy.getParent))
      val fallback = backend(legacy).check(snapshot(legacy, source, original))
      assertEquals(name, fallback.outcome, checked.outcome)
      assertEquals(name, fallback.completeness, checked.completeness)
      assertEquals(name, fallback.reliance, checked.reliance)
    }
    assertFalse(Files.exists(Path.of(executable.toString + ".help-invoked")))
  }

  @Test def incompatibleCheckVersionNeverInvokesSourceOrLegacyHelp(): Unit =
    withCompiler { legacy =>
      val helper = legacy.resolveSibling("structured-bend")
      val _ = compiler.writeStructuredLauncher(helper, structuredChecks = true)
      val executable = legacy.resolveSibling("future-protocol")
      Files.writeString(
        executable,
        s"""#!/bin/sh
      |if [ "$${1:-}" = "--idea-check-capabilities" ]; then
      |  '${helper.toString}' "$$@" | sed 's/"checkProtocol":1/"checkProtocol":99/'
      |else
      |  touch "$$0.source-invoked"
      |  exec '${helper.toString}' "$$@"
      |fi
      |""".stripMargin
      )
      assertTrue(executable.toFile.setExecutable(true))
      val checked = backend(executable).check(
        snapshot(
          executable,
          "import Base\ndef main() -> U32:\n  1\n",
          legacy.resolveSibling("main.bend")
        )
      )
      assertEquals(BendCheckOutcome.Unavailable, checked.outcome)
      assertFalse(
        Files.exists(Path.of(executable.toString + ".source-invoked"))
      )
      assertEquals(0L, tempCount(legacy.getParent))
    }

  @Test def sideEffectingMainIsNeverRunAndSnapshotIsRemoved(): Unit =
    withCompiler { executable =>
      val original = executable.getParent.resolve("main.bend")
      Files.writeString(original, "import Base\ndef main() -> U32:\n  1\n")
      val before = tempCount(executable.getParent)
      val checked = backend(executable).check(
        snapshot(
          executable,
          "import Base\ndef main() -> IO(Unit):\n  do IO<Unit>:\n    IO.print(\"SIDE_EFFECT_MARKER\")\n",
          original
        )
      )
      assertEquals(BendCheckOutcome.Success, checked.outcome)
      assertEquals(BendCompleteness.Complete, checked.completeness)
      assertFalse(checked.details.contains("SIDE_EFFECT_MARKER"))
      assertEquals(
        "The original disk file must remain unchanged",
        "import Base\ndef main() -> U32:\n  1\n",
        Files.readString(original)
      )
      assertEquals(before, tempCount(executable.getParent))
    }

  @Test def unsavedErrorHasFullDetailsAndConservativeLine(): Unit =
    withCompiler { executable =>
      val original = executable.getParent.resolve("bad.bend")
      Files.writeString(original, "import Base\ndef main() -> U32:\n  0\n")
      val checked = backend(executable).check(
        snapshot(
          executable,
          "import Base\ndef main() -> U32:\n  missing_name\n",
          original
        )
      )
      assertEquals(BendCheckOutcome.Failed, checked.outcome)
      assertTrue(checked.details.contains("missing_name"))
      assertEquals(BendLocation.Line(2), checked.diagnostics.head.location)
      assertEquals(
        "import Base\ndef main() -> U32:\n  0\n",
        Files.readString(original)
      )
    }

  @Test def todoIsIncompleteAndOtherImportsAreUnavailable(): Unit =
    withCompiler { executable =>
      val original = executable.getParent.resolve("todo.bend")
      val checked = backend(executable).check(
        snapshot(
          executable,
          "import Base\ndef main() -> U32:\n  ?TODO\n",
          original
        )
      )
      assertEquals(BendCompleteness.Incomplete, checked.completeness)
      assertEquals(BendCheckOutcome.Failed, checked.outcome)
      assertEquals("Check incomplete", checked.status)
      assertTrue(checked.details.contains("TODO"))
      val unsupported = backend(executable).check(
        snapshot(
          executable,
          "import 0xabcdef/foo.bend as Foo\ndef main() -> U32:\n  0\n",
          original
        )
      )
      assertEquals(BendCheckOutcome.Unavailable, unsupported.outcome)
      assertTrue(unsupported.details.contains("complete graph snapshots"))
    }

  @Test def unsafeRelianceDoesNotEraseSuccessfulCompleteness(): Unit =
    withCompiler { executable =>
      val original = executable.getParent.resolve("unsafe.bend")
      val checked = backend(executable).check(
        snapshot(
          executable,
          "import Base\n@unsafe def main() -> U32:\n  0\n",
          original
        )
      )
      assertEquals(BendCheckOutcome.Success, checked.outcome)
      assertEquals(BendCompleteness.Complete, checked.completeness)
      assertEquals(BendReliance.UnsafeOrForeign, checked.reliance)
      assertTrue(checked.status.contains("unsafe or foreign"))
    }

  @Test def noImportSourceChecksAgainstItsOwnSnapshot(): Unit = withCompiler {
    executable =>
      val original = executable.getParent.resolve("plain.bend")
      val checked = backend(executable).check(
        snapshot(executable, "def main() -> Type:\n  Type\n", original)
      )
      assertEquals(BendCheckOutcome.Success, checked.outcome)
      assertEquals(BendCompleteness.Complete, checked.completeness)
  }

  @Test def unfamiliarSuccessfulOutputCannotInventSuccessOrSourceLocation()
      : Unit = withCompiler { real =>
    val executable = real.resolveSibling("changed-bend")
    Files.writeString(
      executable,
      "#!/bin/sh\n" +
        "if [ \"${1:-}\" = \"--help\" ]; then\n" +
        "  printf '%s\\n' '  bend <file.bend> --check-only check the file and its imports; run nothing'\n" +
        "  exit 0\n" +
        "fi\n" +
        "printf '%s\\n' 'Warning: partial report' 'All terms check.' '3>|   missing_name'\n" +
        "exit 0\n"
    )
    assertTrue(executable.toFile.setExecutable(true))
    val original = executable.getParent.resolve("changed.bend")
    val checked = backend(executable).check(
      snapshot(executable, "def main() -> U32:\n  missing_name\n", original)
    )
    assertEquals(BendCheckOutcome.Unavailable, checked.outcome)
    assertEquals(BendCompleteness.Unknown, checked.completeness)
    assertEquals(BendReliance.Unknown, checked.reliance)
    assertEquals(BendLocation.RootOnly, checked.diagnostics.head.location)
  }

  @Test def mismatchedBaseIsUnavailableBeforeSourceInvocation(): Unit =
    withCompiler { executable =>
      val substitute = executable.getParent.resolve("base.bend")
      Files.writeString(substitute, "# a different Base\n")
      val request = snapshot(
        executable,
        "import Base\ndef main() -> U32:\n  0\n",
        executable.getParent.resolve("main.bend")
      )
      val checked = backend(executable).check(
        request.copy(toolchain =
          request.toolchain.copy(baseSource = substitute.toString)
        )
      )
      assertEquals(BendCheckOutcome.Unavailable, checked.outcome)
      assertTrue(checked.details.contains("does not match"))
    }

  @Test def baseWithDependenciesIsExplicitlyUnavailableOffline(): Unit =
    withCompiler { executable =>
      val substitute = executable.getParent.resolve("base-with-import.bend")
      Files.writeString(substitute, "import 0xabcdef/module.bend as M\n")
      val request = snapshot(
        executable,
        "import Base\ndef main() -> U32:\n  0\n",
        executable.getParent.resolve("main.bend")
      )
      val checked = backend(executable).check(
        request.copy(toolchain =
          request.toolchain.copy(baseSource = substitute.toString)
        )
      )
      assertEquals(BendCheckOutcome.Unavailable, checked.outcome)
      assertTrue(checked.details.contains("closed offline snapshot"))
    }

  @Test def missingAndUnsupportedCompilerNeverCheckSource(): Unit =
    withCompiler { executable =>
      val original = executable.getParent.resolve("main.bend")
      val source = "import Base\ndef main() -> U32:\n  0\n"
      val missing = backend(executable).check(
        snapshot(executable.resolveSibling("missing"), source, original)
      )
      assertEquals(BendCheckOutcome.Unavailable, missing.outcome)

      val unsupportedCompiler = executable.resolveSibling("unsupported-bend")
      val sourceMarker = unsupportedCompiler.resolveSibling(
        unsupportedCompiler.getFileName.toString + ".source-invoked"
      )
      Files.writeString(
        unsupportedCompiler,
        "#!/bin/sh\n" +
          "if [ \"${1:-}\" = \"--help\" ]; then\n" +
          "  printf '%s\\n' 'bend <file.bend> runs source only'\n" +
          "  exit 0\n" +
          "fi\n" +
          "if [ \"${1:-}\" = \"--idea-check-capabilities\" ]; then exit 1; fi\n" +
          "touch \"$0.source-invoked\"\n" +
          "exit 1\n"
      )
      assertTrue(
        "Could not mark unsupported compiler launcher executable",
        unsupportedCompiler.toFile.setExecutable(true)
      )
      val unsupported = backend(executable).check(
        snapshot(unsupportedCompiler, source, original)
      )
      assertEquals(BendCheckOutcome.Unavailable, unsupported.outcome)
      assertTrue(unsupported.details.contains("--check-only"))
      assertFalse(
        "Unsupported compilers must not receive source arguments",
        Files.exists(sourceMarker)
      )
    }

  @Test def cancellationRemovesSnapshotAndTerminatesSourceProcess(): Unit =
    val directory = Files.createTempDirectory("bend-check-cancel-")
    try
      val executable = directory.resolve("bend")
      Files.writeString(
        executable,
        "#!/bin/sh\nif [ \"$1\" = \"--idea-check-capabilities\" ]; then exit 1; fi\nif [ \"$1\" = \"--help\" ]; then echo '  bend <file.bend> --check-only check the file and its imports; run nothing'; exit 0; fi\nsleep 10\n"
      )
      executable.toFile.setExecutable(true)
      val canceled = new AtomicBoolean(false)
      val signal = new Thread(() => { Thread.sleep(150); canceled.set(true) })
      val before = tempCount(executable.getParent)
      signal.start()
      val checked = backend(executable).check(
        snapshot(
          executable,
          "def main() -> Type:\n  Type\n",
          directory.resolve("main.bend")
        ),
        () => canceled.get()
      )
      signal.join()
      assertEquals(BendCheckOutcome.Unavailable, checked.outcome)
      assertTrue(checked.details.contains("canceled"))
      assertEquals(before, tempCount(executable.getParent))
    finally
      val paths = Files.walk(directory)
      try
        paths
          .sorted(java.util.Comparator.reverseOrder())
          .forEach(p => {
            val _ = Files.deleteIfExists(p)
          })
      finally paths.close()
