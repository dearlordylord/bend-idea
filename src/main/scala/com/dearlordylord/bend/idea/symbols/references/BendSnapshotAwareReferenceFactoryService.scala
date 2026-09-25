package com.dearlordylord.bend.idea.symbols.references

import com.dearlordylord.bend.idea.symbols.api.*
import com.dearlordylord.bend.idea.syntax.BendLanguage
import com.dearlordylord.bend.idea.syntax.psi.{BendName, BendReferenceElement}
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement

/** Creates the same alias/member reference shapes for the registered PSI
  * provider and snapshot-based dependency inspection.
  */
final class BendSnapshotAwareReferenceFactoryService
    extends BendSnapshotAwareReferenceFactory:
  override def referencesFor(
      element: PsiElement,
      snapshot: BendSourceNavigationSnapshot
  ): List[BendSnapshotAwareReference] =
    if element == null || element.getNode == null ||
      element.getContainingFile == null
    then Nil
    else if element.getContainingFile.getLanguage != BendLanguage.instance then
      Nil
    else
      val word = element.getText
      if !word.matches("[A-Za-z_][A-Za-z0-9_.]*") then Nil
      else
        val full = new BendNameReference(
          element,
          new TextRange(0, word.length),
          word,
          None
        )
        val file = element.getContainingFile
        val offset = element.getTextOffset
        val source = file.getText
        val lineStart = source.lastIndexOf('\n', offset - 1) + 1
        val before = source.substring(lineStart, offset).trim
        if before.startsWith("import ") || before.endsWith(" as") then Nil
        else
          element match
            case _: BendName             => List(full)
            case _: BendReferenceElement =>
              splitAlias(element, word, full, snapshot)
            case _ => Nil

  private def splitAlias(
      element: PsiElement,
      word: String,
      full: BendNameReference,
      snapshot: BendSourceNavigationSnapshot
  ): List[BendSnapshotAwareReference] =
    val dot = word.indexOf('.')
    if dot < 0 then List(full)
    else
      val alias = word.substring(0, dot)
      val file = element.getContainingFile
      val resolution = full.resolveAgainst(snapshot)
      val aliases = snapshot.graph.edges.filter(edge =>
        edge.from == BendSourceSymbols.fileId(file) &&
          edge.importLine.alias.contains(alias)
      )
      def aliasOwns(symbol: BendSourceSymbol): Boolean =
        aliases.exists(_.target.contains(symbol.handle.file))
      val resolvedThroughAlias = resolution.target match
        case BendSourceResolution.Resolved(symbol)   => aliasOwns(symbol)
        case BendSourceResolution.Ambiguous(symbols) =>
          symbols.nonEmpty && symbols.forall(aliasOwns)
        case _ => false
      if aliases.nonEmpty &&
        (resolvedThroughAlias ||
          resolution.target == BendSourceResolution.Unresolved)
      then
        val prefix = new BendNameReference(
          element,
          new TextRange(0, dot),
          alias,
          Some(true)
        )
        if dot == word.length - 1 then List(prefix)
        else
          List(
            prefix,
            new BendNameReference(
              element,
              new TextRange(dot + 1, word.length),
              word,
              None
            )
          )
      else List(full)
