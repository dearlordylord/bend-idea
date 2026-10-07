package com.dearlordylord.bend.idea.adapters.cli

import com.dearlordylord.bend.idea.adapters.process.{
  BendBoundedProcess,
  BendProcessOutcome
}
import com.dearlordylord.bend.idea.adapters.structured.BendLintProtocol
import com.dearlordylord.bend.idea.analysis.model.*
import java.nio.file.{Files, Path}
import java.util.Properties
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

/** Optional read-only prototype. Runs in the check's existing closed snapshot;
  * never invokes --fix, installs packages or replaces the compiler verdict.
  */
object BendLintBackend:
  private lazy val pins: List[(String, String)] =
    val values = new Properties()
    val input = getClass.getResourceAsStream("/lint/pins.properties")
    try values.load(input)
    finally input.close()
    values
      .stringPropertyNames()
      .asScala
      .toList
      .sorted
      .map(name => name -> values.getProperty(name))

  private def observed(path: String): String =
    BendExternalInputs.stamp(path, None)

  def current(result: BendLintResult): Boolean =
    result.observedFiles.forall { case (path, stamp) =>
      observed(path) == stamp
    }

  def run(
      snapshot: BendCheckSnapshot,
      directory: Path,
      root: Path,
      environment: Map[String, String],
      mappings: List[BendSourceMapping],
      canceled: () => Boolean
  ): BendLintResult =
    def unavailable(details: String): BendLintResult =
      BendLintResult(BendLintOutcome.Unavailable, Nil, details)
    val options = snapshot.toolchain.lint
    if !options.enabled then
      return unavailable(
        "Enable bend-lint in Settings | Languages & Frameworks | Bend."
      )
    if options.bun.isEmpty || options.directory.isEmpty then
      return unavailable(
        "Configure the Bun executable and bend-lint checkout in Bend settings."
      )
    try
      val checkout = Path.of(options.directory).toRealPath()
      val compiler =
        Path.of(snapshot.toolchain.baseSource).toRealPath().getParent
      val files = pins.map { case (name, hash) =>
        val path = if name.startsWith("lint.") then
          checkout.resolve(name.stripPrefix("lint."))
        else compiler.resolve(name.stripPrefix("compiler."))
        path -> hash
      }
      if !Files.isExecutable(Path.of(options.bun)) then
        return unavailable("The configured Bun executable is unavailable.")
      val observations = (options.bun :: files.map(_._1.toString)).map(path =>
        path -> observed(path)
      )
      val compatible = files.forall { case (path, hash) =>
        Files.isRegularFile(path) && Files.size(path) <= 2 * 1024 * 1024 &&
        java.security.MessageDigest
          .getInstance("SHA-256")
          .digest(Files.readAllBytes(path))
          .map(b => f"${b & 0xff}%02x")
          .mkString == hash
      }
      if !compatible then
        return unavailable(
          "Unsupported bend-lint/compiler sources. This prototype requires the revisions documented in README."
        )
      // No discovery from a temporary directory or implicit project-code loading.
      val config = directory.resolve("bend-lint.json")
      Files.writeString(config, "{\"rules\":{}}")
      val semantic = if options.semanticObservations then
        val target = directory.resolve("idea-observations.mjs")
        val input = getClass.getResourceAsStream("/lint/observations.mjs")
        try Files.copy(input, target)
        finally input.close()
        List("--rules", target.toString)
      else Nil
      val command = List(
        options.bun,
        checkout.resolve("src/lint.ts").toString,
        root.toString,
        "--bend",
        compiler.toString,
        "--config",
        config.toString,
        "--json",
        "--rules",
        checkout.resolve("rules/trailing_whitespace.ts").toString
      ) ++ semantic
      val process = BendBoundedProcess.run(
        command,
        directory,
        environment,
        timeoutMillis = 15000L,
        maxOutputBytes = 262144,
        canceled = canceled
      )
      val result = process match
        case BendProcessOutcome.Exited(code, output)
            if code == 0 || code == 1 =>
          BendLintProtocol
            .decode(output, code, mappings)
            .getOrElse(
              unavailable(
                "bend-lint returned invalid or excessive diagnostics."
              )
            )
        case BendProcessOutcome.Exited(_, output) =>
          unavailable("bend-lint failed: " + output.take(2048))
        case BendProcessOutcome.TimedOut(_) =>
          BendLintResult(BendLintOutcome.TimedOut, Nil, "bend-lint timed out.")
        case BendProcessOutcome.Canceled(_) =>
          BendLintResult(BendLintOutcome.Canceled, Nil, "bend-lint canceled.")
        case BendProcessOutcome.OutputLimit(_) =>
          unavailable("bend-lint output exceeded the 256 KiB limit.")
        case BendProcessOutcome.StartFailed(message) =>
          unavailable("Could not start bend-lint: " + message)
      val captured = result.copy(observedFiles = observations)
      if current(captured) then captured
      else
        unavailable("bend-lint inputs changed during execution; run it again.")
    catch
      case NonFatal(error) =>
        unavailable(
          "bend-lint unavailable: " + Option(error.getMessage).getOrElse(
            error.getClass.getSimpleName
          )
        )
