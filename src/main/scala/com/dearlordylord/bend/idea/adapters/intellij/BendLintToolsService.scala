package com.dearlordylord.bend.idea.adapters.intellij

import com.dearlordylord.bend.idea.adapters.cli.BendLintInstallation
import com.dearlordylord.bend.idea.toolchain.api.{
  BendLintInstallationResult,
  BendLintTools
}
import com.intellij.openapi.application.PathManager
import java.nio.file.Path

final class BendLintToolsService extends BendLintTools:
  private def installation: BendLintInstallation =
    new BendLintInstallation(
      Path.of(PathManager.getSystemPath).resolve("bend-lint-tools")
    )

  override def current(): BendLintInstallationResult = installation.current()

  override def install(
      bunOverride: String,
      canceled: () => Boolean
  ): BendLintInstallationResult = installation.install(bunOverride, canceled)

  override def rollback(): BendLintInstallationResult = installation.rollback()
