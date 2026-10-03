package com.dearlordylord.bend.idea.features.formatting

import com.dearlordylord.bend.idea.syntax.parser.BendLayoutPolicy
import com.dearlordylord.bend.idea.syntax.psi.BendEffectiveIndent
import com.intellij.lang.ASTNode
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.impl.source.codeStyle.PreFormatProcessor

/** Apply the pure plan inside IntelliJ's existing formatting write command. */
final class BendFormattingProcessor extends PreFormatProcessor:
  override def changesWhitespacesOnly(): Boolean = true

  override def process(node: ASTNode, range: TextRange): TextRange =
    val file = node.getPsi.getContainingFile
    if file == null || file.getFileType.getDefaultExtension != "bend" then
      return range
    val manager = PsiDocumentManager.getInstance(file.getProject)
    val document = manager.getDocument(file)
    if document == null then return range
    val source = file.getText
    // Reformat Selection may omit the final newline or surrounding whitespace.
    // It still selects the whole logical file when no source tokens lie outside.
    val wholeFile =
      source.substring(0, range.getStartOffset).forall(_.isWhitespace) &&
        source.substring(range.getEndOffset).forall(_.isWhitespace)
    val edits = BendLayoutPolicy
      .edits(source, BendEffectiveIndent.forFormattingFile(file))
      .getOrElse(Nil)
      .filter(edit => wholeFile || range.containsRange(edit.start, edit.end))
      .filter(edit =>
        wholeFile ||
          (!edit.replacement.exists(c => c == '\n' || c == '\r') &&
            !source
              .substring(edit.start, edit.end)
              .exists(c => c == '\n' || c == '\r'))
      )
    if document.getText != source then return range
    edits
      .sortBy(-_.start)
      .foreach(edit =>
        document.replaceString(edit.start, edit.end, edit.replacement)
      )
    manager.commitDocument(document)
    if wholeFile then return new TextRange(0, document.getTextLength)
    new TextRange(
      range.getStartOffset,
      range.getEndOffset + edits
        .map(edit => edit.replacement.length - (edit.end - edit.start))
        .sum
    )
