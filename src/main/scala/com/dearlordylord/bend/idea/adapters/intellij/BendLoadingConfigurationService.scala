package com.dearlordylord.bend.idea.adapters.intellij

import com.dearlordylord.bend.idea.toolchain.api.BendToolchainSettings
import com.dearlordylord.bend.idea.workspace.api.{
  BendLoadingConfiguration,
  BendLoadingConfigurationSnapshot
}
import com.intellij.openapi.application.ApplicationManager

final class BendLoadingConfigurationService extends BendLoadingConfiguration:
  override def snapshot: BendLoadingConfigurationSnapshot =
    val selected = ApplicationManager.getApplication
      .getService(classOf[BendToolchainSettings])
      .selection
    BendLoadingConfigurationSnapshot(
      selected.baseSource,
      selected.packageCache,
      selected.configurationRevision
    )
