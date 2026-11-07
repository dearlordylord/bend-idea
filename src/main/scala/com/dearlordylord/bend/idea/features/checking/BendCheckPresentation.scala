package com.dearlordylord.bend.idea.features.checking

import com.dearlordylord.bend.idea.analysis.api.BendCheckService
import com.dearlordylord.bend.idea.analysis.model.*
import com.dearlordylord.bend.idea.model.FileId
import com.intellij.openapi.vfs.VirtualFile

/** Presentation consumes compiler evidence without changing analysis policy. */
private[checking] object BendCheckPresentation:
  def id(file: VirtualFile): FileId = new FileId(
    Option(file.getCanonicalPath).getOrElse(file.getPath),
    file.getCanonicalPath != null
  )

  def summary(
      status: BendCheckingStatus,
      result: Option[BendCheckResult]
  ): String =
    val state =
      if status == BendCheckingStatus.Checking then "Checking…"
      else
        result match
          case Some(value) if !value.fresh => "Recheck needed"
          case Some(value)                 =>
            value.outcome match
              case BendCheckOutcome.Success =>
                value.completeness match
                  case BendCompleteness.Complete   => "Check passed"
                  case BendCompleteness.Incomplete => "Incomplete"
                  case BendCompleteness.Unknown    => "Completion unknown"
              case BendCheckOutcome.Failed =>
                if value.completeness == BendCompleteness.Incomplete then
                  "Incomplete"
                else "Check failed"
              case BendCheckOutcome.Unavailable => "Unavailable"
              case BendCheckOutcome.TimedOut    => "Timed out"
          case None => "Not checked"
    val reliance = result
      .filter(_.fresh)
      .filter(_ => status != BendCheckingStatus.Checking)
      .filter(_.reliance == BendReliance.UnsafeOrForeign)
      .map(_ => " · unsafe/foreign")
      .getOrElse("")
    "Bend: " + state + reliance

  def summary(service: BendCheckService, file: VirtualFile): String =
    val root = id(file)
    summary(service.status(root), service.result(root))

  def lintDetails(
      lint: BendLintResult,
      sources: List[BendCheckedSource] = Nil
  ): String =
    val findings = lint.findings.take(8).map { finding =>
      val location = sources
        .find(source =>
          source.id == finding.source && finding.range.start >= 0 &&
            finding.range.end > finding.range.start && finding.range.end <= source.text.length
        )
        .map { source =>
          val offset = finding.range.start
          val line = source.text.iterator.take(offset).count(_ == '\n') + 1
          val column = offset - source.text.lastIndexOf('\n', offset - 1)
          s"${source.path}:$line:$column"
        }
        .getOrElse(
          s"${finding.source.value} (UTF-16 offsets [${finding.range.start}, ${finding.range.end}))"
        )
      val shownLocation = if location.length > 192 then
        location.take(96) + "…" + location.takeRight(95)
      else location
      s"${finding.severity} [${finding.code.take(128)}] ${finding.message.take(512)}\n$shownLocation"
    }
    val moreFindings = if lint.findings.size > 8 then
      List(s"${lint.findings.size - 8} more finding(s).")
    else Nil
    val skipped =
      lint.skipped.take(8).map(item => s"${item.reason}: ${item.message}")
    val remaining = if lint.skipped.size > 8 then
      List(s"${lint.skipped.size - 8} more skipped file(s).")
    else Nil
    (List(s"Lint: ${lint.outcome}") ++ findings ++ moreFindings ++
      List(lint.details.take(1024)) ++ skipped ++ remaining ++
      Option
        .when(lint.unmapped > 0)(
          s"${lint.unmapped} finding(s) have no exact editor location."
        )
        .toList)
      .mkString("\n")
      .take(8192)

  def details(result: BendCheckResult): String =
    s"${result.status}\n\n${result.details}" +
      result.lint
        .map(lint => "\n\n" + lintDetails(lint, result.sources))
        .getOrElse("")
