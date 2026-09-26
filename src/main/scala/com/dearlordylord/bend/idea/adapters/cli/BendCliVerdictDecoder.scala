package com.dearlordylord.bend.idea.adapters.cli

import com.dearlordylord.bend.idea.adapters.process.BendProcessOutcome
import com.dearlordylord.bend.idea.analysis.model.*
import com.dearlordylord.bend.idea.toolchain.api.{
  BendCompilerInfo,
  BendCompilerInfoResult
}

/** Classifies only complete, documented Bend CLI reports. Unknown text has no
  * semantic facts.
  */
private[cli] object BendCliVerdictDecoder:
  private val CheckOnlyHelpLine =
    "  bend <file.bend> --check-only check the file and its imports; run nothing"
  private val VersionHelpLine = "^Bend ([0-9]+\\.[0-9]+\\.[0-9]+):.*$".r
  private val UnsafeHeader =
    "^All terms check, but ([1-9][0-9]*) (defs?) (relies|rely) on unsafe or foreign code:$".r
  private val UnsafeDefinition = "- [A-Za-z_][A-Za-z0-9_.]*".r
  private val TodoHeader = "^Error: ([1-9][0-9]*) TODO(s?) found\\.$".r
  private val ProofSiblingGuard =
    "bend: PROOF.bend must import ./LAWS.bend (see bend --help)"

  enum Capability:
    case Supported(help: String)
    case TimedOut(details: String)
    case Canceled(details: String)
    case OutputLimit(details: String)
    case Unavailable(details: String)

  final case class Check(
      outcome: BendCheckOutcome,
      completeness: BendCompleteness,
      reliance: BendReliance,
      details: String,
      sourceLocationAllowed: Boolean = false
  )

  def capability(process: BendProcessOutcome): Capability = process match
    case BendProcessOutcome.Exited(0, output) =>
      val help = normalize(output)
      if supportsCheckOnly(help) then Capability.Supported(help)
      else
        Capability.Unavailable(
          "This Bend compiler does not document --check-only; no source was checked."
        )
    case BendProcessOutcome.Exited(_, _) =>
      Capability.Unavailable(
        "This Bend compiler does not document --check-only; no source was checked."
      )
    case BendProcessOutcome.TimedOut(output) =>
      Capability.TimedOut(
        withOutput(
          "Bend capability probe timed out; no source was checked.",
          output
        )
      )
    case BendProcessOutcome.Canceled(_) =>
      Capability.Canceled(
        "Bend capability probe canceled; no source was checked."
      )
    case BendProcessOutcome.OutputLimit(output) =>
      Capability.OutputLimit(
        withOutput(
          "Bend capability probe output exceeded the limit; no source was checked.",
          output
        )
      )
    case BendProcessOutcome.StartFailed(message) =>
      Capability.Unavailable(
        "Could not start Bend capability probe: " + message + "; no source was checked."
      )

  def compilerInfo(process: BendProcessOutcome): BendCompilerInfoResult =
    process match
      case BendProcessOutcome.Exited(0, output) =>
        val help = normalize(output)
        val version = help
          .split("\\n", -1)
          .iterator
          .collectFirst { case VersionHelpLine(value) => value }
        BendCompilerInfoResult.Detected(BendCompilerInfo(version))
      case BendProcessOutcome.Exited(code, _) =>
        BendCompilerInfoResult.Unavailable(
          s"Bend --help exited with code $code."
        )
      case BendProcessOutcome.TimedOut(_)    => BendCompilerInfoResult.TimedOut
      case BendProcessOutcome.Canceled(_)    => BendCompilerInfoResult.Canceled
      case BendProcessOutcome.OutputLimit(_) =>
        BendCompilerInfoResult.Unavailable(
          "Bend help output exceeded the limit."
        )
      case BendProcessOutcome.StartFailed(message) =>
        BendCompilerInfoResult.Unavailable(message)

  private def supportsCheckOnly(help: String): Boolean =
    help.split("\n", -1).contains(CheckOnlyHelpLine)

  def check(process: BendProcessOutcome): Check = process match
    case BendProcessOutcome.Exited(code, output) =>
      val report = normalize(output)
      if code == 0 && report == "All terms check." then
        Check(
          BendCheckOutcome.Success,
          BendCompleteness.Complete,
          BendReliance.None,
          report
        )
      else if code == 0 then
        unsafeReliance(report) match
          case Some(reliance) =>
            Check(
              BendCheckOutcome.Success,
              BendCompleteness.Complete,
              reliance,
              report
            )
          case None =>
            Check(
              BendCheckOutcome.Unavailable,
              BendCompleteness.Unknown,
              BendReliance.Unknown,
              unrecognized(code, report)
            )
      else if todoReport(report) then
        Check(
          BendCheckOutcome.Failed,
          BendCompleteness.Incomplete,
          BendReliance.Unknown,
          report
        )
      else if compilerErrorReport(report) then
        Check(
          BendCheckOutcome.Failed,
          BendCompleteness.Unknown,
          BendReliance.Unknown,
          report,
          sourceLocationAllowed = compilerLocationReport(report)
        )
      else
        Check(
          BendCheckOutcome.Unavailable,
          BendCompleteness.Unknown,
          BendReliance.Unknown,
          unrecognized(code, report)
        )
    case BendProcessOutcome.TimedOut(output) =>
      Check(
        BendCheckOutcome.TimedOut,
        BendCompleteness.Unknown,
        BendReliance.Unknown,
        withOutput("Bend check timed out.", output)
      )
    case BendProcessOutcome.Canceled(_) =>
      Check(
        BendCheckOutcome.Unavailable,
        BendCompleteness.Unknown,
        BendReliance.Unknown,
        "Bend check canceled."
      )
    case BendProcessOutcome.OutputLimit(output) =>
      Check(
        BendCheckOutcome.Unavailable,
        BendCompleteness.Unknown,
        BendReliance.Unknown,
        withOutput("Bend check output exceeded the limit.", output)
      )
    case BendProcessOutcome.StartFailed(message) =>
      Check(
        BendCheckOutcome.Unavailable,
        BendCompleteness.Unknown,
        BendReliance.Unknown,
        message
      )

  private def normalize(output: String): String =
    output
      .replaceAll("\\u001b\\[[0-9;]*m", "")
      .replace("\r\n", "\n")
      .stripSuffix("\n")

  private def withOutput(message: String, output: String): String =
    val report = normalize(output)
    if report.isEmpty then message else message + "\n" + report

  private def unsafeReliance(report: String): Option[BendReliance] =
    val lines = report.split("\n", -1).toList
    lines match
      case UnsafeHeader(countText, pluralDef, verb) :: definitions
          if countText.toIntOption.exists(_ > 0) && definitions.nonEmpty &&
            definitions.forall(line => UnsafeDefinition.matches(line)) =>
        val count = countText.toInt
        val grammarMatches = if count == 1 then
          pluralDef == "def" && verb == "relies"
        else pluralDef == "defs" && verb == "rely"
        Option.when(grammarMatches && definitions.size == count)(
          BendReliance.UnsafeOrForeign
        )
      case _ => None

  private def todoReport(report: String): Boolean =
    report.split("\n", -1).toList match
      case TodoHeader(
            countText,
            plural
          ) :: "The code is incomplete, and not a valid proof yet." :: Nil =>
        val count = BigInt(countText)
        count > 0 && (if count == 1 then plural.isEmpty else plural == "s")
      case _ => false

  private def compilerErrorReport(report: String): Boolean =
    val lines = report.split("\n", -1)
    report == ProofSiblingGuard || (lines.length >= 2 && lines(0) == "Error:" &&
      (lines(1)
        .startsWith("- message  : ") || lines(1).startsWith("- expected : ")))

  private def compilerLocationReport(report: String): Boolean =
    val lines = report.split("\n", -1).toList
    val location = lines.indexWhere(_.startsWith("Location:"))
    val line = "^\\s*[0-9]+(?:>| )\\| ?.*$".r
    val marked = "^\\s*[0-9]+>\\| ?.*$".r
    val underline = "^\\s*\\|\\s*\\^+\\s*$".r
    def recognizedField(text: String, inContext: Boolean): Boolean =
      text.startsWith("- message  : ") || text.startsWith("- expected : ") ||
        text.startsWith("- observed : ") ||
        (inContext && text.matches("^- .+ : .+$"))
    def recognizedFields(fields: List[String]): Boolean =
      fields match
        case first :: rest
            if first.startsWith("- message  : ") || first.startsWith(
              "- expected : "
            ) =>
          rest
            .foldLeft(Option(false)) {
              case (Some(_), "Context:") => Some(true)
              case (Some(inContext), field)
                  if recognizedField(field, inContext) =>
                Some(inContext)
              case _ => None
            }
            .isDefined
        case _ => false
    val beforeLocation = lines.take(location).drop(1)
    val excerpts = lines.drop(location + 1)
    location >= 0 && location < lines.size - 1 && recognizedFields(
      beforeLocation
    ) &&
    excerpts.zipWithIndex.forall { (text, index) =>
      line.matches(text) ||
      (index > 0 && marked.matches(excerpts(index - 1)) &&
        underline.matches(text))
    } && excerpts.exists(marked.matches)

  private def unrecognized(exitCode: Int, report: String): String =
    val prefix = if exitCode == 0 then
      "Bend returned an unrecognized check result; no verdict was inferred."
    else
      s"Bend returned unrecognized failure output (exit code $exitCode); no compiler verdict was inferred."
    if report.isEmpty then prefix else prefix + "\n" + report
