package com.dearlordylord.bend.idea.features.proofs

import com.dearlordylord.bend.idea.analysis.api.{
  BendCheckService,
  BendExplicitCheckOutcome,
  BendExplicitCheckRunner
}
import com.dearlordylord.bend.idea.analysis.model.{
  BendCheckOutcome,
  BendCheckResult
}
import com.dearlordylord.bend.idea.model.FileId
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.{
  ApplicationManager,
  ModalityState,
  ReadAction
}
import com.intellij.openapi.editor.{EditorFactory, event}
import com.intellij.openapi.fileEditor.{
  FileDocumentManager,
  FileEditorManager,
  OpenFileDescriptor
}
import com.intellij.openapi.progress.{ProgressIndicator, ProgressManager, Task}
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.{ModuleRootEvent, ModuleRootListener}
import com.intellij.openapi.ui.Messages
import com.dearlordylord.bend.idea.symbols.api.BendPhysicalTargets
import com.dearlordylord.bend.idea.syntax.psi.BendLaw
import com.dearlordylord.bend.idea.workspace.api.BendPathInventoryStatus
import com.dearlordylord.bend.idea.workspace.api.BendLoadingConfiguration
import com.intellij.openapi.wm.{ToolWindow, ToolWindowFactory}
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.ui.components.{JBLabel, JBList, JBTextField}
import com.intellij.util.Alarm
import com.intellij.ui.content.ContentFactory
import java.awt.{BorderLayout, Component, FlowLayout}
import java.awt.event.{ActionEvent, ActionListener, MouseAdapter, MouseEvent}
import java.nio.file.Path
import java.util.concurrent.atomic.{AtomicBoolean, AtomicLong}
import com.intellij.psi.{SmartPsiElementPointer, SmartPointerManager}
import com.intellij.psi.util.{PsiModificationTracker, PsiTreeUtil}
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
  JList,
  JPanel,
  JScrollPane,
  JTabbedPane,
  JTextArea
}
import scala.jdk.CollectionConverters.*

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

