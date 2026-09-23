package com.dearlordylord.bend.idea.adapters.cli

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.concurrent.TimeUnit

/** Test-only access to the compiler and runtime explicitly supplied to the real compiler fixtures. */
object RealBendCompilerFixture:
  final case class Inputs(compilerDirectory: Path, bunExecutable: Path, main: Path, base: Path,
      bendCommit: String, bunVersion: String, bunRevision: String):
    /** Writes a shell adapter that forwards every argument to the supplied compiler and Bun. */
    def writeLauncher(path: Path, beforeSource: String = ""): Path =
      val prelude = Option.when(beforeSource.nonEmpty)(beforeSource.trim).toList
      val lines = List(
        "#!/bin/sh",
        "set -eu",
        "if [ \"${1:-}\" = \"--help\" ]; then",
        "  exec \"$BEND_TEST_BUN\" \"$BEND_TEST_COMPILER_DIR/bend2/main.ts\" --help",
        "fi") ++ prelude ++ List(
        "exec \"$BEND_TEST_BUN\" \"$BEND_TEST_COMPILER_DIR/bend2/main.ts\" \"$@\"")
      Files.writeString(path, lines.mkString("\n") + "\n", StandardCharsets.UTF_8)
      require(path.toFile.setExecutable(true), s"Could not mark Bend test launcher executable: $path")
      path

  private lazy val verified: Inputs = loadInputs()

  def inputs: Inputs = verified

  def writeLauncher(path: Path, beforeSource: String = ""): Path =
    verified.writeLauncher(path, beforeSource)

  def markAndSleep(marker: Path, seconds: Int): String =
    require(seconds > 0 && seconds <= 10, s"Unexpected test delay: $seconds")
    s"printf x >> ${shellQuote(marker.toAbsolutePath.normalize().toString)}\nsleep $seconds"

  private def loadInputs(): Inputs =
    val compilerDirectory = suppliedPath("BEND_TEST_COMPILER_DIR").toRealPath()
    val bunExecutable = suppliedPath("BEND_TEST_BUN").toRealPath()
    val expectedBendCommit = requiredProperty("bend.test.expectedBendCommit")
    val expectedBunVersion = requiredProperty("bend.test.expectedBunVersion")
    val expectedBunRevision = requiredProperty("bend.test.expectedBunRevision")

    val gitRoot = process(List("git", "-C", compilerDirectory.toString, "rev-parse", "--show-toplevel"))
    require(Path.of(gitRoot).toRealPath() == compilerDirectory,
      s"BEND_TEST_COMPILER_DIR must name the Bend checkout root; observed $gitRoot")
    val bendCommit = process(List("git", "-C", compilerDirectory.toString,
      "rev-parse", "--verify", "HEAD"))
    require(bendCommit == expectedBendCommit,
      s"Bend revision mismatch: expected $expectedBendCommit, observed $bendCommit")
    val changed = process(List("git", "-C", compilerDirectory.toString,
      "status", "--porcelain", "--untracked-files=all"), allowEmpty = true)
    require(changed.isEmpty, s"Bend checkout has tracked or untracked changes: $compilerDirectory\n$changed")

    val main = compilerDirectory.resolve("bend2/main.ts")
    val base = compilerDirectory.resolve("bend2/base.bend")
    require(Files.isRegularFile(main), s"Bend CLI is missing: $main")
    require(Files.isRegularFile(base), s"Bend Base is missing: $base")
    require(Files.isExecutable(bunExecutable), s"BEND_TEST_BUN is not executable: $bunExecutable")
    val bunVersion = process(List(bunExecutable.toString, "--version"))
    require(bunVersion == expectedBunVersion,
      s"Bun version mismatch: expected $expectedBunVersion, observed $bunVersion")
    val bunRevision = process(List(bunExecutable.toString, "--revision"))
    require(bunRevision == expectedBunRevision,
      s"Bun revision mismatch: expected $expectedBunRevision, observed $bunRevision")

    System.out.println(s"Real Bend test inputs: commit=$bendCommit checkout=$compilerDirectory " +
      s"bun=$bunVersion revision=$bunRevision executable=$bunExecutable")
    Inputs(compilerDirectory, bunExecutable, main, base, bendCommit, bunVersion, bunRevision)

  private def suppliedPath(name: String): Path =
    val value = Option(System.getenv(name)).map(_.trim).filter(_.nonEmpty).getOrElse {
      throw new IllegalStateException(s"$name is required by real Bend tests; provision the pinned inputs described in README.md")
    }
    val path = Path.of(value)
    require(path.isAbsolute, s"$name must be absolute: $value")
    require(Files.exists(path), s"$name does not exist: $value")
    path

  private def requiredProperty(name: String): String =
    Option(System.getProperty(name)).map(_.trim).filter(_.nonEmpty).getOrElse {
      throw new IllegalStateException(s"Missing Gradle test pin '$name'; run the real Bend checks through ./gradlew")
    }

  private def process(command: List[String], allowEmpty: Boolean = false): String =
    val capture = Files.createTempFile("bend-test-input-check-", ".log")
    try
      val process = new ProcessBuilder(command*).redirectErrorStream(true)
        .redirectOutput(capture.toFile).start()
      if !process.waitFor(15, TimeUnit.SECONDS) then
        process.destroyForcibly()
        throw new IllegalStateException(s"Timed out verifying real Bend test input: ${command.mkString(" ")}")
      val output = Files.readString(capture, StandardCharsets.UTF_8).trim
      require(process.exitValue() == 0,
        s"Failed to verify real Bend test input (${command.mkString(" ")}): $output")
      if !allowEmpty then
        require(output.nonEmpty, s"Empty output verifying real Bend test input: ${command.mkString(" ")}")
      output
    finally Files.deleteIfExists(capture)

  private def shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
