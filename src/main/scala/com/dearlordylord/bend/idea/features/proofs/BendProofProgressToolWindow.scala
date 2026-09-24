package com.dearlordylord.bend.idea.features.proofs

import com.dearlordylord.bend.idea.analysis.api.{
  BendCheckService,
  BendExplicitCheckOutcome,
  BendExplicitCheckRunner
}
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.{ApplicationManager, ModalityState}
import com.intellij.openapi.editor.{EditorFactory, event}
import com.intellij.openapi.fileEditor.{FileDocumentManager, OpenFileDescriptor}
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.{ToolWindow, ToolWindowFactory}
import com.intellij.ui.components.{JBLabel, JBList, JBTextField}
import com.intellij.util.Alarm
import com.intellij.ui.content.ContentFactory
import java.awt.{BorderLayout, Component, FlowLayout}
import java.awt.event.{ActionEvent, ActionListener, MouseAdapter, MouseEvent}
import java.nio.file.Path
import java.util.concurrent.atomic.{AtomicBoolean, AtomicLong}
import javax.swing.event.{
  DocumentEvent as SwingDocumentEvent,
  DocumentListener as SwingDocumentListener
}
import javax.swing.{
  DefaultComboBoxModel,
  DefaultListCellRenderer,
  DefaultListModel,
  JButton,
  JComboBox,
  JComponent,
  JList,
  JPanel,
  JScrollPane
}

final class BendProofProgressToolWindowFactory extends ToolWindowFactory:
  override def createToolWindowContent(
      project: Project,
      toolWindow: ToolWindow
  ): Unit =
    val panel = new BendProofProgressPanel(project)
    val content = ContentFactory.getInstance().createContent(panel, "", false)
    content.setDisposer(panel)
    toolWindow.getContentManager.addContent(content)
    panel.refresh()

