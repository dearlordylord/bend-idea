package com.dearlordylord.bend.idea.workspace.api

/** Current import-loading inputs for editor services that do not own toolchain
  * settings.
  */
final case class BendLoadingConfigurationSnapshot(
    baseSource: String,
    packageCache: String,
    configurationRevision: Long
):
  def paths: (String, String) = (baseSource, packageCache)

trait BendLoadingConfiguration:
  def snapshot: BendLoadingConfigurationSnapshot

  def paths: (String, String) = snapshot.paths

  def configurationRevision: Long = snapshot.configurationRevision
