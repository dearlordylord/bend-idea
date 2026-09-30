package com.dearlordylord.bend.idea.features.checking

import com.dearlordylord.bend.idea.analysis.api.BendCheckService
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.{
  FileEditorManager,
  FileEditorManagerEvent,
  FileEditorManagerListener
}
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.{
  StatusBar,
  StatusBarWidget,
  StatusBarWidgetFactory
}
import com.intellij.util.Consumer
import java.awt.event.MouseEvent

final class BendCheckStatusWidgetFactory extends StatusBarWidgetFactory:
  override def getId: String = BendCheckStatusWidget.Id
  override def getDisplayName: String = "Bend check status"
  override def createWidget(project: Project): StatusBarWidget =
    new BendCheckStatusWidget(project)

private[checking] object BendCheckStatusWidget:
  val Id = "Bend.CheckStatusWidget"

/** Tracks the active editor; analysis owns all freshness and compiler facts. */
final class BendCheckStatusWidget(project: Project)
    extends StatusBarWidget
    with StatusBarWidget.TextPresentation:
  private var bar: StatusBar = null
  private var unsubscribe: () => Unit = () => ()
  @volatile private var disposed = false

  override def ID(): String = BendCheckStatusWidget.Id
  override def getPresentation: StatusBarWidget.WidgetPresentation = this
  override def getAlignment: Float = 0.0f
  private[checking] def selectedFile: Option[VirtualFile] =
    FileEditorManager
      .getInstance(project)
      .getSelectedFiles
      .headOption
      .filter(file => file.isValid && file.getName.endsWith(".bend"))
  override def getText: String =
    selectedFile
      .map(file =>
        BendCheckPresentation.summary(
          project.getService(classOf[BendCheckService]),
          file
        )
      )
      .getOrElse("")
  override def getTooltipText: String =
    "Bend check status — click for details and compiler output"
  override def getClickConsumer: Consumer[MouseEvent] =
    new Consumer[MouseEvent]:
      override def consume(event: MouseEvent): Unit =
        selectedFile.foreach(file => BendCheckStatusPopup.show(project, file))

  override def install(statusBar: StatusBar): Unit =
    bar = statusBar
    unsubscribe = project
      .getService(classOf[BendCheckService])
      .addStatusListener(_ => refresh())
    project.getMessageBus
      .connect(this)
      .subscribe(
        FileEditorManagerListener.FILE_EDITOR_MANAGER,
        new FileEditorManagerListener:
          override def selectionChanged(event: FileEditorManagerEvent): Unit =
            refresh()
      )
    refresh()

  private def refresh(): Unit =
    ApplicationManager.getApplication.invokeLater(() =>
      if !disposed && !project.isDisposed && bar != null then
        bar.updateWidget(ID())
    )

  override def dispose(): Unit =
    disposed = true
    unsubscribe()
    bar = null
