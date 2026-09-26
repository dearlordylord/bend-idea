package com.dearlordylord.bend.idea.features.formatting

import com.dearlordylord.bend.idea.syntax.lexer.{BendLexer, BendTokens}
import com.dearlordylord.bend.idea.syntax.psi.BendElements
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

/** The surface parser deliberately leaves expressions opaque. Only punctuation
  * gaps whose existing line stays intact are eligible for formatting; every
  * other gap is read-only.
  */
final class BendFormattingModelBuilder extends FormattingModelBuilder:
  override def createModel(context: FormattingContext): FormattingModel =
    val source = context.getContainingFile.getText
    val root = new BendFormatBlock(
      context.getNode,
      source,
      BendFormatSafety.complete(source) && BendFormatSafety.headersComplete(
        context.getNode
      )
    )
    FormattingModelProvider.createFormattingModelForPsiFile(
      context.getContainingFile,
      root,
      context.getCodeStyleSettings
    )

private final class BendFormatBlock(
    node: ASTNode,
    source: String,
    complete: Boolean,
    indent: Indent = Indent.getNoneIndent()
) extends AbstractBlock(node, null, null):
  private val simpleBody =
    complete && BendFormatSafety.singleBodyLine(myNode, source)

  override protected def buildChildren(): java.util.List[Block] =
    val result = scala.collection.mutable.ArrayBuffer.empty[Block]
    var child = myNode.getFirstChildNode
    while child != null do
      if child.getElementType != TokenType.WHITE_SPACE then
        val bodyIndent =
          if simpleBody && child.getElementType != BendElements.Header &&
            child.getStartOffset > myNode.getStartOffset
          then Indent.getSpaceIndent(2, true)
          else Indent.getNoneIndent()
        result += new BendFormatBlock(child, source, complete, bodyIndent)
      child = child.getTreeNext
    result.asJava

  override def getIndent: Indent = indent
  override def getChildAttributes(index: Int): ChildAttributes =
    new ChildAttributes(Indent.getNoneIndent(), null)
  override def isLeaf: Boolean = myNode.getFirstChildNode == null

  override def getSpacing(left: Block, right: Block): Spacing =
    if left == null || !complete then return Spacing.getReadOnlySpacing()
    val before = left.getTextRange.getEndOffset
    val after = right.getTextRange.getStartOffset
    if before > after || before < 0 || after > source.length then
      return Spacing.getReadOnlySpacing()
    val gap = source.substring(before, after)
    val (leftNode, rightNode) = (left, right) match
      case (leftBlock: BendFormatBlock, rightBlock: BendFormatBlock) =>
        (leftBlock.getNode, rightBlock.getNode)
      case _ => return Spacing.getReadOnlySpacing()
    if gap.exists(c => c == '\n' || c == '\r') then
      if simpleBody && leftNode.getElementType == BendElements.Header then
        return Spacing.createSpacing(0, 0, 1, true, 0)
      return Spacing.getReadOnlySpacing()
    if rightNode.getElementType == BendTokens.Separator && rightNode.getText == ","
    then Spacing.createSpacing(0, 0, 0, false, 0)
    else if leftNode.getElementType == BendTokens.Separator && leftNode.getText == "," &&
      !Set(")", "]", "}").contains(rightNode.getText)
    then Spacing.createSpacing(1, 1, 0, false, 0)
    else Spacing.getReadOnlySpacing()

private object BendFormatSafety:
  def singleBodyLine(node: ASTNode, source: String): Boolean =
    if node.getElementType != BendElements.Definition && node.getElementType != BendElements.Law
    then return false
    var child = node.getFirstChildNode
    while child != null && child.getElementType != BendElements.Header do
      child = child.getTreeNext
    if child == null then return false
    val body = source.substring(
      child.getTextRange.getEndOffset,
      node.getTextRange.getEndOffset
    )
    if !body.startsWith("\n") || body.contains('\r') then return false
    val lines = body.linesIterator.filter(_.trim.nonEmpty).toList
    lines.size == 1 && lines.head.startsWith("    ") && !lines.head.contains(
      '\t'
    ) &&
    !lines.head.trim.startsWith("#")

  def headersComplete(node: ASTNode): Boolean =
    if node.getElementType == BendElements.Header && !node.getText.trim
        .endsWith(":")
    then false
    else
      var child = node.getFirstChildNode
      var valid = true
      while child != null && valid do
        valid = headersComplete(child)
        child = child.getTreeNext
      valid

  def complete(source: String): Boolean =
    val lexer = new BendLexer()
    lexer.start(source, 0, source.length, 0)
    val stack = scala.collection.mutable.ArrayBuffer.empty[Char]
    var valid = true
    while lexer.getTokenType != null do
      val kind = lexer.getTokenType
      if kind == TokenType.BAD_CHARACTER || kind == BendTokens.InvalidEscape
      then valid = false
      val opening =
        if kind == BendTokens.LeftParen then Some(')')
        else if kind == BendTokens.LeftBracket then Some(']')
        else if kind == BendTokens.LeftBrace then Some('}')
        else None
      opening.foreach(stack += _)
      val closing =
        if kind == BendTokens.RightParen then Some(')')
        else if kind == BendTokens.RightBracket then Some(']')
        else if kind == BendTokens.RightBrace then Some('}')
        else None
      closing.foreach { expected =>
        if stack.lastOption.contains(expected) then stack.remove(stack.size - 1)
        else valid = false
      }
      lexer.advance()
    valid && stack.isEmpty && (lexer.getState & 3) == 0
