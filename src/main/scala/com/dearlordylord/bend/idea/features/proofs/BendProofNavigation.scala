package com.dearlordylord.bend.idea.features.proofs

import com.dearlordylord.bend.idea.analysis.api.BendCheckService
import com.dearlordylord.bend.idea.analysis.model.BendCheckingStatus
import com.dearlordylord.bend.idea.model.FileId
import com.dearlordylord.bend.idea.symbols.api.*
import com.dearlordylord.bend.idea.syntax.psi.{
  BendDeclaration,
  BendDefinition,
  BendLaw,
  BendProofForm,
  BendProofSurface
}
import com.dearlordylord.bend.idea.workspace.api.BendWorkspacePaths
import com.intellij.notification.{NotificationGroupManager, NotificationType}
import com.intellij.openapi.actionSystem.{
  AnAction,
  AnActionEvent,
  CommonDataKeys
}
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.psi.{PsiElement, PsiFile}
import com.intellij.psi.util.PsiTreeUtil
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

final case class BendProofDestination(
    rootPath: String,
    rootId: FileId,
    symbol: BendSourceSymbol,
    target: PsiElement,
    status: BendCheckingStatus
):
  override def toString: String =
    val source = symbol.handle.file.value
    s"${symbol.name} — $rootPath — ${BendCheckingStatus.label(status)} — $source"

/** Root-relative source links. These destinations do not assert compiler
  * success; the attached state is only the selected root's latest check status.
  */
object BendProofNavigation:
  private val RootLimit = 128

  def roots(origin: PsiFile): List[String] =
    val project = origin.getProject
    val currentPath = Option(origin.getVirtualFile).map(_.getPath)
    val saved = project.getService(classOf[BendProofRootStore]).selectedPaths
    val conventional = project
      .getService(classOf[BendWorkspacePaths])
      .filesNamed("PROOF.bend", RootLimit)
    (currentPath.toList ++ BendProofRootSelection.candidates(
      currentPath,
      saved,
      conventional
    )).distinct

  def destinations(
      origin: PsiFile,
      selected: BendSourceSymbol,
      rootPaths: List[String]
  ): List[BendProofDestination] =
    val project = origin.getProject
    rootPaths
      .flatMap { path =>
        rootFile(origin, path).toList.flatMap { requestRoot =>
          try
            val site = BendSourceDocumentation.site(requestRoot, selected)
            val related = selected.category match
              case BendSymbolCategory.Law        => site.fills
              case BendSymbolCategory.Definition => site.law.toList
              case _                             => Nil
            related.flatMap { symbol =>
              BendPhysicalTargets
                .file(project, symbol.handle.file)
                .flatMap(BendPhysicalTargets.declaration(_, symbol))
                .map { target =>
                  val rootId = BendSourceSymbols.fileId(requestRoot)
                  BendProofDestination(
                    path,
                    rootId,
                    symbol,
                    target,
                    currentStatus(project, rootId)
                  )
                }
            }
          catch case NonFatal(_) => Nil
        }
      }
      .distinctBy(destination =>
        (
          destination.rootId,
          destination.symbol.handle,
          destination.target.getTextOffset
        )
      )

  def openFromGutter(element: PsiElement): Unit =
    Option(PsiTreeUtil.getParentOfType(element, classOf[BendDeclaration]))
      .foreach(openRelated)

  private def openRelated(declaration: BendDeclaration): Unit =
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
      case Some(symbol) =>
        val candidates = destinations(file, symbol, roots(file))
        if candidates.isEmpty then notifyNoCandidates(file, symbol)
        else if candidates.size == 1 && !requiresStatusChoice(candidates) then
          navigate(file.getProject, candidates.head)
        else
          JBPopupFactory
            .getInstance()
            .createPopupChooserBuilder(candidates.asJava)
            .setTitle("Candidate law or proof declarations")
            .setItemChosenCallback(destination =>
              navigate(file.getProject, destination)
            )
            .createPopup()
            .showCenteredInCurrentWindow(file.getProject)

  private[proofs] def requiresStatusChoice(
      candidates: List[BendProofDestination]
  ): Boolean =
    candidates.size > 1 || candidates.exists(
      _.status == BendCheckingStatus.Stale
    )

  private def notifyNoCandidates(
      file: PsiFile,
      selected: BendSourceSymbol
  ): Unit =
    val rootsWithStatus = roots(file).map { path =>
      val rootId = rootFile(file, path)
        .map(BendSourceSymbols.fileId)
        .getOrElse(new FileId(path, canonical = false))
      s"$path (${BendCheckingStatus.label(currentStatus(file.getProject, rootId))})"
    }
    val role = selected.category match
      case BendSymbolCategory.Law => "candidate fill"
      case _                      => "matching law"
    val rootDetail =
      if rootsWithStatus.isEmpty then
        "No proof roots are configured or suggested."
      else rootsWithStatus.mkString("Selected roots: ", "; ", ".")
    notify(
      file.getProject,
      s"No $role found for ${selected.name}. $rootDetail Presence of a matching definition is not proof success."
    )

  private def rootFile(origin: PsiFile, path: String): Option[PsiFile] =
    if Option(origin.getVirtualFile).exists(_.getPath == path) then Some(origin)
    else
      val id = new FileId(path, canonical = false)
      BendPhysicalTargets.file(origin.getProject, id)

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
      destination: BendProofDestination
  ): Unit =
    val file = destination.target.getContainingFile
    Option(file.getVirtualFile).foreach(virtual =>
      new OpenFileDescriptor(
        project,
        virtual,
        destination.target.getTextOffset
      ).navigate(true)
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
