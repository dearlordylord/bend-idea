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

/** Installed lint tools consume the same closed source snapshot as checking.
  * Lint findings never change the selected compiler's verdict.
  */
object BendLintBackend:
  private def program(path: String): Boolean =
    path
      .replace('\\', '/')
      .contains("/cache/bend-lint/server/programs/") && path.endsWith(".js")

  private def content(path: String): String =
    val file = Path.of(path)
    require(Files.isRegularFile(file) && Files.size(file) <= 32 * 1024 * 1024)
    java.security.MessageDigest
      .getInstance("SHA-256")
      .digest(Files.readAllBytes(file))
      .map(byte => f"${byte & 0xff}%02x")
      .mkString

  private def observed(path: String): String =
    if Files.isDirectory(Path.of(path)) then
      "inventory:" + BendLintInstallation.inventoryStamp(Path.of(path))
    else if program(path) then
      "program:" + BendExternalInputs.stamp(path, None) + ":" + content(path)
    else
      BendExternalInputs.stamp(
        path,
        Option
          .when(Path.of(path).getFileName.toString == "ready.properties")(path)
      )

  private def matches(path: String, stamp: String): Boolean =
    try
      if stamp.startsWith("program:") then
        val parts = stamp.split(":")
        parts.length == 3 && (BendExternalInputs.stamp(path, None) == parts(
          1
        ) || content(path) == parts(2))
      else observed(path) == stamp
    catch case NonFatal(_) => false

  def current(result: BendLintResult): Boolean =
    result.observedFiles.forall { case (path, stamp) => matches(path, stamp) }

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
        "Install bend-lint in Bend settings, or select prepared tool paths."
      )
    try
      if canceled() then
        return BendLintResult(
          BendLintOutcome.Canceled,
          Nil,
          "bend-lint canceled."
        )
      val installation = Path.of(options.directory).toRealPath()
      val ready = new Properties()
      val readyPath = installation.resolve("ready.properties")
      require(
        Files.isRegularFile(readyPath) && Files.size(readyPath) <= 1024 * 1024,
        "Install the pinned bend-lint tools first."
      )
      val readyObservation = readyPath.toString -> observed(readyPath.toString)
      val input = Files.newInputStream(readyPath)
      try
        val bytes = input.readNBytes(1024 * 1024 + 1)
        require(
          bytes.length <= 1024 * 1024,
          "Prepared tool manifest is too large."
        )
        ready.load(new java.io.ByteArrayInputStream(bytes))
      finally input.close()
      if Path
          .of(options.bun)
          .toRealPath() != Path.of(ready.getProperty("bun")).toRealPath()
      then
        return unavailable(
          "Select the Bun executable validated for this lint installation."
        )
      val tracked = ready
        .stringPropertyNames()
        .asScala
        .toList
        .filter(_.startsWith("file."))
        .map(name =>
          BendLintInstallation
            .safePath(installation, name.stripPrefix("file."))
            .toString
        )
      require(
        tracked.nonEmpty && tracked.size <= 4096,
        "Invalid prepared tool inventory."
      )
      val observations =
        readyObservation :: (installation.toString :: options.bun :: tracked)
          .map(path => path -> observed(path))
      BendLintInstallation.validate(installation, canceled) match
        case Some(_) if canceled() =>
          return BendLintResult(
            BendLintOutcome.Canceled,
            Nil,
            "bend-lint canceled."
          )
        case Some(error) => return unavailable(error)
        case None        => ()
      if !observations.forall { case (path, stamp) => matches(path, stamp) }
      then
        return unavailable(
          "Installed lint inputs changed before execution; run it again."
        )
      // User imports are already rewritten to closed snapshot paths. Only the
      // prepared rule/API packages are visible through the lint package cache.
      val config = com.google.gson.JsonParser
        .parseString(BendLintInstallation.config)
        .getAsJsonObject
      val entries = new com.google.gson.JsonArray()
      entries.add(directory.relativize(root).toString.replace('\\', '/'))
      config.add("entries", entries)
      val _ =
        Files.writeString(directory.resolve("bend-lint.json"), config.toString)
      val guard = directory.resolve("idea-lint-offline.mjs")
      val resource = getClass.getResourceAsStream("/lint/offline.mjs")
      try
        val _ = Files.copy(resource, guard)
      finally resource.close()
      val isolated =
        environment ++ BendLintInstallation.environment(installation) ++ Map(
          "BEND_HUB" -> "file:///__bend_editor_offline__",
          "BUN_OPTIONS" -> "--preload=./idea-lint-offline.mjs"
        )
      val process = BendBoundedProcess.run(
        List(
          options.bun,
          installation.resolve("src/linter/seam.ts").toString,
          root.toString,
          "--json",
          "--jobs",
          "1"
        ),
        directory,
        isolated,
        timeoutMillis = 15000L,
        maxOutputBytes = 262144,
        canceled = canceled,
        stdoutOnly = true
      )
      val result = process match
        case BendProcessOutcome.Exited(code, output)
            if code >= 0 && code <= 2 =>
          BendLintProtocol
            .decode(output, code, mappings)
            .getOrElse(
              unavailable(
                "bend-lint returned invalid or excessive diagnostics: " + output
                  .take(2048)
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
        unavailable(
          "Installed lint inputs changed during execution; run it again."
        )
    catch
      case NonFatal(_) if canceled() =>
        BendLintResult(BendLintOutcome.Canceled, Nil, "bend-lint canceled.")
      case NonFatal(error) =>
        unavailable(
          "bend-lint unavailable: " + Option(error.getMessage).getOrElse(
            error.getClass.getSimpleName
          )
        )
