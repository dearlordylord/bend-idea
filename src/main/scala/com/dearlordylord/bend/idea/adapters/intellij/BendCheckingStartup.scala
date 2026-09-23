package com.dearlordylord.bend.idea.adapters.intellij

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity

/** Install project listeners when the project opens. */
final class BendCheckingStartup extends StartupActivity.DumbAware:
  override def runActivity(project: Project): Unit =
    project.getService(classOf[BendBackgroundChecking]).configurationChanged()
