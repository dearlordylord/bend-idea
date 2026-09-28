package com.dearlordylord.bend.idea.features.formatting

import com.dearlordylord.bend.idea.syntax.parser.BendLayoutPolicy
import com.dearlordylord.bend.idea.syntax.psi.{
  BendEffectiveIndent,
  BendElements
}
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
import com.intellij.psi.TokenType
import com.intellij.psi.formatter.common.AbstractBlock
import scala.jdk.CollectionConverters.*

/** IntelliJ applies only the gaps approved by the shared source policy. */
final class BendFormattingModelBuilder extends FormattingModelBuilder:
  override def createModel(context: FormattingContext): FormattingModel =
    val file = context.getContainingFile
    val settings = BendEffectiveIndent.forFile(file)
    val source = file.getText
    val edits = BendLayoutPolicy.edits(source, settings).getOrElse(Nil)
    val root = new BendFormatBlock(context.getNode, source, edits)
    FormattingModelProvider.createFormattingModelForPsiFile(
      file,
      root,
      context.getCodeStyleSettings
    )

private final class BendFormatBlock(
    node: ASTNode,
    source: String,
    edits: List[BendLayoutPolicy.Edit],
    indent: Indent = Indent.getNoneIndent()
) extends AbstractBlock(node, null, null):
  private val simpleBody =
    (myNode.getElementType == BendElements.Definition ||
      myNode.getElementType == BendElements.Law) &&
      edits.exists(edit =>
        edit.start > myNode.getStartOffset &&
          edit.start < myNode.getTextRange.getEndOffset &&
          edit.start > 0 && source.charAt(edit.start - 1) == '\n'
      )

  override protected def buildChildren(): java.util.List[Block] =
    val result = scala.collection.mutable.ArrayBuffer.empty[Block]
    var child = myNode.getFirstChildNode
    while child != null do
      if child.getElementType != TokenType.WHITE_SPACE then
        val bodyIndent =
          if simpleBody && child.getElementType != BendElements.Header &&
            child.getStartOffset > myNode.getStartOffset
          then Indent.getNormalIndent()
          else Indent.getNoneIndent()
        result += new BendFormatBlock(child, source, edits, bodyIndent)
      child = child.getTreeNext
    result.asJava

  override def getIndent: Indent = indent
  override def getChildAttributes(index: Int): ChildAttributes =
    new ChildAttributes(Indent.getNoneIndent(), null)
  override def isLeaf: Boolean = myNode.getFirstChildNode == null

  override def getSpacing(left: Block, right: Block): Spacing =
    if left == null then return Spacing.getReadOnlySpacing()
    val before = left.getTextRange.getEndOffset
    val after = right.getTextRange.getStartOffset
    if before > after || before < 0 || after > source.length then
      return Spacing.getReadOnlySpacing()
    val gap = source.substring(before, after)
    if gap.exists(c => c == '\n' || c == '\r') then
      left match
        case block: BendFormatBlock
            if simpleBody &&
              block.getNode.getElementType == BendElements.Header =>
          Spacing.createSpacing(0, 0, 1, true, 0)
        case _ => Spacing.getReadOnlySpacing()
    else
      edits.find(edit => edit.start == before && edit.end == after) match
        case Some(edit) if edit.replacement.isEmpty =>
          Spacing.createSpacing(0, 0, 0, false, 0)
        case Some(edit) if edit.replacement == " " =>
          Spacing.createSpacing(1, 1, 0, false, 0)
        case _ => Spacing.getReadOnlySpacing()
