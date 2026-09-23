package com.dearlordylord.bend.idea.adapters.intellij

import com.dearlordylord.bend.idea.toolchain.api.BendToolchainSettings
import com.dearlordylord.bend.idea.workspace.api.BendLoadingConfiguration
import com.intellij.openapi.application.ApplicationManager

final class BendLoadingConfigurationService extends BendLoadingConfiguration:
  override def paths: (String, String) =
    val selected = ApplicationManager.getApplication.getService(classOf[BendToolchainSettings]).selection
    (selected.baseSource, selected.packageCache)
