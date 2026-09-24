package com.dearlordylord.bend.idea.features.rename

import com.dearlordylord.bend.idea.syntax.psi.{
  BendDeclaration,
  BendName,
  BendReferenceElement
}
import com.dearlordylord.bend.idea.symbols.api.{
  BendSourceFiles,
  BendSourceSymbols,
  BendSymbolCategory
}
import com.dearlordylord.bend.idea.syntax.lexer.BendWords
import com.dearlordylord.bend.idea.workspace.api.{
  BendImportLines,
  BendLoadingConfiguration
}
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.rename.RenamePsiElementProcessor
import com.intellij.util.IncorrectOperationException
import com.intellij.util.containers.MultiMap
import scala.jdk.CollectionConverters.*

/** Use the declaration owner as the rename target; its name PSI is replaced
  * during the edit.
  */
final class BendDeclarationRenameProcessor extends RenamePsiElementProcessor:
  override def canProcessElement(element: PsiElement): Boolean = element match
    case declaration: BendDeclaration =>
      declaration.isWritable && declaration.getNameIdentifier != null
    case name: BendName =>
      Option(PsiTreeUtil.getParentOfType(name, classOf[BendDeclaration]))
        .exists(_.isWritable)
    case _ => false

  override def substituteElementToRename(
      element: PsiElement,
      editor: Editor
  ): PsiElement =
    element match
      case declaration: BendDeclaration => declaration
      case name: BendName               =>
        PsiTreeUtil.getParentOfType(name, classOf[BendDeclaration])
      case _ => element

  override def isInplaceRenameSupported: Boolean = false
  override def forcesShowPreview(): Boolean = true

  override def prepareRenaming(
      element: PsiElement,
      newName: String,
      allRenames: java.util.Map[PsiElement, String]
  ): Unit =
    element match
      case declaration: BendDeclaration =>
        val file = declaration.getContainingFile
        BendSourceSymbols
          .declarations(file)
          .find(_.declaration eq declaration)
          .foreach { selected =>
            val localFill = BendSourceSymbols.isLocalLawFill(file, selected)
            val importedFillSpelling = BendImportLines
              .parse(file.getText)
              .exists(imp =>
                imp.alias.exists(alias => selected.name.startsWith(alias + "."))
              )
            val scan = BendSourceFiles.scan(
              file,
              GlobalSearchScope.projectScope(file.getProject),
              importedFillSpelling
            )
            if !scan.complete then
              throw new IncorrectOperationException(
                "Rename needs a complete bounded Bend source inventory"
              )
            if selected.category == BendSymbolCategory.Law || localFill ||
              selected.category == BendSymbolCategory.Definition && importedFillSpelling
            then
              val (base, cache) = file.getProject
                .getService(classOf[BendLoadingConfiguration])
                .paths
              val related = BendSourceSymbols.relatedLawSites(
                scan.files,
                selected,
                base,
                cache
              )
              if related.exists(symbol => !symbol.declaration.isWritable) then
                throw new IncorrectOperationException(
                  "A related Bend law or fill is read-only"
                )
              val oldLawName =
                related.headOption.map(_.name).getOrElse(selected.name)
              val simpleNewName =
                newName.substring(newName.lastIndexOf('.') + 1)
              if selected.category == BendSymbolCategory.Definition &&
                selected.name.endsWith("." + oldLawName)
              then
                val _ = allRenames.put(
                  declaration,
                  selected.name.stripSuffix(oldLawName) + simpleNewName
                )
              related.foreach { symbol =>
                if symbol.declaration ne declaration then
                  val spelling = if symbol.name == oldLawName then simpleNewName
                  else if symbol.name.endsWith("." + oldLawName) then
                    symbol.name.stripSuffix(oldLawName) + simpleNewName
                  else newName
                  val _ = allRenames.put(symbol.declaration, spelling)
              }
          }
      case _ => ()

  override def findExistingNameConflicts(
      element: PsiElement,
      newName: String,
      conflicts: MultiMap[PsiElement, String],
      allRenames: java.util.Map[PsiElement, String]
  ): Unit =
    val planned = allRenames.asScala.toMap
      .updated(element, Option(allRenames.get(element)).getOrElse(newName))
    for case (candidate: BendDeclaration, spelling) <- planned do
      if !spelling.matches("[A-Za-z_][A-Za-z0-9_.]*") || spelling.endsWith(
          "."
        ) ||
        spelling.split('.').exists(BendWords.reserved)
      then
        conflicts.putValue(
          candidate,
          s"Invalid Bend declaration name: $spelling"
        )
      else
        val symbols =
          BendSourceSymbols.declarations(candidate.getContainingFile)
        symbols.find(_.declaration eq candidate).foreach { selected =>
          val collision = symbols.exists { other =>
            (other.declaration ne candidate) && !planned.contains(
              other.declaration
            ) &&
            other.name == spelling &&
            (other.category == selected.category ||
              Set(other.category, selected.category) ==
              Set(BendSymbolCategory.Law, BendSymbolCategory.Definition))
          }
          if collision then
            conflicts.putValue(
              candidate,
              s"Bend ${selected.category.toString.toLowerCase} $spelling already exists in this source"
            )
          val capturedExisting = PsiTreeUtil
            .findChildrenOfType(
              candidate.getContainingFile,
              classOf[BendReferenceElement]
            )
            .asScala
            .exists { reference =>
              val offset = reference.getTextOffset
              reference.getText == spelling && offset > selected.handle.nameOffset &&
              BendSourceSymbols
                .bindingDeclaredAt(candidate.getContainingFile, offset)
                .isEmpty &&
              !BendSourceSymbols
                .visibleBindings(candidate.getContainingFile, offset)
                .exists(_.name == spelling) &&
              BendSourceSymbols
                .referenceCategory(
                  candidate.getContainingFile,
                  offset,
                  spelling
                )
                .forall(_ == selected.category)
            }
          if capturedExisting then
            conflicts.putValue(
              candidate,
              s"Renaming ${selected.name} to $spelling would capture an existing reference"
            )
          if !spelling.contains('.') then
            val captured =
              ReferencesSearch.search(candidate).findAll().asScala.exists {
                reference =>
                  val use = reference.getElement
                  val range = reference.getRangeInElement
                  range.getStartOffset == 0 && range.getEndOffset == use.getTextLength &&
                  BendSourceSymbols
                    .visibleBindings(use.getContainingFile, use.getTextOffset)
                    .exists(_.name == spelling)
              }
            if captured then
              conflicts.putValue(
                candidate,
                s"Renaming ${selected.name} to $spelling would capture a reference with a local binding"
              )
        }
