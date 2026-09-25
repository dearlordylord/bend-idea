package com.dearlordylord.bend.idea.adapters.intellij

import com.dearlordylord.bend.idea.toolchain.api.*
import com.dearlordylord.bend.idea.analysis.api.BendCheckService
import com.intellij.openapi.components.{
  PersistentStateComponent,
  State,
  Storage
}
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.application.{ApplicationManager, ModalityState}
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.project.RootsChangeRescanningInfo
import com.intellij.openapi.roots.ex.ProjectRootManagerEx

final class BendSettingsState:
  var executable: String = ""
  var baseSource: String = ""
  var packageCache: String = ""
  var diagnosticsEnabled: Boolean = true

@State(name = "BendSettings", storages = Array(new Storage("BendSettings.xml")))
final class BendSettingsStorage
    extends PersistentStateComponent[BendSettingsState]
    with BendToolchainSettings:
  private var data = new BendSettingsState
  private var revision = 0L

  override def getState: BendSettingsState = synchronized { copyState(data) }
  override def loadState(state: BendSettingsState): Unit =
    val loaded = copyState(state)
    val rootsChanged = synchronized {
      val changed = data.baseSource != loaded.baseSource ||
        data.packageCache != loaded.packageCache
      data = loaded
      revision += 1
      changed
    }
    notifyChanged(rootsChanged)

  private def copyState(source: BendSettingsState): BendSettingsState =
    val copied = new BendSettingsState
    copied.executable = source.executable
    copied.baseSource = source.baseSource
    copied.packageCache = source.packageCache
    copied.diagnosticsEnabled = source.diagnosticsEnabled
    copied

  override def choices: BendToolchainChoices = synchronized {
    BendToolchainChoices(
      data.executable,
      data.baseSource,
      data.packageCache,
      data.diagnosticsEnabled
    )
  }

  override def selection: BendToolchainSelection = synchronized {
    BendToolchainPaths.resolve(
      choices,
      System.getProperty("user.home"),
      Option(System.getenv("BEND_LIB")),
      revision
    )
  }

  override def update(value: BendToolchainChoices): Unit =
    def valid(path: String): Boolean =
      path.trim.isEmpty || path.startsWith("~/") ||
        java.nio.file.Path.of(path).isAbsolute
    require(
      List(value.executable, value.baseSource, value.packageCache)
        .forall(valid),
      "Bend paths must be absolute or start with ~/."
    )
    val (changed, rootsChanged) = synchronized {
      if value == choices then (false, false)
      else
        val pathsChanged = data.baseSource != value.baseSource.trim ||
          data.packageCache != value.packageCache.trim
        val next = new BendSettingsState
        next.executable = value.executable.trim
        next.baseSource = value.baseSource.trim
        next.packageCache = value.packageCache.trim
        next.diagnosticsEnabled = value.diagnosticsEnabled
        data = next
        revision += 1
        (true, pathsChanged)
    }
    if changed then notifyChanged(rootsChanged)

  private def notifyChanged(rootsChanged: Boolean): Unit =
    ProjectManager.getInstance().getOpenProjects.foreach { project =>
      if !project.isDisposed then
        if rootsChanged then
          val app = ApplicationManager.getApplication
          val updateRoots = new Runnable:
            override def run(): Unit =
              ProjectRootManagerEx
                .getInstanceEx(project)
                .makeRootsChange(
                  new Runnable { override def run(): Unit = () },
                  RootsChangeRescanningInfo.RESCAN_DEPENDENCIES_IF_NEEDED
                )
          val writeRoots = new Runnable:
            override def run(): Unit = app.runWriteAction(updateRoots)
          if app.isDispatchThread then writeRoots.run()
          else app.invokeAndWait(writeRoots, ModalityState.any())
        Option(project.getService(classOf[BendCheckService]))
          .foreach(_.configurationChanged())
        project
          .getService(classOf[BendBackgroundChecking])
          .configurationChanged()
        DaemonCodeAnalyzer.getInstance(project).restart()
    }
