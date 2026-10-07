package com.dearlordylord.bend.idea.features.proofs

import com.dearlordylord.bend.idea.analysis.api.BendCheckService
import com.dearlordylord.bend.idea.analysis.model.BendCheckingStatus
import com.dearlordylord.bend.idea.model.FileId
import com.dearlordylord.bend.idea.workspace.model.BendLoadedGraph
import com.dearlordylord.bend.idea.symbols.api.*
import com.dearlordylord.bend.idea.syntax.psi.{
  BendDeclaration,
  BendDefinition,
  BendLaw,
  BendProofForm,
  BendProofSurface
}
import com.dearlordylord.bend.idea.workspace.api.{
  BendLoadingConfiguration,
  BendLoadingConfigurationSnapshot,
  BendNamedPathInventory,
  BendPathInventoryStatus,
  BendSourceCatalog,
  BendWorkspaceGraph,
  BendWorkspacePaths
}
import com.intellij.notification.{NotificationGroupManager, NotificationType}
import com.intellij.openapi.actionSystem.{
  AnAction,
  AnActionEvent,
  ActionUpdateThread,
  CommonDataKeys
}
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.progress.{ProgressIndicator, Task}
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.{VirtualFile, VirtualFileManager}
import com.intellij.psi.{PsiDocumentManager, PsiElement, PsiFile}
import com.intellij.psi.util.{PsiModificationTracker, PsiTreeUtil}
import scala.jdk.CollectionConverters.*

final case class BendProofDestination(
    rootPath: String,
    rootId: FileId,
    symbol: BendSourceDeclarationFact,
    target: PsiElement,
    status: BendCheckingStatus
):
  override def toString: String =
    val source = symbol.handle.file.value
    s"${symbol.name} — $rootPath — ${BendCheckingStatus.label(status)} — $source"

private[proofs] final case class BendNavigationTarget(
    file: VirtualFile,
    offset: Int
)

/** Root-relative source links. These destinations do not assert compiler
  * success; the attached state is only the selected root's latest check status.
  */
