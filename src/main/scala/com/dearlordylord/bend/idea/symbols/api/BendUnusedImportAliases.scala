package com.dearlordylord.bend.idea.symbols.api

import com.dearlordylord.bend.idea.syntax.psi.{
  BendAlias,
  BendName,
  BendReferenceElement
}
import com.dearlordylord.bend.idea.workspace.api.BendImportLines
import com.dearlordylord.bend.idea.syntax.parser.BendSourceDelimiters
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.{PsiErrorElement, PsiFile}
import com.intellij.psi.util.PsiTreeUtil
import scala.jdk.CollectionConverters.*

/** A conservative absence fact, not a judgment that loading an import is
  * unnecessary. Possible unresolved, shadowed and declaration-name uses veto
  * the hint too.
  */
object BendUnusedImportAliases:
  def in(file: PsiFile): List[BendAlias] =
    if !BendSourceDelimiters.balanced(
        file.getText,
        () => ProgressManager.checkCanceled()
      )
    then return Nil
    if PsiTreeUtil.findChildOfType(file, classOf[PsiErrorElement]) != null then
      return Nil
    val imports = BendImportLines.parse(file.getText)
    if imports.exists(_.syntaxProblem.nonEmpty) then return Nil
    val aliases =
      PsiTreeUtil.findChildrenOfType(file, classOf[BendAlias]).asScala.toList
    val names = PsiTreeUtil
      .findChildrenOfType(file, classOf[BendName])
      .asScala
      .map(element => { ProgressManager.checkCanceled(); element.getText })
      .toList ++
      PsiTreeUtil
        .findChildrenOfType(file, classOf[BendReferenceElement])
        .asScala
        .map(element => { ProgressManager.checkCanceled(); element.getText })
        .toList
    val counts =
      imports.flatMap(_.alias).groupMapReduce(identity)(_ => 1)(_ + _)
    val possibleUses = names.iterator.map { word =>
      ProgressManager.checkCanceled()
      word.takeWhile(_ != '.')
    }.toSet
    aliases.filter { alias =>
      ProgressManager.checkCanceled()
      val name = alias.getText
      counts.get(name).contains(1) && !possibleUses.contains(name)
    }
