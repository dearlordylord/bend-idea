package com.dearlordylord.bend.idea.analysis.model

/** The displayed state keeps currency separate from the compiler verdict. */
enum BendCheckingStatus:
  case Disabled, Checking, Unchecked, Stale, Current, Incomplete, Failed, Unavailable, TimedOut

object BendCheckingStatus:
  def of(enabled: Boolean, running: Boolean, result: Option[BendCheckResult]): BendCheckingStatus =
    if !enabled then Disabled
    else if running then Checking
    else result match
      case None => Unchecked
      case Some(value) if !value.fresh => Stale
      case Some(value) => value.outcome match
        case BendCheckOutcome.Success =>
          if value.completeness == BendCompleteness.Incomplete then Incomplete else Current
        case BendCheckOutcome.Failed =>
          if value.completeness == BendCompleteness.Incomplete then Incomplete else Failed
        case BendCheckOutcome.Unavailable => Unavailable
        case BendCheckOutcome.TimedOut => TimedOut

  def label(value: BendCheckingStatus): String = value match
    case Disabled => "Background checking disabled"
    case Checking => "Checking"
    case Unchecked => "Not checked"
    case Stale => "Check stale"
    case Current => "Check current"
    case Incomplete => "Check incomplete"
    case Failed => "Check failed"
    case Unavailable => "Checker unavailable"
    case TimedOut => "Check timed out"
