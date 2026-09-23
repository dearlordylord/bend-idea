package com.dearlordylord.bend.idea.workspace.api

/** Current import-loading inputs for editor services that do not own toolchain
  * settings.
  */
trait BendLoadingConfiguration:
  def paths: (String, String)
