package com.dearlordylord.bend.idea.toolchain.api

final case class BendCompilerInfo(version: Option[String])

enum BendCompilerInfoResult:
  case Detected(info: BendCompilerInfo)
  case Unavailable(details: String)
  case TimedOut
  case Canceled

/** Reads bounded compiler identity at the adapter boundary. */
trait BendCompilerInfoProbe:
  def inspect(
      executable: String,
      canceled: () => Boolean
  ): BendCompilerInfoResult