object BendProofNavigation:
  private val RootLimit = 32
  private val GraphFileLimit = 256
  private val SourceCharacterLimit = 250000
  private val SourceDeclarationLimit = 1024
  private val RootDeclarationLimit = 4096
  private val RootDestinationLimit = 128

  private final case class SearchSnapshot(
      sourceModificationCount: Long,
      vfsModificationCount: Long,
      loadingConfigurationRevision: Long,
      roots: BendProofRootInventory,
      symbol: Option[SelectedSymbol],
      rootStatuses: List[RootStatus],
      destinations: List[BendProofDestination]
  )
  private final case class SelectedSymbol(
      handle: BendSourceHandle,
      name: String,
      category: BendSymbolCategory
  )
  private final case class RootStatus(
      path: String,
      status: BendCheckingStatus,
      sourceInventoryCapped: Boolean = false
  )
  private final case class SearchCapture(
      sourceModificationCount: Long,
      vfsModificationCount: Long,
      roots: BendProofRootInventory,
      symbol: Option[SelectedSymbol],
      loadingConfiguration: BendLoadingConfigurationSnapshot
  )

  def roots(origin: PsiFile): BendProofRootInventory =
    val project = origin.getProject
    val currentPath = Option(origin.getVirtualFile).map(_.getPath)
    val saved = project.getService(classOf[BendProofRootStore]).selectedPaths
    val lawIndex = currentPath.exists(path =>
      java.nio.file.Path.of(path).getFileName.toString == "LAWS.bend"
    )
    val discovered =
      if saved.isEmpty || lawIndex then
        project
          .getService(classOf[BendWorkspacePaths])
          .filesNamed("PROOF.bend", RootLimit)
      else BendNamedPathInventory(Nil, BendPathInventoryStatus.Complete)
    val conventional = discovered.paths
    val sibling = Option(origin.getVirtualFile)
      .filter(_.getName == "LAWS.bend")
      .flatMap(file => Option(file.getParent))
      .flatMap(parent => Option(parent.findChild("PROOF.bend")))
      .filter(file => file.isValid && !file.isDirectory)
      .map(_.getPath)
      .toList
    val suggestions =
      if saved.isEmpty then sibling ++ conventional
      else sibling
    BendProofRootInventory(
      BendProofRootSelection.candidates(currentPath, saved, suggestions),
      discovered.status
    )

  def destinations(
      origin: PsiFile,
      selected: BendSourceSymbol,
      rootPaths: List[String]
  ): List[BendProofDestination] =
    val project = origin.getProject
    // Discovering a previously unseen VFS dependency can advance its revision.
    // Retry once from fresh inputs rather than returning a false empty result.
    (0 until 3).iterator
      .map { _ =>
        val (
          sourceModificationCount,
          vfsModificationCount,
          loadingConfiguration
        ) =
          ReadAction.compute(() =>
            (
              PsiModificationTracker.getInstance(project).getModificationCount,
              VirtualFileManager.getInstance().getModificationCount,
              project.getService(classOf[BendLoadingConfiguration]).snapshot
            )
          )
        destinationsForRoots(
          origin,
          SelectedSymbol(selected.handle, selected.name, selected.category),
          rootPaths,
          sourceModificationCount,
          vfsModificationCount,
          loadingConfiguration,
          () => false
        )
      }
      .collectFirst { case Some((_, found)) => found }
      .getOrElse(Nil)

  private def linkRevision(project: Project): BendProofLinkRevision =
    BendProofLinkRevision(
      PsiModificationTracker.getInstance(project).getModificationCount,
      VirtualFileManager.getInstance().getModificationCount,
      project
        .getService(classOf[BendLoadingConfiguration])
        .configurationRevision
    )

  private def inputsCurrent(
      origin: PsiFile,
      expected: BendProofLinkRevision
  ): Boolean =
    !origin.getProject.isDisposed && origin.isValid &&
      !PsiDocumentManager
        .getInstance(origin.getProject)
        .hasUncommitedDocuments &&
      linkRevision(origin.getProject) == expected

  private def rootInventory(
      origin: PsiFile,
      path: String,
      expected: BendProofLinkRevision,
      configuration: BendLoadingConfigurationSnapshot,
      canceled: () => Boolean
  ): Option[BendProofLinkInventory] =
    val project = origin.getProject
    val cache = project.getService(classOf[BendProofLinkCache])
    if canceled() || !ReadAction.compute(() => inputsCurrent(origin, expected))
    then return None
    val cached = ReadAction.compute(() =>
      if inputsCurrent(origin, expected) then cache.get(path, expected)
      else None
    )
    if canceled() then return None
    val graphService = project.getService(classOf[BendWorkspaceGraph])
    val reusable = cached.filter(inventory =>
      !inventory.capped && project
        .getService(classOf[BendSourceCatalog])
        .source(path)
        .exists(record =>
          inventory.graph
            .source(inventory.root)
            .exists(before =>
              record.id == before.id && record.revision == before.revision && record.text == before.text
            )
        ) && graphService.current(
        inventory.graph,
        configuration.packageCache,
        canceled
      )
    )
    if reusable.nonEmpty && ReadAction.compute(() =>
        inputsCurrent(origin, expected)
      )
    then return reusable
    val root = project.getService(classOf[BendSourceCatalog]).source(path)
    root match
      case None       => None
      case Some(root) =>
        val graph = project
          .getService(classOf[BendWorkspaceGraph])
          .load(
            root,
            configuration.baseSource,
            configuration.packageCache,
            canceled
          )
        if canceled() then return None
        val accepted =
          Set(BendSymbolCategory.Law, BendSymbolCategory.Definition)
        val relevantIds = BendSourceDocumentation.relevantSourceIds(graph)
        val relevantFiles =
          graph.files.filter(file => relevantIds.contains(file.source.id))
        var inventoryCapped =
          graph.sourceInventoryCapped || relevantFiles.size > GraphFileLimit
        val facts = scala.collection.mutable.ListBuffer.empty[
          BendSourceDeclarationFact
        ]
        val sourceIterator = relevantFiles.take(GraphFileLimit).iterator
        while sourceIterator.hasNext && facts.size < RootDeclarationLimit &&
          !canceled()
        do
          val loaded = sourceIterator.next()
          val remaining = math.min(
            SourceDeclarationLimit,
            RootDeclarationLimit - facts.size
          )
          val scan = ReadAction.compute(() =>
            if !inputsCurrent(origin, expected) then None
            else
              BendSourceSymbols
                .sourceDeclarationsBounded(
                  project,
                  loaded.source.id,
                  loaded.source.text,
                  remaining,
                  SourceCharacterLimit,
                  accepted,
                  canceled
                )
                .map(result =>
                  (
                    result.symbols.map(BendSourceSymbols.declarationFact),
                    result.truncated
                  )
                )
          )
          scan match
            case None                           => return None
            case Some((sourceFacts, truncated)) =>
              facts ++= sourceFacts
              if truncated then inventoryCapped = true
        if facts.size == RootDeclarationLimit then inventoryCapped = true
        if canceled() then return None

        val sourceFacts = facts.toList
        val lawsByFill = BendSourceDocumentation.linkedFills(graph, sourceFacts)
        val declarations = sourceFacts.map(fact => fact.handle -> fact).toMap
        val fillsByLaw = lawsByFill.toList
          .groupMap(_._2.handle)(_._1)
          .view
          .mapValues(_.flatMap(declarations.get))
          .toMap
        val inventory = BendProofLinkInventory(
          graph,
          root.id,
          declarations,
          lawsByFill,
          fillsByLaw,
          graph.files
            .filter(_.source.text.length > SourceCharacterLimit)
            .map(_.source.id)
            .toSet,
          inventoryCapped
        )
        if !graphService.current(graph, configuration.packageCache, canceled)
        then return None
        ReadAction.compute(() =>
          if canceled() || !inputsCurrent(origin, expected) then None
          else
            if !inventory.capped then cache.put(path, expected, inventory)
            Some(inventory)
        )

  private def destinationsForRoots(
      origin: PsiFile,
      selected: SelectedSymbol,
      rootPaths: List[String],
      expectedModificationCount: Long,
      expectedVfsModificationCount: Long,
      loadingConfiguration: BendLoadingConfigurationSnapshot,
      canceled: () => Boolean
  ): Option[(List[RootStatus], List[BendProofDestination])] =
    val project = origin.getProject
    val observed = scala.collection.mutable.ListBuffer.empty[BendLoadedGraph]
    val statuses = scala.collection.mutable.ListBuffer.empty[RootStatus]
    val destinations = scala.collection.mutable.ListBuffer.empty[
      BendProofDestination
    ]
    val iterator = rootPaths.iterator
    while iterator.hasNext && !canceled() do
      val path = iterator.next()
      val expected = BendProofLinkRevision(
        expectedModificationCount,
        expectedVfsModificationCount,
        loadingConfiguration.configurationRevision
      )
      rootInventory(
        origin,
        path,
        expected,
        loadingConfiguration,
        canceled
      ) match
        case None =>
          if project
              .getService(classOf[BendSourceCatalog])
              .source(path)
              .nonEmpty
          then return None
          if canceled() || !ReadAction.compute(() =>
              inputsCurrent(origin, expected)
            )
          then return None
          statuses += RootStatus(path, BendCheckingStatus.Unavailable)
        case Some(inventory) =>
          observed += inventory.graph
          var inventoryCapped = inventory.capped
          val related = selected.category match
            case BendSymbolCategory.Law =>
              inventory.fillsByLaw.getOrElse(selected.handle, Nil)
            case BendSymbolCategory.Definition =>
              inventory.lawsByFill.get(selected.handle).toList
            case _ => Nil
          if related.size > RootDestinationLimit then inventoryCapped = true
          val boundedRelated = related.take(RootDestinationLimit)
          val targetGroups = boundedRelated.groupBy(_.handle.file).toList
          val status = currentStatus(project, inventory.root)
          val rootDestinations = scala.collection.mutable.ListBuffer.empty[
            BendProofDestination
          ]
          val targetIterator = targetGroups.iterator
          while targetIterator.hasNext && !canceled() do
            val (sourceId, targetFacts) = targetIterator.next()
            val sourceIsLarge = inventory.largeSources.contains(sourceId)
            if sourceIsLarge then inventoryCapped = true
            else
              val resolved = ReadAction.compute(() =>
                if !inputsCurrent(origin, expected) then None
                else
                  val values = BendPhysicalTargets
                    .file(project, sourceId)
                    .filter(_.getTextLength <= SourceCharacterLimit)
                    .toList
                    .flatMap(file =>
                      targetFacts.flatMap(fact =>
                        BendPhysicalTargets
                          .declaration(file, fact)
                          .map(target =>
                            BendProofDestination(
                              path,
                              inventory.root,
                              fact,
                              target,
                              status
                            )
                          )
                      )
                    )
                  Some(values)
              )
              resolved match
                case None         => return None
                case Some(values) => rootDestinations ++= values
          statuses += RootStatus(path, status, inventoryCapped)
          destinations ++= rootDestinations
    if canceled() || !observed.forall(graph =>
        project
          .getService(classOf[BendWorkspaceGraph])
          .current(
            graph,
            loadingConfiguration.packageCache,
            canceled
          )
      ) || !ReadAction.compute(() =>
        inputsCurrent(
          origin,
          BendProofLinkRevision(
            expectedModificationCount,
            expectedVfsModificationCount,
            loadingConfiguration.configurationRevision
          )
        )
      )
    then None
    else
      Some(
        (
          statuses.toList,
          destinations.toList.distinctBy(destination =>
            (
              destination.rootId,
              destination.symbol.handle,
              destination.symbol.handle.nameOffset
            )
          )
        )
      )

  def openFromGutter(element: PsiElement): Unit =
    Option(PsiTreeUtil.getParentOfType(element, classOf[BendDeclaration]))
      .foreach(openRelated)

  private def openRelated(declaration: BendDeclaration): Unit =
    PsiDocumentManager.getInstance(declaration.getProject).commitAllDocuments()
    if !declaration.isValid then return
    val file = declaration.getContainingFile
    val selected = BendSourceSymbols
      .declarations(file)
      .find(symbol =>
        symbol.handle.category == category(declaration) &&
          symbol.handle.nameOffset == declaration.getNameIdentifier.getTextOffset &&
          symbol.name == declaration.getName
      )
    selected match
      case None =>
        notify(
          file.getProject,
          "The declaration changed; reload its proof link"
        )
      case Some(symbol) => findDestinations(file, symbol)

  private def findDestinations(
      file: PsiFile,
      selected: BendSourceSymbol
  ): Unit = searchTask(file, selected).queue()

  private[proofs] def searchTask(
      file: PsiFile,
      selected: BendSourceSymbol
  ): Task.Backgroundable =
    val project = file.getProject
    new Task.Backgroundable(project, "Finding Bend laws and proofs", true):
      private var snapshot: Option[Either[String, SearchSnapshot]] = None

      override def run(indicator: ProgressIndicator): Unit =
        val result = (0 until 3).iterator
          .takeWhile(_ => !indicator.isCanceled)
          .map { _ =>
            val captured = ReadAction.compute(() =>
              val rootInventory = roots(file)
              val refreshed = BendSourceSymbols
                .declarations(file)
                .find(_.handle == selected.handle)
              SearchCapture(
                PsiModificationTracker
                  .getInstance(project)
                  .getModificationCount,
                VirtualFileManager.getInstance().getModificationCount,
                rootInventory,
                refreshed.map(symbol =>
                  SelectedSymbol(symbol.handle, symbol.name, symbol.category)
                ),
                project.getService(classOf[BendLoadingConfiguration]).snapshot
              )
            )
            captured.symbol match
              case None =>
                Some(
                  SearchSnapshot(
                    captured.sourceModificationCount,
                    captured.vfsModificationCount,
                    captured.loadingConfiguration.configurationRevision,
                    captured.roots,
                    None,
                    Nil,
                    Nil
                  )
                )
              case Some(symbol) =>
                destinationsForRoots(
                  file,
                  symbol,
                  captured.roots.paths,
                  captured.sourceModificationCount,
                  captured.vfsModificationCount,
                  captured.loadingConfiguration,
                  () => indicator.isCanceled
                ).map { case (rootStatuses, destinations) =>
                  SearchSnapshot(
                    captured.sourceModificationCount,
                    captured.vfsModificationCount,
                    captured.loadingConfiguration.configurationRevision,
                    captured.roots,
                    Some(symbol),
                    rootStatuses,
                    destinations
                  )
                }
          }
          .collectFirst { case Some(found) => found }
        if !indicator.isCanceled then
          snapshot = Some(
            result.toRight(
              "Bend sources or loading settings changed while finding proof links; click again"
            )
          )

      override def onSuccess(): Unit =
        if project.isDisposed || !file.isValid then return
        snapshot match
          case None =>
            BendProofNavigation.notify(
              project,
              "Could not capture Bend proof links; click again"
            )
          case Some(Left(message)) =>
            BendProofNavigation.notify(project, message)
          case Some(Right(result)) if !isCurrent(project, result) =>
            BendProofNavigation.notify(
              project,
              "Bend sources or loading settings changed while finding proof links; click again"
            )
          case Some(Right(result)) =>
            result.symbol match
              case None =>
                BendProofNavigation.notify(
                  project,
                  "The declaration changed; reload its proof link"
                )
              case Some(symbol) if result.destinations.isEmpty =>
                notifyNoCandidates(
                  project,
                  symbol,
                  result.rootStatuses,
                  result.roots.status
                )
              case Some(_)
                  if !requiresStatusChoice(
                    result.destinations,
                    result.roots.status,
                    result.rootStatuses.exists(_.sourceInventoryCapped)
                  ) =>
                navigate(
                  project,
                  result.destinations.head,
                  result.sourceModificationCount,
                  result.loadingConfigurationRevision,
                  result.vfsModificationCount
                )
              case Some(_) =>
                JBPopupFactory
                  .getInstance()
                  .createPopupChooserBuilder(result.destinations.asJava)
                  .setTitle(
                    candidateChooserTitle(
                      result.roots.status,
                      result.rootStatuses.exists(_.sourceInventoryCapped)
                    )
                  )
                  .setItemChosenCallback(destination =>
                    navigate(
                      project,
                      destination,
                      result.sourceModificationCount,
                      result.loadingConfigurationRevision,
                      result.vfsModificationCount
                    )
                  )
                  .createPopup()
                  .showCenteredInCurrentWindow(project)

  private[proofs] def requiresStatusChoice(
      candidates: List[BendProofDestination],
      inventoryStatus: BendPathInventoryStatus =
        BendPathInventoryStatus.Complete,
      sourceInventoryCapped: Boolean = false
  ): Boolean =
    inventoryStatus != BendPathInventoryStatus.Complete ||
      sourceInventoryCapped ||
      candidates.size > 1 ||
      candidates.exists(_.status == BendCheckingStatus.Stale)

  private[proofs] def candidateChooserTitle(
      inventoryStatus: BendPathInventoryStatus,
      sourceInventoryCapped: Boolean = false
  ): String =
    val base = "Candidate law or proof declarations"
    val statusNotice = BendPathInventoryStatus
      .notice(inventoryStatus)
      .fold(base)(message => s"$base — $message")
    if sourceInventoryCapped then
      s"$statusNotice — search limited by source inventory cap"
    else statusNotice

  private def notifyNoCandidates(
      project: Project,
      selected: SelectedSymbol,
      candidateRoots: List[RootStatus],
      inventoryStatus: BendPathInventoryStatus
  ): Unit =
    val rootsWithStatus = candidateRoots.map { root =>
      val cap =
        if root.sourceInventoryCapped then "; source inventory capped"
        else ""
      s"${root.path} (${BendCheckingStatus.label(root.status)}$cap)"
    }
    val role = selected.category match
      case BendSymbolCategory.Law => "candidate fill"
      case _                      => "matching law"
    val rootDetail =
      if rootsWithStatus.isEmpty then
        "No proof roots are configured or suggested."
      else rootsWithStatus.mkString("Selected roots: ", "; ", ".")
    val inventoryNotice = BendPathInventoryStatus
      .notice(inventoryStatus)
      .fold("")(message => s" $message")
    val sourceInventoryNotice =
      if candidateRoots.exists(_.sourceInventoryCapped) then
        " Some root declaration searches reached their safety cap."
      else ""
    notify(
      project,
      s"No $role found for ${selected.name}. $rootDetail$inventoryNotice$sourceInventoryNotice Presence of a matching definition is not proof success."
    )

  private def currentStatus(
      project: Project,
      rootId: FileId
  ): BendCheckingStatus =
    val checks = project.getService(classOf[BendCheckService])
    val _ = checks.result(rootId)
    checks.status(rootId)

  private def category(declaration: BendDeclaration): BendSymbolCategory =
    declaration match
      case _: BendLaw        => BendSymbolCategory.Law
      case _: BendDefinition => BendSymbolCategory.Definition
      case _                 => BendSymbolCategory.Binder

  private def navigate(
      project: Project,
      destination: BendProofDestination,
      expectedModificationCount: Long,
      expectedConfigurationRevision: Long,
      expectedVfsModificationCount: Long
  ): Unit =
    navigationTarget(
      project,
      destination,
      expectedModificationCount,
      expectedConfigurationRevision,
      expectedVfsModificationCount
    ) match
      case Some(target) if !project.isDisposed =>
        new OpenFileDescriptor(project, target.file, target.offset)
          .navigate(true)
      case _ if !project.isDisposed =>
        notify(
          project,
          "Bend sources changed while finding proof links; click again"
        )
      case _ => ()

  private[proofs] def navigationTarget(
      project: Project,
      destination: BendProofDestination,
      expectedModificationCount: Long,
      expectedConfigurationRevision: Long,
      expectedVfsModificationCount: Long =
        VirtualFileManager.getInstance().getModificationCount
  ): Option[BendNavigationTarget] =
    ReadAction.compute(() => {
      val currentModificationCount =
        PsiModificationTracker.getInstance(project).getModificationCount
      val currentConfigurationRevision = project
        .getService(classOf[BendLoadingConfiguration])
        .configurationRevision
      if project.isDisposed ||
        PsiDocumentManager.getInstance(project).hasUncommitedDocuments ||
        currentModificationCount != expectedModificationCount ||
        VirtualFileManager
          .getInstance()
          .getModificationCount != expectedVfsModificationCount ||
        currentConfigurationRevision != expectedConfigurationRevision ||
        !destination.target.isValid
      then None
      else
        Option(destination.target.getContainingFile.getVirtualFile).map(file =>
          BendNavigationTarget(file, destination.target.getTextOffset)
        )
    })

  private def isCurrent(project: Project, result: SearchSnapshot): Boolean =
    ReadAction.compute(() =>
      !project.isDisposed && !PsiDocumentManager
        .getInstance(project)
        .hasUncommitedDocuments &&
        linkRevision(project) == BendProofLinkRevision(
          result.sourceModificationCount,
          result.vfsModificationCount,
          result.loadingConfigurationRevision
        )
    )

  private def notify(project: Project, message: String): Unit =
    NotificationGroupManager
      .getInstance()
      .getNotificationGroup("Bend")
      .createNotification(
        "Bend proof navigation",
        message,
        NotificationType.INFORMATION
      )
      .notify(project)