/** Source inventory plus the selected root's own current compiler result. */
final class BendProofProgressPanel(
    project: Project,
    private val reader: BendProofProgressReader
) extends JPanel(new BorderLayout(6, 6)),
      Disposable:
  def this(project: Project) =
    this(project, new BendProofProgressReader(project))
  private val alarm = new Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)
  private val generation = new AtomicLong(0L)
  private val disposed = new AtomicBoolean(false)
  private val roots = new JComboBox[String]()
  private val filter = new JBTextField()
  private val entries = new DefaultListModel[BendProofInventoryEntry]()
  private val list = new JBList[BendProofInventoryEntry](entries)
  private val summary = new JBLabel(
    "Select a proof root to inspect source inventory."
  )
  private var allEntries = List.empty[BendProofInventoryEntry]
  @volatile private var latestSnapshot: Option[BendProofProgressSnapshot] = None
  private var updatingRoots = false

  private val connection = project.getMessageBus.connect(this)
  connection.subscribe(
    BendProofRootListener.Topic,
    new BendProofRootListener:
      override def rootsChanged(): Unit = refresh()
  )
  private val statusUnsubscribe = project
    .getService(classOf[BendCheckService])
    .addStatusListener(_ => refresh())

  private val documentListener = new event.DocumentListener:
    override def documentChanged(change: event.DocumentEvent): Unit =
      Option(FileDocumentManager.getInstance().getFile(change.getDocument))
        .filter(file => file.isValid && file.getName.endsWith(".bend"))
        .foreach(_ => refresh())
  EditorFactory
    .getInstance()
    .getEventMulticaster
    .addDocumentListener(documentListener, this)

  private val top = new JPanel(new BorderLayout(6, 0))
  private val controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0))
  private val refreshButton = new JButton("Refresh")
  private val checkButton = new JButton("Check root")

  controls.add(roots)
  controls.add(checkButton)
  controls.add(refreshButton)
  top.add(controls, BorderLayout.WEST)
  top.add(filter, BorderLayout.CENTER)
  add(top, BorderLayout.NORTH)
  add(new JScrollPane(list), BorderLayout.CENTER)
  add(summary, BorderLayout.SOUTH)

  list.setCellRenderer(new DefaultListCellRenderer:
    override def getListCellRendererComponent(
        owner: JList[?],
        value: Object,
        index: Int,
        selected: Boolean,
        focused: Boolean
    ): Component =
      val rendered = super.getListCellRendererComponent(
        owner,
        value,
        index,
        selected,
        focused
      )
      rendered
        .asInstanceOf[JComponent]
        .setToolTipText(value match
          case entry: BendProofInventoryEntry => entry.path
          case _                              => null)
      rendered)
  list.addMouseListener(new MouseAdapter:
    override def mouseClicked(event: MouseEvent): Unit =
      if event.getClickCount == 2 then
        Option(list.getSelectedValue).foreach(open))
  roots.addActionListener(new ActionListener:
    override def actionPerformed(event: ActionEvent): Unit =
      if !updatingRoots then selectedRoot.foreach(loadRoot))
  refreshButton.addActionListener((_: ActionEvent) => refresh())
  checkButton.addActionListener((_: ActionEvent) => checkSelectedRoot())
  filter.getDocument.addDocumentListener(new SwingDocumentListener:
    override def insertUpdate(change: SwingDocumentEvent): Unit = applyFilter()
    override def removeUpdate(change: SwingDocumentEvent): Unit = applyFilter()
    override def changedUpdate(change: SwingDocumentEvent): Unit =
      applyFilter())

  def refresh(): Unit =
    if disposed.get() || project.isDisposed then return
    val ticket = generation.incrementAndGet()
    alarm.cancelAllRequests()
    alarm.addRequest(
      new Runnable:
        override def run(): Unit =
          val available = reader.roots()
          if current(ticket) then
            ApplicationManager.getApplication.invokeLater(
              new Runnable:
                override def run(): Unit =
                  if !current(ticket) then return
                  val previous = selectedRoot
                  val next = previous
                    .filter(available.contains)
                    .orElse(available.headOption)
                  updatingRoots = true
                  roots.setModel(
                    new DefaultComboBoxModel[String](available.toArray)
                  )
                  next.foreach(roots.setSelectedItem)
                  updatingRoots = false
                  next match
                    case Some(path) => loadRoot(path, ticket)
                    case None       =>
                      latestSnapshot = None
                      allEntries = Nil
                      renderEntries()
                      summary.setText(
                        "No Bend proof roots found. Use Check root to select one."
                      )
              ,
              ModalityState.any()
            )
      ,
      150
    )

  private def loadRoot(path: String): Unit =
    val ticket = generation.incrementAndGet()
    alarm.cancelAllRequests()
    loadRoot(path, ticket)

  private def loadRoot(path: String, ticket: Long): Unit =
    alarm.addRequest(
      new Runnable:
        override def run(): Unit =
          val snapshot = reader.read(path, () => !current(ticket))
          snapshot.foreach { value =>
            if current(ticket) then
              ApplicationManager.getApplication.invokeLater(
                new Runnable:
                  override def run(): Unit =
                    if current(ticket) then
                      allEntries = value.entries
                      latestSnapshot = Some(value)
                      renderEntries()
                      summary.setText(
                        s"${value.rootPath} — ${value.checkedStatus}"
                      )
                ,
                ModalityState.any()
              )
          }
      ,
      0
    )

  private def checkSelectedRoot(): Unit =
    selectedRoot.foreach { path =>
      project.getService(classOf[BendProofRootStore]).select(path)
      project
        .getService(classOf[BendExplicitCheckRunner])
        .check(path, "Checking Bend proof root") {
          case BendExplicitCheckOutcome.Published(_)   => refresh()
          case BendExplicitCheckOutcome.Rejected(_, _) => refresh()
        }
    }

  private def selectedRoot: Option[String] =
    Option(roots.getSelectedItem).map(_.toString)

  private def current(ticket: Long): Boolean =
    !disposed.get() && !project.isDisposed && generation.get() == ticket

  private def applyFilter(): Unit = renderEntries()

  private def renderEntries(): Unit =
    entries.clear()
    BendProofProgressModel
      .filtered(allEntries, filter.getText)
      .foreach { entry =>
        val _ = entries.addElement(entry)
      }

  private def open(entry: BendProofInventoryEntry): Unit =
    try
      val path = Path.of(entry.path).toAbsolutePath.normalize().toString
      Option(LocalFileSystem.getInstance().findFileByPath(path)).foreach(file =>
        new OpenFileDescriptor(project, file, entry.offset).navigate(true)
      )
    catch case _: java.nio.file.InvalidPathException => ()

  private[proofs] def openEntry(entry: BendProofInventoryEntry): Unit = open(
    entry
  )

  private[proofs] def snapshotForTests: Option[BendProofProgressSnapshot] =
    latestSnapshot

  private[proofs] def selectRootForTests(path: String): Unit =
    roots.setSelectedItem(path)

  private[proofs] def isDisposedForTests: Boolean = disposed.get()

  override def dispose(): Unit =
    if disposed.compareAndSet(false, true) then
      generation.incrementAndGet()
      val _ = alarm.cancelAllRequests()
      statusUnsubscribe()
