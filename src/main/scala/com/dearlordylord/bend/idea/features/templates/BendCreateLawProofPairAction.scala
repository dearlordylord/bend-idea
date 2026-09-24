package com.dearlordylord.bend.idea.features.templates

import com.intellij.openapi.actionSystem.{
  AnAction,
  AnActionEvent,
  CommonDataKeys
}
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.ui.Messages
import com.intellij.psi.{PsiDirectory, PsiFile, PsiManager}

/** Creates the conventional sibling LAWS.bend and PROOF.bend sources. */
final class BendCreateLawProofPairAction extends AnAction with DumbAware:
  override def update(event: AnActionEvent): Unit =
    event.getPresentation.setEnabledAndVisible(
      event.getProject != null && directory(event).nonEmpty
    )

  override def actionPerformed(event: AnActionEvent): Unit =
    val project = event.getProject
    if project == null then return
    directory(event) match
      case None =>
        Messages.showInfoMessage(
          project,
          "Select a project directory first.",
          "New Bend Law and Proof Pair"
        )
      case Some(target) =>
        BendFileTemplates.createLawProofPair(target) match
          case Left(message) =>
            Messages.showInfoMessage(
              project,
              message,
              "New Bend Law and Proof Pair"
            )
          case Right((_, proof)) =>
            val hole = proof.getText.indexOf("?TODO")
            BendFileTemplates.openAt(proof, if hole < 0 then 0 else hole + 1)

  private def directory(event: AnActionEvent): Option[PsiDirectory] =
    Option(event.getData(CommonDataKeys.PSI_ELEMENT))
      .flatMap {
        case dir: PsiDirectory => Some(dir)
        case file: PsiFile     => Option(file.getContainingDirectory)
        case _                 => None
      }
      .orElse {
        Option(event.getData(CommonDataKeys.VIRTUAL_FILE)).flatMap { virtual =>
          val target =
            if virtual.isDirectory then virtual else virtual.getParent
          Option(target).flatMap(value =>
            Option(
              PsiManager.getInstance(event.getProject).findDirectory(value)
            )
          )
        }
      }
      .orElse {
        ProjectRootManager
          .getInstance(event.getProject)
          .getContentRoots
          .headOption
          .flatMap(value =>
            Option(
              PsiManager.getInstance(event.getProject).findDirectory(value)
            )
          )
      }
