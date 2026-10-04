package com.dearlordylord.bend.idea.symbols.api

import com.dearlordylord.bend.idea.syntax.BendLanguage
import com.dearlordylord.bend.idea.syntax.psi.*
import com.dearlordylord.bend.idea.workspace.api.BendLoadingConfiguration
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil

/** Native type navigation shares source scope, loading and physical targets. */
object BendSourceTypeTargets:
  def declarations(element: PsiElement): List[PsiElement] =
    val file = element.getContainingFile
    if file == null || file.getLanguage != BendLanguage.instance then Nil
    else
      val owner = Option(
        PsiTreeUtil.getParentOfType(element, classOf[BendDeclaration], false)
      )
      val binding =
        BendSourceSymbols.bindingDeclaredAt(file, element.getTextOffset)
      if binding.isEmpty && owner.exists(_.isInstanceOf[BendConstructor]) then
        owner
          .flatMap(value =>
            Option(PsiTreeUtil.getParentOfType(value, classOf[BendDatatype]))
          )
          .flatMap(value => Option(value.getNameIdentifier))
          .toList
      else
        val source = file.getText
        val head = binding
          .flatMap(value =>
            owner.flatMap(declaration =>
              BendDeclaredTypeHeads.binding(
                source,
                value.handle.nameOffset + value.name.length,
                declaration.getTextRange.getEndOffset
              )
            )
          )
          .orElse {
            owner
              .filter(value =>
                Option(value.getNameIdentifier)
                  .exists(_.getTextOffset == element.getTextOffset)
              )
              .flatMap(value =>
                Option(PsiTreeUtil.getChildOfType(value, classOf[BendHeader]))
              )
              .flatMap(header =>
                BendDeclaredTypeHeads.result(
                  source,
                  header.getTextOffset,
                  header.getTextRange.getEndOffset
                )
              )
          }
        head.toList.flatMap { selected =>
          val visible = BendSourceSymbols.visibleBindings(file, selected.offset)
          val prefix = selected.spelling.takeWhile(_ != '.')
          if visible.exists(value =>
              value.name == selected.spelling || value.name == prefix
            )
          then Nil
          else
            val (base, cache) = file.getProject
              .getService(classOf[BendLoadingConfiguration])
              .paths
            val catalog =
              file.getProject.getService(classOf[BendImportedSymbolCatalog])
            val snapshot = catalog.navigationSnapshot(file, base, cache)
            val resolved = List(
              BendSymbolCategory.Datatype,
              BendSymbolCategory.Definition,
              BendSymbolCategory.Law
            ).iterator
              .map(category =>
                catalog.resolveForNavigation(
                  snapshot,
                  selected.offset,
                  selected.spelling,
                  Some(category)
                )
              )
              .collectFirst {
                case BendNavigationResolution(
                      BendSourceResolution.Resolved(symbol),
                      _
                    ) =>
                  symbol
              }
            resolved.toList.flatMap(symbol =>
              val targetFile =
                if BendSourceSymbols.fileId(file) == symbol.handle.file then
                  Some(file)
                else
                  BendPhysicalTargets.file(file.getProject, symbol.handle.file)
              targetFile.flatMap(BendPhysicalTargets.declaration(_, symbol))
            )
        }