/** Actionable source work plus the selected root's own compiler result. */
final class BendProofProgressPanel(
    project: Project,
    private val reader: BendProofProgressReader
) extends JPanel(new BorderLayout(6, 6)),
      Disposable:
  def this(project: Project) =
    this(project, new BendProofProgressReader(project))
  private val alarm = new Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)
  private val statusAlarm = new Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)
  private val generation = new AtomicLong(0L)
  private val selectionGeneration = new AtomicLong(0L)
  private val sourceGeneration = new AtomicLong(0L)
  private val statusGeneration = new AtomicLong(0L)
  private val disposed = new AtomicBoolean(false)
  @volatile private var activeRootPath: Option[String] = None
  private val roots = new JComboBox[String]()
  private val filter = new JBTextField()
  private val entries = new DefaultListModel[BendProofInventoryEntry]()
  private val list = new JBList[BendProofInventoryEntry](entries)
  private val inventoryEntries = new DefaultListModel[BendProofInventoryEntry]()
  private val inventoryList =
    new JBList[BendProofInventoryEntry](inventoryEntries)
  private val tabs = new JTabbedPane()
  private val counts = new JBLabel("Select a proof root to find source work.")
  private val summary = new JBLabel(
    "Choose a proof root above to find holes and laws without candidate fills."
  )
  private val checkExplanation = new JTextArea()
  private val detail = new JTextArea("Open a work item to edit its source.")
  private var workItems = List.empty[BendProofInventoryEntry]
  private var allEntries = List.empty[BendProofInventoryEntry]
  private var pendingWorkSelection: Option[BendProofInventoryEntry] = None
  @volatile private var latestSnapshot: Option[BendProofProgressSnapshot] = None
  private var latestCheckResult: Option[BendCheckResult] = None
  private var rootInventoryStatus = BendPathInventoryStatus.Complete
  private var updatingRoots = false

  private val connection = project.getMessageBus.connect(this)
  connection.subscribe(
    BendProofRootListener.Topic,
    new BendProofRootListener:
      override def rootsChanged(): Unit = refresh(preferStoredRoot = true)
  )
  private val statusUnsubscribe = project
    .getService(classOf[BendCheckService])
    .addStatusListener(updateCheckStatus)

  private val documentListener = new event.DocumentListener:
    override def documentChanged(change: event.DocumentEvent): Unit =
      Option(FileDocumentManager.getInstance().getFile(change.getDocument))
        .filter(file => file.isValid && file.getName.endsWith(".bend"))
        .filter(file =>
          latestSnapshot.exists(_.sourcePaths.contains(file.getPath)) ||
            (latestSnapshot.isEmpty && activeRootPath.contains(file.getPath))
        )
        .foreach(_ =>
          sourceGeneration.incrementAndGet()
          refresh()
        )
  EditorFactory
    .getInstance()
    .getEventMulticaster
    .addDocumentListener(documentListener, this)

  connection.subscribe(
    VirtualFileManager.VFS_CHANGES,
    new BulkFileListener:
      override def after(events: java.util.List[? <: VFileEvent]): Unit =
        val observed = latestSnapshot.fold(Set.empty[String])(_.observedPaths)
        val changed = events.asScala.map(_.getPath).toSet
        if observed.exists(path =>
            changed.exists(eventPath =>
              path == eventPath || path.startsWith(
                eventPath.stripSuffix("/") + "/"
              )
            )
          )
        then
          sourceGeneration.incrementAndGet()
          refresh()
  )
  connection.subscribe(
    ModuleRootListener.TOPIC,
    new ModuleRootListener:
      override def rootsChanged(event: ModuleRootEvent): Unit =
        sourceGeneration.incrementAndGet()
        refresh()
  )

  private val top = new JPanel(new BorderLayout(6, 0))
  private val controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0))
  private val rootLabel = new JBLabel("Proof root:")
  private val refreshButton = new JButton("Refresh")
  private val checkButton = new JButton("Check root")
  private val showOutputButton = new JButton("Show check output")
  private val openButton = new JButton("Open")
  private val generateButton = new JButton("Generate fill in this root")
  private val nextHoleButton = new JButton("Next hole")
  private val workPanel = new JPanel(new BorderLayout(0, 6))
  private val workActions = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0))

  detail.setEditable(false)
  detail.setFocusable(false)
  detail.setOpaque(false)
  detail.setLineWrap(true)
  detail.setWrapStyleWord(true)
  detail.setRows(2)
  checkExplanation.setEditable(false)
  checkExplanation.setFocusable(false)
  checkExplanation.setOpaque(false)
  checkExplanation.setLineWrap(true)
  checkExplanation.setWrapStyleWord(true)
  checkExplanation.setRows(2)
  showOutputButton.setEnabled(false)
  filter.getEmptyText.setText("Filter work and inventory")
  roots.setRenderer(new DefaultListCellRenderer:
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
      value match
        case path: String =>
          val full = Path.of(path)
          val label = Option(project.getBasePath)
            .map(Path.of(_))
            .filter(full.startsWith)
            .map(_.relativize(full).toString)
            .getOrElse(path)
          setText(label)
          setToolTipText(path)
        case _ => setToolTipText(null)
      rendered)

  controls.add(rootLabel)
  controls.add(roots)
  controls.add(checkButton)
  controls.add(showOutputButton)
  controls.add(refreshButton)
  top.add(controls, BorderLayout.WEST)
  top.add(filter, BorderLayout.CENTER)
  val checkPanel = new JPanel(new BorderLayout(0, 3))
  checkPanel.add(summary, BorderLayout.NORTH)
  checkPanel.add(checkExplanation, BorderLayout.SOUTH)
  top.add(checkPanel, BorderLayout.SOUTH)
  workActions.add(openButton)
  workActions.add(generateButton)
  workActions.add(nextHoleButton)
  workPanel.add(counts, BorderLayout.NORTH)
  workPanel.add(new JScrollPane(list), BorderLayout.CENTER)
  workPanel.add(workActions, BorderLayout.SOUTH)
  tabs.addTab("Work to do", workPanel)
  tabs.addTab("Laws and candidate fills", new JScrollPane(inventoryList))
  add(top, BorderLayout.NORTH)
  add(tabs, BorderLayout.CENTER)
  add(detail, BorderLayout.SOUTH)

  list.setCellRenderer(renderer(work = true))
  inventoryList.setCellRenderer(renderer(work = false))
  list.addMouseListener(new MouseAdapter:
    override def mouseClicked(event: MouseEvent): Unit =
      if event.getClickCount == 2 then
        Option(list.getSelectedValue).foreach(open))
  inventoryList.addMouseListener(new MouseAdapter:
    override def mouseClicked(event: MouseEvent): Unit =
      if event.getClickCount == 2 then
        Option(inventoryList.getSelectedValue).foreach(open))
  list.addListSelectionListener(_ => updateWorkSelection())
  openButton.addActionListener((_: ActionEvent) =>
    Option(list.getSelectedValue).foreach(open)
  )
  generateButton.addActionListener((_: ActionEvent) =>
    Option(list.getSelectedValue)
      .filter(_.kind == BendProofInventoryKind.Law)
      .foreach(generateFill)
  )
  nextHoleButton.addActionListener((_: ActionEvent) => openNextHole())
  roots.addActionListener(new ActionListener:
    override def actionPerformed(event: ActionEvent): Unit =
      if !updatingRoots then
        checkButton.setEnabled(selectedRoot.nonEmpty)
        selectedRoot.foreach(loadRoot))
  refreshButton.addActionListener((_: ActionEvent) => refresh())
  checkButton.addActionListener((_: ActionEvent) => checkSelectedRoot())
  showOutputButton.addActionListener((_: ActionEvent) =>
    latestCheckResult
      .map(checkOutput)
      .filter(_.nonEmpty)
      .foreach(output =>
        Messages.showInfoMessage(project, output, "Bend check output")
      )
  )
  filter.getDocument.addDocumentListener(new SwingDocumentListener:
    override def insertUpdate(change: SwingDocumentEvent): Unit = applyFilter()
    override def removeUpdate(change: SwingDocumentEvent): Unit = applyFilter()
    override def changedUpdate(change: SwingDocumentEvent): Unit =
      applyFilter())

  def refresh(): Unit = refresh(preferStoredRoot = false)

  private def refresh(preferStoredRoot: Boolean): Unit =
    if disposed.get() || project.isDisposed then return
    val ticket = generation.incrementAndGet()
    alarm.cancelAllRequests()
    alarm.addRequest(
      new Runnable:
        override def run(): Unit =
          val inventory = reader.roots()
          if current(ticket) then
            ApplicationManager.getApplication.invokeLater(
              new Runnable:
                override def run(): Unit =
                  if !current(ticket) then return
                  val previous = selectedRoot
                  rootInventoryStatus = inventory.status
                  val next =
                    if preferStoredRoot then inventory.paths.headOption
                    else
                      previous
                        .filter(inventory.paths.contains)
                        .orElse(inventory.paths.headOption)
                  updatingRoots = true
                  roots.setModel(
                    new DefaultComboBoxModel[String](inventory.paths.toArray)
                  )
                  roots.setSelectedItem(next.orNull)
                  checkButton.setEnabled(next.nonEmpty)
                  updatingRoots = false
                  next match
                    case Some(path) => loadRoot(path, ticket)
                    case None       =>
                      setActiveRoot(None)
                      pendingWorkSelection = None
                      latestSnapshot = None
                      latestCheckResult = None
                      workItems = Nil
                      allEntries = Nil
                      renderEntries()
                      counts.setText("No source work items found.")
                      checkExplanation.setText("")
                      showOutputButton.setEnabled(false)
                      summary.setText(
                        withRootInventoryNotice(
                          if inventory.paths.isEmpty then
                            "No proof roots found. Use Check Bend Proof Root to select a PROOF.bend file."
                          else
                            "Choose a proof root above to find holes and laws without candidate fills."
                        )
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
    val sameRoot = activeRootPath.contains(path) && latestSnapshot.nonEmpty
    val previousSelection =
      if activeRootPath.contains(path) then Option(list.getSelectedValue)
      else None
    setActiveRoot(Some(path))
    if !sameRoot then
      latestSnapshot = None
      latestCheckResult = None
      workItems = Nil
      allEntries = Nil
      renderEntries()
      counts.setText("Loading source work…")
      summary.setText("Loading selected proof root…")
      checkExplanation.setText("")
      showOutputButton.setEnabled(false)
    pendingWorkSelection = previousSelection
    summary.setToolTipText(path)
    alarm.addRequest(
      new Runnable:
        override def run(): Unit =
          val snapshot = reader.read(path, () => !current(ticket))
          snapshot.foreach { value =>
            val check = value.rootId.map(reader.checkState)
            if current(ticket) then
              ApplicationManager.getApplication.invokeLater(
                new Runnable:
                  override def run(): Unit =
                    if current(ticket) then
                      latestSnapshot = Some(value)
                      workItems = BendProofProgressModel.workItems(value)
                      allEntries = BendProofProgressModel.inventoryItems(value)
                      renderEntries()
                      pendingWorkSelection = None
                      val holeCount = workItems.count(
                        _.kind == BendProofInventoryKind.Hole
                      )
                      val unmatchedCount = value.lawGroups.count(
                        _.candidateFills.isEmpty
                      )
                      counts.setText(
                        s"$holeCount hole(s); $unmatchedCount law(s) with no candidate fill found" +
                          (if value.inventoryLimited then "; inventory capped"
                           else "")
                      )
                      check match
                        case Some((status, result)) =>
                          showCheckState(value, status, result)
                        case None =>
                          latestCheckResult = None
                          checkExplanation.setText("")
                          showOutputButton.setEnabled(false)
                          summary.setText(
                            withRootInventoryNotice(value.checkedStatus)
                          )
                          summary.setToolTipText(value.rootPath)
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
          case BendExplicitCheckOutcome.Published(_) =>
            if latestSnapshot.exists(_.rootId.isEmpty) then refresh()
          case BendExplicitCheckOutcome.Rejected(_, reason) =>
            if selectedRoot.contains(path) then checkExplanation.setText(reason)
        }
    }

  private def updateCheckStatus(root: FileId): Unit =
    val selected = latestSnapshot.filter(_.rootId.contains(root))
    if !disposed.get() && selected.exists(snapshot =>
        snapshot.loadingConfigurationRevision != project
          .getService(classOf[BendLoadingConfiguration])
          .configurationRevision
      )
    then
      sourceGeneration.incrementAndGet()
      refresh()
    else if !disposed.get() && selected.nonEmpty then
      val ticket = selectionGeneration.get()
      val sourceTicket = sourceGeneration.get()
      val statusTicket = statusGeneration.incrementAndGet()
      statusAlarm.cancelAllRequests()
      statusAlarm.addRequest(
        new Runnable:
          override def run(): Unit =
            val (status, result) = reader.checkState(root)
            ApplicationManager.getApplication.invokeLater(
              new Runnable:
                override def run(): Unit =
                  if selectionGeneration.get() == ticket &&
                    sourceGeneration.get() == sourceTicket &&
                    statusGeneration.get() == statusTicket &&
                    !disposed.get()
                  then
                    latestSnapshot
                      .filter(_.rootId.contains(root))
                      .foreach(snapshot =>
                        showCheckState(snapshot, status, result)
                      )
              ,
              ModalityState.any()
            )
        ,
        150
      )

  private def showCheckState(
      snapshot: BendProofProgressSnapshot,
      status: String,
      result: Option[BendCheckResult]
  ): Unit =
    latestSnapshot = Some(
      snapshot.copy(
        checkedStatus = status + snapshot.sourceNotice
      )
    )
    latestCheckResult = result
    summary.setText(withRootInventoryNotice(status + snapshot.sourceNotice))
    summary.setToolTipText(snapshot.rootPath)
    showOutputButton.setEnabled(
      status != "Checking" && result.exists(value =>
        checkOutput(value).nonEmpty
      )
    )
    checkExplanation.setText(
      if status == "Checking" then "Checking this proof root…"
      else if result.exists(!_.fresh) then
        "The source changed since the last check. Check root to get a current result."
      else if status == "Not checked" ||
        status == "Background checking disabled"
      then "Select Check root to run the Bend compiler on this proof root."
      else
        result match
          case Some(value) if value.outcome == BendCheckOutcome.Failed =>
            val firstError = value.diagnostics.headOption
              .map(_.message)
              .orElse(value.details.linesIterator.map(_.trim).find(_.nonEmpty))
              .map(_.take(220))
              .getOrElse("Open the check output for the compiler error.")
            s"First error: $firstError  Fix it in the source, then Check root."
          case Some(value) if value.outcome == BendCheckOutcome.Unavailable =>
            "The compiler could not check this root. Show check output for the reason."
          case Some(value) if value.outcome == BendCheckOutcome.TimedOut =>
            "The root check timed out. Show check output for details."
          case _ => ""
    )

  private def checkOutput(result: BendCheckResult): String =
    val details = result.details.trim
    if details.nonEmpty then details
    else result.diagnostics.map(_.message).mkString("\n").trim

  private def selectedRoot: Option[String] =
    Option(roots.getSelectedItem).map(_.toString)

  private def current(ticket: Long): Boolean =
    !disposed.get() && !project.isDisposed && generation.get() == ticket

  private def setActiveRoot(path: Option[String]): Unit =
    if activeRootPath != path then
      selectionGeneration.incrementAndGet()
      activeRootPath = path

  private def selectionCurrent(path: String, ticket: Long): Boolean =
    !disposed.get() && !project.isDisposed &&
      selectionGeneration.get() == ticket && activeRootPath.contains(path)

  private def withRootInventoryNotice(summaryText: String): String =
    BendPathInventoryStatus
      .notice(rootInventoryStatus)
      .fold(summaryText)(notice => s"$summaryText; $notice")

  private def applyFilter(): Unit = renderEntries()

  private def renderEntries(): Unit =
    val selectedWork =
      Option(list.getSelectedValue).orElse(pendingWorkSelection)
    entries.clear()
    BendProofProgressModel
      .filtered(workItems, filter.getText)
      .foreach { entry =>
        val _ = entries.addElement(entry)
      }
    selectedWork.foreach { previous =>
      val candidates = (0 until entries.size()).filter(index =>
        val current = entries.getElementAt(index)
        current.kind == previous.kind && current.path == previous.path &&
        current.name == previous.name
      )
      candidates
        .find(index =>
          val current = entries.getElementAt(index)
          current.offset == previous.offset
        )
        .orElse(candidates.headOption)
        .foreach(list.setSelectedIndex)
    }
    inventoryEntries.clear()
    BendProofProgressModel
      .filtered(allEntries, filter.getText)
      .foreach { entry =>
        val _ = inventoryEntries.addElement(entry)
      }
    tabs.setTitleAt(0, s"Work to do (${entries.getSize})")
    tabs.setTitleAt(
      1,
      s"Laws and candidate fills (${inventoryEntries.getSize})"
    )
    updateWorkSelection()

  private def renderer(work: Boolean): DefaultListCellRenderer =
    new DefaultListCellRenderer:
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
        value match
          case entry: BendProofInventoryEntry =>
            val source = Option(Path.of(entry.path).getFileName)
              .fold(entry.path)(_.toString)
            val prefix = if work then
              entry.kind match
                case BendProofInventoryKind.Hole => "Hole"
                case BendProofInventoryKind.Law  => "No candidate fill found"
                case _                           => entry.kind.toString
            else if entry.kind == BendProofInventoryKind.CandidateFill then
              "    ↳ Candidate fill"
            else entry.kind.toString
            setText(s"$prefix: ${entry.name} — $source")
            setToolTipText(entry.path)
          case _ => setToolTipText(null)
        rendered

  private def updateWorkSelection(): Unit =
    val selected = Option(list.getSelectedValue)
    openButton.setEnabled(selected.nonEmpty)
    generateButton.setEnabled(
      selected.exists(_.kind == BendProofInventoryKind.Law)
    )
    nextHoleButton.setEnabled(
      (0 until entries.getSize).exists(index =>
        entries.getElementAt(index).kind == BendProofInventoryKind.Hole
      )
    )
    detail.setText(selected match
      case Some(entry) if entry.kind == BendProofInventoryKind.Hole =>
        "Open selects the hole for editing. Goal and local context are not available in this plugin yet; check the root after editing."
      case Some(_) if latestSnapshot.exists(_.inventoryLimited) =>
        "No candidate fill found in this capped source inventory. Generation rechecks the selected root before inserting."
      case Some(_) =>
        "Generate an incomplete fill in this root, edit its ?TODO hole, then check the root."
      case None
          if workItems.isEmpty &&
            latestSnapshot.exists(_.checkedStatus.contains("failed")) =>
        "No source work items found. The root check failed; inspect its compiler diagnostics."
      case None if workItems.isEmpty && latestSnapshot.nonEmpty =>
        "No source holes or unmatched laws found. Check the root for the compiler verdict."
      case None =>
        "Select a work item to open it or generate an incomplete fill.")

  private def openNextHole(): Unit =
    val visible = (0 until entries.getSize)
      .filter(index =>
        entries.getElementAt(index).kind == BendProofInventoryKind.Hole
      )
    if visible.nonEmpty then
      val next = visible.find(_ > list.getSelectedIndex).getOrElse(visible.head)
      list.setSelectedIndex(next)
      open(entries.getElementAt(next))

  private def generateFill(entry: BendProofInventoryEntry): Unit =
    selectedRoot.foreach { rootPath =>
      val ticket = selectionGeneration.get()
      ProgressManager
        .getInstance()
        .run(
          new Task.Backgroundable(project, "Finding selected Bend law", true):
            private var lawPointer: Option[SmartPsiElementPointer[BendLaw]] =
              None

            override def run(indicator: ProgressIndicator): Unit =
              if !indicator.isCanceled then
                val sourceModificationCount = ReadAction.compute(() =>
                  PsiModificationTracker
                    .getInstance(project)
                    .getModificationCount
                )
                reader.navigationTarget(entry).foreach { _ =>
                  lawPointer = ReadAction.compute(() =>
                    if PsiModificationTracker
                        .getInstance(project)
                        .getModificationCount != sourceModificationCount
                    then None
                    else
                      BendPhysicalTargets
                        .file(project, entry.sourceId)
                        .filter(file =>
                          file.isValid && entry.offset < file.getTextLength
                        )
                        .flatMap(file =>
                          Option(file.findElementAt(entry.offset))
                            .flatMap(element =>
                              Option(
                                PsiTreeUtil.getParentOfType(
                                  element,
                                  classOf[BendLaw]
                                )
                              )
                            )
                        )
                        .filter(law =>
                          law.getNameIdentifier.getTextOffset == entry.offset &&
                            law.getName == entry.name
                        )
                        .map(law =>
                          SmartPointerManager
                            .getInstance(project)
                            .createSmartPsiElementPointer(law)
                        )
                  )
                }

            override def onSuccess(): Unit =
              if selectionCurrent(rootPath, ticket) &&
                selectedRoot.contains(rootPath)
              then
                lawPointer match
                  case Some(pointer) =>
                    new BendGenerateLawFillAction().generateForRoot(
                      project,
                      pointer,
                      rootPath,
                      () => selectionCurrent(rootPath, ticket)
                    )
                  case None =>
                    detail.setText(
                      "The law changed; refresh proof work before generating a fill."
                    )
        )
    }

  private def open(entry: BendProofInventoryEntry): Unit =
    val rootPath = selectedRoot
    val ticket = selectionGeneration.get()
    val sourceTicket = sourceGeneration.get()
    ProgressManager
      .getInstance()
      .run(
        new Task.Backgroundable(project, "Opening Bend proof item", true):
          override def run(indicator: ProgressIndicator): Unit =
            if !indicator.isCanceled then
              val target = reader.navigationTarget(entry)
              if !indicator.isCanceled then
                ApplicationManager.getApplication.invokeLater(
                  new Runnable:
                    override def run(): Unit =
                      if rootPath.exists(path => selectionCurrent(path, ticket))
                      then
                        if sourceGeneration.get() != sourceTicket then
                          detail.setText(
                            "Source changed; refresh proof progress before opening this row."
                          )
                        else
                          target match
                            case Some(value) =>
                              val editor = FileEditorManager
                                .getInstance(project)
                                .openTextEditor(
                                  new OpenFileDescriptor(
                                    project,
                                    value.file,
                                    value.offset
                                  ),
                                  true
                                )
                              if entry.kind == BendProofInventoryKind.Hole then
                                Option(editor).foreach { opened =>
                                  val end = value.offset + entry.name.length
                                  val document = opened.getDocument
                                  if end <= document.getTextLength &&
                                    document.getText
                                      .substring(
                                        value.offset,
                                        end
                                      ) == entry.name
                                  then
                                    opened.getSelectionModel
                                      .setSelection(value.offset, end)
                                }
                            case None =>
                              detail.setText(
                                "Source changed; refresh proof progress before opening this row."
                              )
                  ,
                  ModalityState.any()
                )
      )

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
