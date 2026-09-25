package com.dearlordylord.bend.idea.toolchain.api

enum BendCheckOnlySupport:
  case Supported
  case Unsupported

final case class BendCompilerInfo(
    version: Option[String],
    checkOnly: BendCheckOnlySupport
)

enum BendCompilerInfoResult:
  case Detected(info: BendCompilerInfo)
  case Unavailable(details: String)
  case TimedOut
  case Canceled

/** Reads bounded compiler identity/capability facts at the adapter boundary. */
trait BendCompilerInfoProbe:
  def inspect(
      executable: String,
      canceled: () => Boolean
  ): BendCompilerInfoResult
