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
