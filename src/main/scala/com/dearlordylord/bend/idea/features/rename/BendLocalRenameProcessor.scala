package com.dearlordylord.bend.idea.features.rename

import com.dearlordylord.bend.idea.symbols.api.BendSourceSymbols
import com.dearlordylord.bend.idea.syntax.lexer.BendWords
import com.dearlordylord.bend.idea.syntax.psi.{BendAlias, BendReferenceElement}
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.rename.RenamePsiElementProcessor
import com.intellij.util.containers.MultiMap
import scala.jdk.CollectionConverters.*

/** Standard rename is available only for a source binding, not every name-like PSI node. */
final class BendLocalRenameProcessor extends RenamePsiElementProcessor:
  override def canProcessElement(element: PsiElement): Boolean = element match
    case reference: BendReferenceElement =>
      val file = reference.getContainingFile
      file != null && file.isWritable &&
        BendSourceSymbols.bindingDeclaredAt(file, reference.getTextOffset).nonEmpty
    case alias: BendAlias => alias.getContainingFile != null && alias.getContainingFile.isWritable
    case _ => false

  override def isInplaceRenameSupported: Boolean = false
  override def forcesShowPreview(): Boolean = true

  override def findExistingNameConflicts(element: PsiElement, newName: String,
      conflicts: MultiMap[PsiElement, String]): Unit =
    element match
      case reference: BendReferenceElement =>
        val file = reference.getContainingFile
        BendSourceSymbols.bindingDeclaredAt(file, reference.getTextOffset).foreach { selected =>
          if !newName.matches("[A-Za-z_][A-Za-z0-9_]*") || BendWords.reserved(newName) then
            conflicts.putValue(reference, s"Invalid Bend local name: $newName")
          else
            val peers = PsiTreeUtil.findChildrenOfType(file, classOf[BendReferenceElement]).asScala
              .filter(_.getText == newName)
              .flatMap(peer => BendSourceSymbols.bindingDeclaredAt(file, peer.getTextOffset))
            if peers.exists(other => other.handle != selected.handle &&
                selected.from < other.until && other.from < selected.until) then
              conflicts.putValue(reference, s"Renaming ${selected.name} to $newName would capture another local binding")
            val capturedUse = PsiTreeUtil.findChildrenOfType(file, classOf[BendReferenceElement]).asScala
              .exists(other => other.getText == newName &&
                other.getTextOffset >= selected.from && other.getTextOffset < selected.until &&
                BendSourceSymbols.bindingDeclaredAt(file, other.getTextOffset).isEmpty)
            if capturedUse then
              conflicts.putValue(reference, s"Renaming ${selected.name} to $newName would capture an existing reference")
        }
      case alias: BendAlias =>
        val file = alias.getContainingFile
        if !newName.matches("[A-Za-z_][A-Za-z0-9_]*") || BendWords.reserved(newName) then
          conflicts.putValue(alias, s"Invalid Bend import alias: $newName")
        else
          val duplicate = PsiTreeUtil.findChildrenOfType(file, classOf[BendAlias]).asScala
            .exists(other => other != alias && other.getName == newName)
          val sourceCollision = BendSourceSymbols.declarations(file)
            .exists(symbol => symbol.name == newName || symbol.name.startsWith(newName + "."))
          if duplicate || sourceCollision then
            conflicts.putValue(alias, s"Import alias $newName conflicts with another source name")
      case _ => ()
