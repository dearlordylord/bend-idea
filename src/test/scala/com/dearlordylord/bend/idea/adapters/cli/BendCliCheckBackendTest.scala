package com.dearlordylord.bend.idea.adapters.cli

import com.dearlordylord.bend.idea.analysis.model.*
import com.dearlordylord.bend.idea.model.FileId
import com.dearlordylord.bend.idea.toolchain.api.BendToolchainSelection
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.{Files, Path}
import java.util.concurrent.atomic.AtomicBoolean

/** Uses the pinned compiler source via Bun; the installed older binary is a negative probe. */
final class BendCliCheckBackendTest:
  private val pinned = Path.of(".references/bend/bend2/main.ts").toAbsolutePath.normalize()
  private val base = pinned.resolveSibling("base.bend")

  private def withCompiler(run: Path => Unit): Unit =
    val directory = Files.createTempDirectory("bend-check-test-")
    try
      val executable = directory.resolve("bend")
      Files.writeString(executable,
        "#!/bin/sh\nexec npx --yes bun '" + pinned + "' \"$@\"\n")
      executable.toFile.setExecutable(true)
      run(executable)
    finally
      val paths = Files.walk(directory)
      try paths.sorted(java.util.Comparator.reverseOrder()).forEach(p => Files.deleteIfExists(p))
      finally paths.close()

  private def snapshot(executable: Path, text: String, original: Path): BendCheckSnapshot =
    BendCheckSnapshot(new FileId(original.toString, false), original.toString,
      text, 4L, BendToolchainSelection(executable.toString, base.toString, "", true, 1L))

  private def backend(executable: Path): BendCliCheckBackend =
    new BendCliCheckBackend(executable.getParent)

  private def tempCount(directory: Path): Long =
    val paths = Files.list(directory)
    try paths.filter(_.getFileName.toString.startsWith("bend-editor-check-")).count()
    finally paths.close()

  @Test def sideEffectingMainIsNeverRunAndSnapshotIsRemoved(): Unit = withCompiler { executable =>
    val original = executable.getParent.resolve("main.bend")
    Files.writeString(original, "import Base\ndef main() -> U32:\n  1\n")
    val before = tempCount(executable.getParent)
    val checked = backend(executable).check(snapshot(executable,
      "import Base\ndef main() -> IO(Unit):\n  do IO<Unit>:\n    IO.print(\"SIDE_EFFECT_MARKER\")\n", original))
    assertEquals(BendCheckOutcome.Success, checked.outcome)
    assertEquals(BendCompleteness.Complete, checked.completeness)
    assertFalse(checked.details.contains("SIDE_EFFECT_MARKER"))
    assertEquals("The original disk file must remain unchanged", "import Base\ndef main() -> U32:\n  1\n",
      Files.readString(original))
    assertEquals(before, tempCount(executable.getParent))
  }

  @Test def unsavedErrorHasFullDetailsAndConservativeLine(): Unit = withCompiler { executable =>
    val original = executable.getParent.resolve("bad.bend")
    Files.writeString(original, "import Base\ndef main() -> U32:\n  0\n")
    val checked = backend(executable).check(snapshot(executable,
      "import Base\ndef main() -> U32:\n  missing_name\n", original))
    assertEquals(BendCheckOutcome.Failed, checked.outcome)
    assertTrue(checked.details.contains("missing_name"))
    assertEquals(BendLocation.Line(2), checked.diagnostics.head.location)
    assertEquals("import Base\ndef main() -> U32:\n  0\n", Files.readString(original))
  }

  @Test def todoIsIncompleteAndOtherImportsAreUnavailable(): Unit = withCompiler { executable =>
    val original = executable.getParent.resolve("todo.bend")
    val checked = backend(executable).check(snapshot(executable,
      "import Base\ndef main() -> U32:\n  ?TODO\n", original))
    assertEquals(BendCompleteness.Incomplete, checked.completeness)
    assertEquals(BendCheckOutcome.Failed, checked.outcome)
    assertEquals("Check incomplete", checked.status)
    assertTrue(checked.details.contains("TODO"))
    val unsupported = backend(executable).check(snapshot(executable,
      "import 0xabcdef/foo.bend as Foo\ndef main() -> U32:\n  0\n", original))
    assertEquals(BendCheckOutcome.Unavailable, unsupported.outcome)
    assertTrue(unsupported.details.contains("complete graph snapshots"))
  }

  @Test def unsafeRelianceDoesNotEraseSuccessfulCompleteness(): Unit = withCompiler { executable =>
    val original = executable.getParent.resolve("unsafe.bend")
    val checked = backend(executable).check(snapshot(executable,
      "import Base\n@unsafe def main() -> U32:\n  0\n", original))
    assertEquals(BendCheckOutcome.Success, checked.outcome)
    assertEquals(BendCompleteness.Complete, checked.completeness)
    assertEquals(BendReliance.UnsafeOrForeign, checked.reliance)
    assertTrue(checked.status.contains("unsafe or foreign"))
  }

  @Test def noImportSourceChecksAgainstItsOwnSnapshot(): Unit = withCompiler { executable =>
    val original = executable.getParent.resolve("plain.bend")
    val checked = backend(executable).check(snapshot(executable,
      "def main() -> Type:\n  Type\n", original))
    assertEquals(BendCheckOutcome.Success, checked.outcome)
    assertEquals(BendCompleteness.Complete, checked.completeness)
  }

  @Test def mismatchedBaseIsUnavailableBeforeSourceInvocation(): Unit = withCompiler { executable =>
    val substitute = executable.getParent.resolve("base.bend")
    Files.writeString(substitute, "# a different Base\n")
    val request = snapshot(executable, "import Base\ndef main() -> U32:\n  0\n",
      executable.getParent.resolve("main.bend"))
    val checked = backend(executable).check(request.copy(toolchain =
      request.toolchain.copy(baseSource = substitute.toString)))
    assertEquals(BendCheckOutcome.Unavailable, checked.outcome)
    assertTrue(checked.details.contains("does not match"))
  }

  @Test def baseWithDependenciesIsExplicitlyUnavailableOffline(): Unit = withCompiler { executable =>
    val substitute = executable.getParent.resolve("base-with-import.bend")
    Files.writeString(substitute, "import 0xabcdef/module.bend as M\n")
    val request = snapshot(executable, "import Base\ndef main() -> U32:\n  0\n",
      executable.getParent.resolve("main.bend"))
    val checked = backend(executable).check(request.copy(toolchain =
      request.toolchain.copy(baseSource = substitute.toString)))
    assertEquals(BendCheckOutcome.Unavailable, checked.outcome)
    assertTrue(checked.details.contains("closed offline snapshot"))
  }

  @Test def missingAndUnsupportedCompilerNeverCheckSource(): Unit = withCompiler { executable =>
    val original = executable.getParent.resolve("main.bend")
    val source = "import Base\ndef main() -> U32:\n  0\n"
    val missing = backend(executable).check(snapshot(executable.resolveSibling("missing"), source, original))
    assertEquals(BendCheckOutcome.Unavailable, missing.outcome)
    val installed = Path.of(System.getProperty("user.home"), ".bend", "bin", "bend")
    if Files.isExecutable(installed) then
      val unsupported = backend(executable).check(snapshot(installed, source, original))
      assertEquals(BendCheckOutcome.Unavailable, unsupported.outcome)
      assertTrue(unsupported.details.contains("--check-only"))
  }

  @Test def cancellationRemovesSnapshotAndTerminatesSourceProcess(): Unit =
    val directory = Files.createTempDirectory("bend-check-cancel-")
    try
      val executable = directory.resolve("bend")
      Files.writeString(executable,
        "#!/bin/sh\nif [ \"$1\" = \"--help\" ]; then echo 'bend <file.bend> --check-only check the file and its imports; run nothing'; exit 0; fi\nsleep 10\n")
      executable.toFile.setExecutable(true)
      val canceled = new AtomicBoolean(false)
      val signal = new Thread(() => { Thread.sleep(150); canceled.set(true) })
      val before = tempCount(executable.getParent)
      signal.start()
      val checked = backend(executable).check(snapshot(executable,
        "def main() -> Type:\n  Type\n", directory.resolve("main.bend")), () => canceled.get())
      signal.join()
      assertEquals(BendCheckOutcome.Unavailable, checked.outcome)
      assertTrue(checked.details.contains("canceled"))
      assertEquals(before, tempCount(executable.getParent))
    finally
      val paths = Files.walk(directory)
      try paths.sorted(java.util.Comparator.reverseOrder()).forEach(p => Files.deleteIfExists(p))
      finally paths.close()
