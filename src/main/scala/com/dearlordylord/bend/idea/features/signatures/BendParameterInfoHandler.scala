package com.dearlordylord.bend.idea.features.signatures

import com.dearlordylord.bend.idea.symbols.api.{
  BendRenderedCallSignature,
  BendSourceApplications,
  BendSourceCallSignatures
}
import com.dearlordylord.bend.idea.syntax.BendLanguage
import com.intellij.lang.parameterInfo.*
import com.intellij.psi.{PsiElement, PsiFile}

/** Shows source signatures only; no compiler type or dependent inference is
  * implied.
  */
final class BendParameterInfoHandler
    extends ParameterInfoHandler[PsiElement, BendRenderedCallSignature]:
  override def findElementForParameterInfo(
      context: CreateParameterInfoContext
  ): PsiElement =
    resolved(context.getFile, context.getOffset).map { signature =>
      val owner = ownerAt(context.getFile, signature.application.calleeFrom)
      if owner != null then
        context.setItemsToShow(Array(signature.asInstanceOf[Object]))
      owner
    }.orNull

  override def showParameterInfo(
      element: PsiElement,
      context: CreateParameterInfoContext
  ): Unit =
    resolved(context.getFile, context.getOffset).foreach { signature =>
      context.setItemsToShow(Array(signature.asInstanceOf[Object]))
      context.showHint(element, signature.application.openFrom, this)
    }

  override def findElementForUpdatingParameterInfo(
      context: UpdateParameterInfoContext
  ): PsiElement =
    resolved(context.getFile, context.getOffset)
      .map(signature =>
        ownerAt(context.getFile, signature.application.calleeFrom)
      )
      .orNull

  override def updateParameterInfo(
      parameterOwner: PsiElement,
      context: UpdateParameterInfoContext
  ): Unit =
    resolved(context.getFile, context.getOffset) match
      case Some(signature) =>
        Option(context.getObjectsToView).foreach { items =>
          items.indices.foreach { index =>
            items(index) match
              case _: BendRenderedCallSignature =>
                items(index) = signature.asInstanceOf[Object]
              case _ => ()
          }
        }
        context.setParameterOwner(parameterOwner)
        context.setCurrentParameter(
          if signature.activeParameter < signature.parameters.size then
            signature.activeParameter
          else -1
        )
      case None => context.removeHint()

  override def updateUI(
      signature: BendRenderedCallSignature,
      context: ParameterInfoUIContext
  ): Unit =
    val active =
      signature.parameterRanges.lift(context.getCurrentParameterIndex)
    val (from, until) = active.getOrElse((0, 0))
    val _ = context.setupUIComponentPresentation(
      signature.text,
      from,
      until,
      false,
      false,
      false,
      context.getDefaultParameterColor
    )

  override def getParameterCloseChars: String = ")}>"

  private def resolved(
      file: PsiFile,
      offset: Int
  ): Option[BendRenderedCallSignature] =
    if file == null || file.getLanguage != BendLanguage.instance then None
    else
      BendSourceApplications
        .at(file, offset)
        .flatMap(BendSourceCallSignatures.resolve(file, _))

  private def ownerAt(file: PsiFile, offset: Int): PsiElement =
    if file == null || offset < 0 || offset >= file.getTextLength then null
    else file.findElementAt(offset)