object BendHoleNavigation:
  def nextOffset(source: String, caret: Int): Option[Int] =
    val holes = BendProofSurface
      .scan(source)
      .collect { case hole: BendProofForm.Hole => hole.from }
      .sorted
    holes.find(_ > caret).orElse(holes.headOption)

  def previousOffset(source: String, caret: Int): Option[Int] =
    val holes = BendProofSurface
      .scan(source)
      .collect { case hole: BendProofForm.Hole => hole.from }
      .sorted
    holes.reverse.find(_ < caret).orElse(holes.lastOption)

abstract class BendHoleNavigationAction(forward: Boolean, name: String)
    extends AnAction(name):
  override def getActionUpdateThread: ActionUpdateThread =
    ActionUpdateThread.BGT

  override def update(event: AnActionEvent): Unit =
    val editor = event.getData(CommonDataKeys.EDITOR)
    val file = event.getData(CommonDataKeys.PSI_FILE)
    event.getPresentation.setEnabledAndVisible(
      editor != null && file != null && file.getName.endsWith(".bend")
    )

  override def actionPerformed(event: AnActionEvent): Unit =
    val editor = event.getData(CommonDataKeys.EDITOR)
    if editor == null then return
    val source = editor.getDocument.getText
    val caret = editor.getCaretModel.getOffset
    val target =
      if forward then BendHoleNavigation.nextOffset(source, caret)
      else BendHoleNavigation.previousOffset(source, caret)
    target match
      case Some(offset) =>
        editor.getCaretModel.moveToOffset(offset)
        editor.getScrollingModel.scrollToCaret(ScrollType.CENTER)
      case None =>
        com.intellij.codeInsight.hint.HintManager
          .getInstance()
          .showInformationHint(editor, "No named or TODO holes in this source")

final class BendNextHoleAction
    extends BendHoleNavigationAction(true, "Next Bend Proof Hole")

final class BendPreviousHoleAction
    extends BendHoleNavigationAction(false, "Previous Bend Proof Hole")
