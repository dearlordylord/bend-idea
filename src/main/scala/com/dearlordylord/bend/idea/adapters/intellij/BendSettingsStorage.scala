package com.dearlordylord.bend.idea.adapters.intellij

import com.dearlordylord.bend.idea.toolchain.api.*
import com.intellij.openapi.components.{PersistentStateComponent, State, Storage}

final class BendSettingsState:
  var executable: String = ""
  var baseSource: String = ""
  var packageCache: String = ""
  var diagnosticsEnabled: Boolean = true

@State(name = "BendSettings", storages = Array(new Storage("BendSettings.xml")))
final class BendSettingsStorage extends PersistentStateComponent[BendSettingsState] with BendToolchainSettings:
  private var data = new BendSettingsState
  private var revision = 0L

  override def getState: BendSettingsState = data
  override def loadState(state: BendSettingsState): Unit = synchronized {
    data = state
    revision += 1
  }

  override def choices: BendToolchainChoices = synchronized {
    BendToolchainChoices(data.executable, data.baseSource, data.packageCache, data.diagnosticsEnabled)
  }

  override def selection: BendToolchainSelection = synchronized {
    BendToolchainPaths.resolve(choices, System.getProperty("user.home"),
      Option(System.getenv("BEND_LIB")), revision)
  }

  override def update(value: BendToolchainChoices): Unit = synchronized {
    def valid(path: String): Boolean = path.trim.isEmpty || path.startsWith("~/") ||
      java.nio.file.Path.of(path).isAbsolute
    require(List(value.executable, value.baseSource, value.packageCache).forall(valid),
      "Bend paths must be absolute or start with ~/.")
    if value != choices then
      val next = new BendSettingsState
      next.executable = value.executable.trim
      next.baseSource = value.baseSource.trim
      next.packageCache = value.packageCache.trim
      next.diagnosticsEnabled = value.diagnosticsEnabled
      data = next
      revision += 1
  }
