package com.dearlordylord.bend.idea.toolchain.api

enum BendLintInstallationResult:
  case Installed(bun: String, directory: String, details: String)
  case Unavailable(details: String)
  case Canceled

/** Explicit tool management. Analysis never downloads or updates tools. */
trait BendLintTools:
  def current(): BendLintInstallationResult
  def install(
      bunOverride: String,
      canceled: () => Boolean
  ): BendLintInstallationResult
  def rollback(): BendLintInstallationResult
