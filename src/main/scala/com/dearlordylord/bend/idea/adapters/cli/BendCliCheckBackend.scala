package com.dearlordylord.bend.idea.adapters.cli

import com.dearlordylord.bend.idea.adapters.process.{BendBoundedProcess, BendProcessOutcome}
import com.dearlordylord.bend.idea.analysis.api.BendCheckPolicy
import com.dearlordylord.bend.idea.analysis.model.*
import com.dearlordylord.bend.idea.analysis.ports.BendCheckBackend
import com.dearlordylord.bend.idea.workspace.api.BendImportLines
import com.dearlordylord.bend.idea.workspace.model.*
import com.dearlordylord.bend.idea.model.FileId
import java.nio.file.{Files, Path}
import java.nio.charset.StandardCharsets
import scala.jdk.CollectionConverters.*
import scala.collection.mutable

/** Text CLI backend. The only source command it can construct is --check-only. */
final class BendCliCheckBackend(tempParent: Path = Path.of(System.getProperty("java.io.tmpdir")))
    extends BendCheckBackend:
  override def check(snapshot: BendCheckSnapshot, canceled: () => Boolean): BendCheckResult =
    val basePath = snapshot.selectedBasePath
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
    if snapshot.graph.isEmpty then
      BendCheckPolicy.unsupportedImport(snapshot) match
        case Some(message) =>
          return result(BendCheckOutcome.Unavailable, BendCompleteness.Unknown, BendReliance.Unknown, message)
        case None => ()
    if basePath.nonEmpty then
      val capturedBase = snapshot.graph.flatMap { graph =>
        graph.edges.find(_.importLine.spelling == "Base").flatMap(_.target)
          .flatMap(graph.source).map(_.text)
      }
      val configured = try capturedBase.getOrElse(
        Files.readString(Path.of(snapshot.toolchain.baseSource), StandardCharsets.UTF_8))
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
    key = key.copy(inputFingerprint =
      BendAnalysisKey.sourceDigest(snapshot.inputFingerprint + observed))
    if snapshot.graph.nonEmpty then
      return checkGraph(snapshot, key, executable, path, environment, canceled)
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

  private def checkGraph(snapshot: BendCheckSnapshot, key: BendAnalysisKey,
      executable: String, executablePath: Path, environment: Map[String, String],
      canceled: () => Boolean): BendCheckResult =
    val graph = snapshot.graph.get
    val sources = graph.files.map(file => BendCheckedSource(file.source.id, file.source.path,
      file.source.text, file.source.revision, file.namespace))
    def result(outcome: BendCheckOutcome, completeness: BendCompleteness,
        details: String, diagnostics: List[BendDiagnostic] = Nil,
        mappings: List[BendSourceMapping] = Nil, reliance: BendReliance = BendReliance.Unknown): BendCheckResult =
      BendCheckResult(key, outcome, completeness, reliance, diagnostics, details,
        sources = sources, mappings = mappings)
    if graph.root != snapshot.root || graph.source(snapshot.root).forall(_.text != snapshot.text) then
      return result(BendCheckOutcome.Unavailable, BendCompleteness.Unknown,
        "The captured Bend graph does not match the selected root.")
    val selectedBaseIds = graph.edges.filter(_.importLine.spelling == "Base")
      .flatMap(_.target).toSet
    if graph.edges.exists(edge => edge.importLine.spelling != "Base" &&
        edge.target.exists(selectedBaseIds)) then
      return result(BendCheckOutcome.Unavailable, BendCompleteness.Unknown,
        "The selected Base is also imported by path. Its identity relative to the compiler's built-in Base cannot be verified in an isolated snapshot.")
    if graph.problems.nonEmpty then
      val diagnostics = graph.problems.map { problem =>
        val (from, imp, detail) = problem match
          case BendGraphProblem.InvalidImport(file, line, why) => (file, line, why)
          case BendGraphProblem.Missing(file, line, path) =>
            (file, line, s"Cannot read imported module: ${line.spelling} ($path)")
          case BendGraphProblem.Cycle(file, line, _) =>
            (file, line, s"Import cycle through ${line.spelling}")
          case BendGraphProblem.NamespaceConflict(file, line, _, first, next) =>
            (file, line, s"One namespace per file: '$first' and '$next'")
        val source = graph.source(from)
        val number = source.fold(0)(s => s.text.take(imp.offset).count(_ == '\n'))
        BendDiagnostic(detail, BendLocation.SourceLine(from, number))
      }
      return result(BendCheckOutcome.Failed, BendCompleteness.Unknown,
        diagnostics.map(_.message).mkString("\n"), diagnostics)
    var temp: Path = null
    try
      temp = Files.createTempDirectory(tempParent, "bend-editor-check-")
      val offline = temp.resolve("offline-lib")
      Files.createDirectory(offline)
      val rootPath = temp.resolve("root").resolve(Path.of(snapshot.path).getFileName.toString)
      val lawId = snapshot.siblingLaws.map(_.id)
      // import Base is never redirected: Bend reads its own built-in BASE_BEND.
      // Keep Base in checked-source provenance but do not claim a temp copy was
      // compiler input.
      val baseIds = graph.edges.filter(_.importLine.spelling == "Base")
        .flatMap(_.target).toSet - snapshot.root
      val materialized = graph.files.filterNot(file => baseIds(file.source.id))
      val copied = materialized.map { file =>
        val target = if file.source.id == snapshot.root then rootPath
          else if Path.of(snapshot.path).getFileName.toString == "PROOF.bend" &&
              lawId.contains(file.source.id) then rootPath.resolveSibling("LAWS.bend")
          else
            val digest = BendAnalysisKey.sourceDigest(file.source.id.value).take(24)
            temp.resolve(digest).resolve(Path.of(file.source.path).getFileName.toString)
        file.source.id -> target
      }.toMap
      val mappings = materialized.map { file =>
        val original = file.source.text
        val imports = graph.edges.filter(_.from == file.source.id)
          .filterNot(_.importLine.spelling == "Base")
        val rewrites = imports.flatMap(edge => edge.target.flatMap(copied.get).map { target =>
          val sourceFile = copied(file.source.id)
          val relative = sourceFile.getParent.relativize(target).toString.replace('\\', '/')
          edge.importLine -> (if relative.startsWith(".") then relative else "./" + relative)
        })
        val replacements = rewrites.map { case (imp, value) =>
          val start = original.indexOf(imp.spelling, imp.offset)
          (start, imp.spelling.length, value)
        }.filter(_._1 >= 0).sortBy(-_._1)
        val (ranges, _) = replacements.reverse.foldLeft((List.empty[BendRewrittenRange], 0)) {
          case ((seen, shift), (start, length, value)) =>
            val copiedStart = start + shift
            (seen :+ BendRewrittenRange(start, start + length, copiedStart,
              copiedStart + value.length), shift + value.length - length)
        }
        val rewritten = replacements.foldLeft(original) { case (text, (start, length, value)) =>
          text.substring(0, start) + value + text.substring(start + length)
        }
        val rewrittenLines = replacements.map { case (start, _, _) =>
          original.take(start).count(_ == '\n')
        }.toSet
        val destination = copied(file.source.id)
        Files.createDirectories(destination.getParent)
        Files.writeString(destination, rewritten, StandardCharsets.UTF_8)
        val lines = rewritten.split("\n", -1)
        val blanked = BendImportLines.parse(rewritten).map { imp =>
          val number = rewritten.take(imp.offset).count(_ == '\n')
          val start = lines.take(number).map(_.length + 1).sum
          (start, start + lines(number).length)
        }
        val compilerText = lines.zipWithIndex.map { case (line, number) =>
          if blanked.exists { case (start, _) =>
            start == lines.take(number).map(_.length + 1).sum
          } then "" else line
        }.mkString("\n")
        BendSourceMapping(file.source.id, file.source.path, destination.toString,
          file.namespace, original, rewritten, rewrittenLines, ranges,
          compilerText, blanked)
      }
      if rootPath.getFileName.toString == "PROOF.bend" then
        snapshot.siblingLaws.foreach { law =>
          val destination = rootPath.resolveSibling("LAWS.bend")
          if !Files.exists(destination) then
            Files.writeString(destination, law.text, StandardCharsets.UTF_8)
        }
      if canceled() then return result(BendCheckOutcome.Unavailable, BendCompleteness.Unknown,
        "Bend check canceled.", mappings = mappings)
      val isolated = environment ++ Map("BEND_LIB" -> offline.toString,
        "BEND_HUB" -> "file:///__bend_editor_offline__")
      BendBoundedProcess.run(List(executable, rootPath.toString, "--check-only"), temp,
        isolated, canceled = canceled) match
        case BendProcessOutcome.Exited(0, output) if output.contains("All terms check") =>
          val relied = if output.contains("relies on unsafe or foreign code") ||
              output.contains("rely on unsafe or foreign code") then BendReliance.UnsafeOrForeign
            else BendReliance.None
          result(BendCheckOutcome.Success, BendCompleteness.Complete, output,
            mappings = mappings, reliance = relied)
        case BendProcessOutcome.Exited(0, output) =>
          result(BendCheckOutcome.Unavailable, BendCompleteness.Unknown,
            "Compiler returned success without a check verdict.\n" + output, mappings = mappings)
        case BendProcessOutcome.Exited(_, output) =>
          val clean = output.replaceAll("\\u001b\\[[0-9;]*m", "").trim
          val incomplete = clean.matches("(?s).*\\b[0-9]+ TODOs? found\\..*")
          val location = graphLocation(clean, mappings, snapshot.root)
          result(BendCheckOutcome.Failed,
            if incomplete then BendCompleteness.Incomplete else BendCompleteness.Unknown,
            if clean.isEmpty then "Bend failed without output." else clean,
            List(BendDiagnostic(if clean.isEmpty then "Bend failed without output." else clean, location)),
            mappings)
        case BendProcessOutcome.TimedOut(output) =>
          result(BendCheckOutcome.TimedOut, BendCompleteness.Unknown,
            "Bend check timed out.\n" + output, mappings = mappings)
        case BendProcessOutcome.OutputLimit(output) =>
          result(BendCheckOutcome.Unavailable, BendCompleteness.Unknown,
            "Bend check output exceeded the limit.\n" + output, mappings = mappings)
        case BendProcessOutcome.StartFailed(message) =>
          result(BendCheckOutcome.Unavailable, BendCompleteness.Unknown, message, mappings = mappings)
        case BendProcessOutcome.Canceled(_) =>
          result(BendCheckOutcome.Unavailable, BendCompleteness.Unknown,
            "Bend check canceled.", mappings = mappings)
    catch
      case e: java.io.IOException =>
        result(BendCheckOutcome.Unavailable, BendCompleteness.Unknown,
          "Could not prepare Bend graph snapshot: " + e.getMessage)
    finally
      if temp != null then
        val files = Files.walk(temp)
        try files.sorted(java.util.Comparator.reverseOrder()).forEach(p => Files.deleteIfExists(p))
        finally files.close()

  private def graphLocation(output: String, files: List[BendSourceMapping],
      root: FileId): BendLocation =
    val Marked = "(?m)^\\s*([0-9]+)>\\| ?(.*)$".r
    val marks = Marked.findAllMatchIn(output).toList
    if marks.size != 1 then BendLocation.RootOnly
    else
      val line = marks.head.group(1).toInt - 1
      val excerpt = marks.head.group(2).trim
      val candidates = files.filter { source =>
        val lines = source.copiedText.split("\n", -1)
        line >= 0 && line < lines.length && lines(line).trim == excerpt &&
          !source.rewrittenLines.contains(line)
      }
      candidates match
        case source :: Nil => BendLocation.SourceLine(source.source, line)
        case _ => BendLocation.RootOnly
