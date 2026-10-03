package com.dearlordylord.bend.idea.features.formatting

import com.intellij.formatting.{
  Block,
  ChildAttributes,
  FormattingContext,
  FormattingModel,
  FormattingModelBuilder,
  FormattingModelProvider,
  Indent,
  Spacing
}
import com.intellij.lang.ASTNode
import com.intellij.psi.formatter.common.AbstractBlock

/** The platform owns the write command and undo; the policy owns every edit. A
  * read-only root prevents a second indentation engine from changing the plan.
  */
final class BendFormattingModelBuilder extends FormattingModelBuilder:
  override def createModel(context: FormattingContext): FormattingModel =
    val file = context.getContainingFile
    val root = new BendPolicyBlock(context.getNode)
    FormattingModelProvider.createFormattingModelForPsiFile(
      file,
      root,
      context.getCodeStyleSettings
    )

private final class BendPolicyBlock(node: ASTNode)
    extends AbstractBlock(node, null, null):
  override protected def buildChildren(): java.util.List[Block] =
    java.util.Collections.emptyList[Block]()
  override def getIndent: Indent = Indent.getNoneIndent()
  override def getChildAttributes(index: Int): ChildAttributes =
    new ChildAttributes(Indent.getNoneIndent(), null)
  override def isLeaf: Boolean = true
  override def getSpacing(left: Block, right: Block): Spacing =
    Spacing.getReadOnlySpacing()
