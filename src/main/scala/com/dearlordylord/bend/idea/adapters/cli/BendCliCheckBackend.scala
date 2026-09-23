package com.dearlordylord.bend.idea.adapters.cli

import com.dearlordylord.bend.idea.adapters.process.{BendBoundedProcess, BendProcessOutcome}
import com.dearlordylord.bend.idea.analysis.api.BendCheckPolicy
import com.dearlordylord.bend.idea.analysis.model.*
import com.dearlordylord.bend.idea.analysis.ports.BendCheckBackend
import com.dearlordylord.bend.idea.workspace.api.BendImportLines
import java.nio.file.{Files, Path}
import java.nio.charset.StandardCharsets
import scala.jdk.CollectionConverters.*

/** Text CLI backend. The only source command it can construct is --check-only. */
final class BendCliCheckBackend(tempParent: Path = Path.of(System.getProperty("java.io.tmpdir")))
    extends BendCheckBackend:
  override def check(snapshot: BendCheckSnapshot, canceled: () => Boolean): BendCheckResult =
    val importsBase = BendImportLines.parse(snapshot.text).exists(_.spelling == "Base")
    val basePath = Option.when(importsBase)(snapshot.toolchain.baseSource)
    var key = BendAnalysisKey.from(snapshot).copy(basePath = basePath,
      externalStamp = BendExternalInputs.stamp(snapshot.toolchain.executable, basePath))
    def result(outcome: BendCheckOutcome, completeness: BendCompleteness,
        reliance: BendReliance, details: String, location: BendLocation = BendLocation.RootOnly): BendCheckResult =
      BendCheckResult(key, outcome, completeness, reliance,
        if details.isEmpty then Nil else List(BendDiagnostic(details, location)), details)
    val executable = snapshot.toolchain.executable
    val path = try Path.of(executable) catch case _: Exception =>
      return result(BendCheckOutcome.Unavailable, BendCompleteness.Unknown, BendReliance.Unknown,
        "Invalid Bend executable path. Configure it in Settings | Bend.")
    if !Files.isRegularFile(path) || !Files.isExecutable(path) then
      return result(BendCheckOutcome.Unavailable, BendCompleteness.Unknown, BendReliance.Unknown,
        "Bend compiler is missing or not executable. Configure it in Settings | Bend.")
    val environment = Map("BEND_NO_TELEMETRY" -> "1", "NO_COLOR" -> "1")
    var observed = ""
    // A help probe must precede every source invocation, including a potentially
    // side-effecting main on an older compiler that ignores unknown switches.
    BendBoundedProcess.run(List(executable, "--help"), path.getParent, environment,
      canceled = canceled) match
      case BendProcessOutcome.Exited(0, output) if
          output.matches("(?s).*bend <file\\.bend> --check-only check the file and its imports; run nothing.*") =>
        observed += ":" + BendAnalysisKey.sourceDigest(output)
      case BendProcessOutcome.TimedOut(_) =>
        return result(BendCheckOutcome.TimedOut, BendCompleteness.Unknown, BendReliance.Unknown,
          "Bend capability probe timed out; no source was checked.")
      case BendProcessOutcome.Canceled(_) =>
        return result(BendCheckOutcome.Unavailable, BendCompleteness.Unknown, BendReliance.Unknown,
          "Bend check canceled.")
      case _ =>
        return result(BendCheckOutcome.Unavailable, BendCompleteness.Unknown, BendReliance.Unknown,
          "This Bend compiler does not document --check-only; no source was checked.")
    BendCheckPolicy.unsupportedImport(snapshot) match
      case Some(message) =>
        return result(BendCheckOutcome.Unavailable, BendCompleteness.Unknown, BendReliance.Unknown, message)
      case None => ()
    if importsBase then
      val configured = try Files.readString(Path.of(snapshot.toolchain.baseSource), StandardCharsets.UTF_8)
        catch case _: Exception =>
          return result(BendCheckOutcome.Unavailable, BendCompleteness.Unknown, BendReliance.Unknown,
            "The configured Base source is unavailable.")
      if BendImportLines.parse(configured).nonEmpty then
        return result(BendCheckOutcome.Unavailable, BendCompleteness.Unknown, BendReliance.Unknown,
          "Base imports dependencies; a closed offline snapshot is unavailable.")
      BendBoundedProcess.run(List(executable, "base"), path.getParent, environment,
        canceled = canceled) match
        case BendProcessOutcome.Exited(0, compilerBase) if compilerBase == configured =>
          observed += ":" + BendAnalysisKey.sourceDigest(configured)
        case BendProcessOutcome.TimedOut(_) =>
          return result(BendCheckOutcome.TimedOut, BendCompleteness.Unknown, BendReliance.Unknown,
            "Bend Base compatibility probe timed out; no source was checked.")
        case BendProcessOutcome.Canceled(_) =>
          return result(BendCheckOutcome.Unavailable, BendCompleteness.Unknown, BendReliance.Unknown,
            "Bend check canceled.")
        case _ =>
          return result(BendCheckOutcome.Unavailable, BendCompleteness.Unknown, BendReliance.Unknown,
            "Configured Base does not match this compiler's Base; no source was checked.")
    key = BendAnalysisKey.from(snapshot.copy(inputFingerprint =
      BendAnalysisKey.sourceDigest(snapshot.inputFingerprint + observed))).copy(
      basePath = basePath, externalStamp = key.externalStamp)
    var temp: Path = null
    try
      temp = Files.createTempDirectory(tempParent, "bend-editor-check-")
      val name = Path.of(snapshot.path).getFileName.toString
      if !name.endsWith(".bend") then
        return result(BendCheckOutcome.Unavailable, BendCompleteness.Unknown, BendReliance.Unknown,
          "Check Current File needs a .bend source file.")
      val copied = temp.resolve(name)
      Files.writeString(copied, snapshot.text, StandardCharsets.UTF_8)
      // A copied PROOF must retain the compiler's sibling-LAWS presence guard.
      // Its dependency cannot be snapshotted safely until graph checks arrive.
      if name == "PROOF.bend" && Files.exists(Path.of(snapshot.path).resolveSibling("LAWS.bend")) then
        return result(BendCheckOutcome.Unavailable, BendCompleteness.Unknown, BendReliance.Unknown,
          "PROOF.bend has a sibling LAWS.bend; checking it needs a complete graph snapshot.")
      val offline = temp.resolve("offline-lib")
      Files.createDirectory(offline)
      val isolated = environment ++ Map("BEND_LIB" -> offline.toString,
        "BEND_HUB" -> "file:///__bend_editor_offline__")
      BendBoundedProcess.run(List(executable, copied.toString, "--check-only"), temp, isolated,
        canceled = canceled) match
        case BendProcessOutcome.Exited(0, output) if output.contains("All terms check") =>
          val relied = if output.contains("relies on unsafe or foreign code") ||
              output.contains("rely on unsafe or foreign code") then BendReliance.UnsafeOrForeign
            else BendReliance.None
          BendCheckResult(key, BendCheckOutcome.Success, BendCompleteness.Complete,
            relied, Nil, output)
        case BendProcessOutcome.Exited(0, output) =>
          result(BendCheckOutcome.Unavailable, BendCompleteness.Unknown, BendReliance.Unknown,
            "Compiler returned success without a check verdict.\n" + output)
        case BendProcessOutcome.Exited(_, output) =>
          val clean = output.replaceAll("\\u001b\\[[0-9;]*m", "").trim
          val incomplete = clean.matches("(?s).*\\b[0-9]+ TODOs? found\\..*")
          val line = reliableLine(clean, snapshot.text)
          result(BendCheckOutcome.Failed,
            if incomplete then BendCompleteness.Incomplete else BendCompleteness.Unknown,
            BendReliance.Unknown, if clean.isEmpty then "Bend failed without output." else clean, line)
        case BendProcessOutcome.TimedOut(output) =>
          result(BendCheckOutcome.TimedOut, BendCompleteness.Unknown, BendReliance.Unknown,
            "Bend check timed out.\n" + output)
        case BendProcessOutcome.OutputLimit(output) =>
          result(BendCheckOutcome.Unavailable, BendCompleteness.Unknown, BendReliance.Unknown,
            "Bend check output exceeded the limit.\n" + output)
        case BendProcessOutcome.StartFailed(message) =>
          result(BendCheckOutcome.Unavailable, BendCompleteness.Unknown, BendReliance.Unknown, message)
        case BendProcessOutcome.Canceled(_) =>
          result(BendCheckOutcome.Unavailable, BendCompleteness.Unknown, BendReliance.Unknown,
            "Bend check canceled.")
    catch
      case e: java.io.IOException =>
        result(BendCheckOutcome.Unavailable, BendCompleteness.Unknown, BendReliance.Unknown,
          "Could not prepare Bend source snapshot: " + e.getMessage)
    finally
      if temp != null then
        val files = Files.walk(temp)
        try files.sorted(java.util.Comparator.reverseOrder()).forEach(p => Files.deleteIfExists(p))
        finally files.close()

  private def reliableLine(output: String, source: String): BendLocation =
    val lines = source.split("\n", -1)
    val Marked = "(?m)^\\s*([0-9]+)>\\| ?(.*)$".r
    val candidates = Marked.findAllMatchIn(output).toList
    if candidates.size != 1 then BendLocation.RootOnly
    else
      val line = candidates.head.group(1).toInt - 1
      if line >= 0 && line < lines.length &&
          lines(line).trim == candidates.head.group(2).trim then BendLocation.Line(line)
      else BendLocation.RootOnly
