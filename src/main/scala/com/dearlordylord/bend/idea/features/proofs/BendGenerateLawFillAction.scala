package com.dearlordylord.bend.idea.features.proofs

import com.dearlordylord.bend.idea.workspace.api.BendPathInventoryStatus
import com.intellij.notification.{NotificationGroupManager, NotificationType}
import com.intellij.openapi.actionSystem.{
  AnAction,
  AnActionEvent,
  ActionUpdateThread,
  CommonDataKeys
}
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.{FileEditorManager, OpenFileDescriptor}
import com.intellij.openapi.progress.{ProgressIndicator, Task}
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.psi.{PsiFile, SmartPsiElementPointer, SmartPointerManager}
import com.intellij.psi.util.PsiTreeUtil
import com.dearlordylord.bend.idea.syntax.psi.BendLaw
import scala.jdk.CollectionConverters.*

/** Explicitly generates a source-level implementation skeleton for a law. */
final class BendGenerateLawFillAction
    extends AnAction("Generate Bend Law Fill"):
  private final case class Discovery(
      roots: BendProofRootInventory,
      fillSearch: BendProofFillSearch
  )

  override def getActionUpdateThread: ActionUpdateThread =
    ActionUpdateThread.BGT

  override def update(event: AnActionEvent): Unit =
    val editor = event.getData(CommonDataKeys.EDITOR)
    val file = Option(event.getData(CommonDataKeys.PSI_FILE))
    val enabled = editor != null && file.exists(
      currentLaw(_, editor.getCaretModel.getOffset).nonEmpty
    )
    event.getPresentation.setVisible(true)
    event.getPresentation.setEnabled(enabled)
    event.getPresentation.setDescription(
      if enabled then "Insert an incomplete law fill into a selected proof root"
      else "Place the caret on a law declaration to enable law-fill generation"
    )

  override def actionPerformed(event: AnActionEvent): Unit =
    val project = event.getProject
    val editor = event.getData(CommonDataKeys.EDITOR)
    if project == null || editor == null then return
    val lawPointer = ReadAction.compute(() =>
      Option(event.getData(CommonDataKeys.PSI_FILE)).flatMap(file =>
        currentLaw(file, editor.getCaretModel.getOffset).map(law =>
          SmartPointerManager
            .getInstance(project)
            .createSmartPsiElementPointer(law)
        )
      )
    )
    lawPointer match
      case None =>
        notify(
          project,
          "Place the caret on a Bend law declaration",
          NotificationType.WARNING
        )
      case Some(pointer) => discover(project, editor, pointer)

  private def discover(
      project: Project,
      editor: Editor,
      law: SmartPsiElementPointer[BendLaw]
  ): Unit =
    new Task.Backgroundable(project, "Finding Bend proof roots", true):
      private var result: Option[Discovery] = None

      override def run(indicator: ProgressIndicator): Unit =
        val file = ReadAction.compute(() =>
          Option(law.getElement).filter(_.isValid).map(_.getContainingFile)
        )
        file.foreach { sourceFile =>
          val roots =
            ReadAction.compute(() => BendProofNavigation.roots(sourceFile))
          val targets = BendProofFillGenerator.candidates(
            law,
            roots.paths,
            () => indicator.isCanceled
          )
          if !indicator.isCanceled then
            targets.foreach(values => result = Some(Discovery(roots, values)))
        }

      override def onSuccess(): Unit =
        if project.isDisposed then return
        result match
          case None =>
            BendGenerateLawFillAction.this.notify(
              project,
              "The law changed while finding proof roots; run generation again",
              NotificationType.WARNING
            )
          case Some(discovery) if discovery.fillSearch.targets.isEmpty =>
            val notice = BendPathInventoryStatus
              .notice(discovery.roots.status)
              .fold("")(message => s" $message")
            val capNotice = sourceInventoryNotice(discovery.fillSearch)
            BendGenerateLawFillAction.this.notify(
              project,
              s"No writable proof root exposes this law without an existing fill.$notice$capNotice",
              NotificationType.WARNING
            )
          case Some(discovery)
              if shouldAutoInsert(
                discovery.roots.status,
                discovery.fillSearch
              ) =>
            validateAndInsert(
              project,
              law,
              discovery.fillSearch.targets.head,
              automaticSelection = true
            )
          case Some(discovery) =>
            val baseTitle = BendPathInventoryStatus
              .notice(discovery.roots.status)
              .fold("Generate law fill in proof root")(notice =>
                s"Generate law fill in proof root — $notice"
              )
            val title =
              if discovery.fillSearch.sourceInventoryCapped then
                s"$baseTitle — source inventory capped; results may be incomplete"
              else baseTitle
            JBPopupFactory
              .getInstance()
              .createPopupChooserBuilder(discovery.fillSearch.targets.asJava)
              .setTitle(title)
              .setItemChosenCallback(target =>
                validateAndInsert(
                  project,
                  law,
                  target,
                  automaticSelection = false
                )
              )
              .createPopup()
              .showInBestPositionFor(editor)
    .queue()

  private[proofs] def shouldAutoInsert(
      rootInventoryStatus: BendPathInventoryStatus,
      search: BendProofFillSearch
  ): Boolean =
    rootInventoryStatus == BendPathInventoryStatus.Complete &&
      search.targets.size == 1 && !search.sourceInventoryCapped

  private def sourceInventoryNotice(search: BendProofFillSearch): String =
    if search.sourceInventoryCapped then
      s" Source inventory limits were reached in: ${search.cappedRootPaths.mkString(", ")}; more matches may exist."
    else ""

  private[proofs] def mayInsertAfterRefresh(
      automaticSelection: Boolean,
      refreshed: BendProofFillSearch
  ): Boolean =
    !automaticSelection || !refreshed.sourceInventoryCapped

  private def validateAndInsert(
      project: Project,
      law: SmartPsiElementPointer[BendLaw],
      selected: BendProofFillTarget,
      automaticSelection: Boolean
  ): Unit =
    new Task.Backgroundable(project, "Rechecking Bend law visibility", true):
      private var refreshed: Option[BendProofFillSearch] = None

      override def run(indicator: ProgressIndicator): Unit =
        refreshed = BendProofFillGenerator.refreshCandidate(
          law,
          selected,
          () => indicator.isCanceled
        )

      override def onSuccess(): Unit =
        if project.isDisposed then return
        refreshed match
          case None =>
            BendGenerateLawFillAction.this.notify(
              project,
              "The law, proof-root imports, or loading settings changed; generate the fill again",
              NotificationType.WARNING
            )
          case Some(search) if search.targets.isEmpty =>
            BendGenerateLawFillAction.this.notify(
              project,
              "The law, proof-root imports, or loading settings changed; generate the fill again",
              NotificationType.WARNING
            )
          case Some(search)
              if !mayInsertAfterRefresh(automaticSelection, search) =>
            BendGenerateLawFillAction.this.notify(
              project,
              s"The proof-root source inventory became capped during automatic selection.${sourceInventoryNotice(search)} Run generation again and choose a target explicitly.",
              NotificationType.WARNING
            )
          case Some(search) => insert(project, search.targets.head)
    .queue()

  private def currentLaw(file: PsiFile, offset: Int): Option[BendLaw] =
    if file.getTextLength == 0 then None
    else
      Option(
        file.findElementAt(
          math.max(0, math.min(offset, file.getTextLength - 1))
        )
      )
        .flatMap(element =>
          Option(PsiTreeUtil.getParentOfType(element, classOf[BendLaw]))
        )

  private def insert(
      project: Project,
      target: BendProofFillTarget
  ): Unit =
    BendProofFillGenerator.insert(project, target) match
      case Left(reason)  => notify(project, reason, NotificationType.WARNING)
      case Right(result) =>
        Option(
          FileEditorManager
            .getInstance(project)
            .openTextEditor(
              new OpenFileDescriptor(
                project,
                result.file,
                result.placeholderStart
              ),
              true
            )
        ).foreach { editor =>
          editor.getSelectionModel.setSelection(
            result.placeholderStart,
            result.placeholderEnd
          )
        }
        notify(
          project,
          "Law fill skeleton inserted; ?TODO remains incomplete until replaced",
          NotificationType.INFORMATION
        )

  private def notify(
      project: Project,
      message: String,
      kind: NotificationType
  ): Unit =
    NotificationGroupManager
      .getInstance()
      .getNotificationGroup("Bend")
      .createNotification(message, kind)
      .notify(project)
