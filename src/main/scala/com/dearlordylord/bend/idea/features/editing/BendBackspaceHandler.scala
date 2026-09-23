package com.dearlordylord.bend.idea.features.editing

import com.dearlordylord.bend.idea.syntax.BendLanguage
import com.dearlordylord.bend.idea.syntax.lexer.{BendLexer, BendTokens}
import com.dearlordylord.bend.idea.syntax.parser.BendIndentPolicy
import com.intellij.application.options.CodeStyle
import com.intellij.codeInsight.editorActions.BackspaceHandlerDelegate
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiFile

/** Remove the closing character only when backspace deletes an adjacent empty pair. */
final class BendBackspaceHandler extends BackspaceHandlerDelegate:
  import BendBackspaceHandler.{indentAfterDeletion, removeCloser}

  override def beforeCharDeleted(c: Char, file: PsiFile, editor: Editor): Unit =
    editor.putUserData(removeCloser, false)
    editor.putUserData(indentAfterDeletion, null)
    if file.getLanguage != BendLanguage.instance then return
    val offset = editor.getCaretModel.getOffset
    val source = editor.getDocument.getCharsSequence
    if offset <= 0 then return
    if c == ' ' || c == '\t' then
      val step = math.max(1, CodeStyle.getIndentOptions(file).INDENT_SIZE)
      BendIndentPolicy.backspace(source.toString, offset, step).foreach { target =>
        val lineStart = source.toString.lastIndexOf('\n', offset - 1) + 1
        editor.putUserData(indentAfterDeletion, (lineStart, target))
      }
    if offset >= source.length then return
    val adjacent = (c == '"' || c == '\'') && source.charAt(offset) == c ||
      c == '<' && BendInsertedAngles.consumeEmptyPairAt(editor, offset - 1)
    if !adjacent then return
    val lexer = new BendLexer()
    lexer.start(source)
    while lexer.getTokenType != null && lexer.getTokenEnd <= offset - 1 do lexer.advance()
    val outside = (lexer.getState & 3) == 0 && lexer.getTokenType != BendTokens.Comment
    if outside then editor.putUserData(removeCloser, true)

  override def charDeleted(c: Char, file: PsiFile, editor: Editor): Boolean =
    val remove = editor.getUserData(removeCloser) == true
    editor.putUserData(removeCloser, false)
    var handled = false
    if remove then
      val offset = editor.getCaretModel.getOffset
      val source = editor.getDocument.getCharsSequence
      val closer = if c == '<' then '>' else c
      if offset < source.length && source.charAt(offset) == closer then
        editor.getDocument.deleteString(offset, offset + 1)
        handled = true
    Option(editor.getUserData(indentAfterDeletion)).foreach { case (lineStart, target) =>
      editor.putUserData(indentAfterDeletion, null)
      val offset = editor.getCaretModel.getOffset
      val source = editor.getDocument.getCharsSequence
      var firstCode = lineStart
      while firstCode < source.length && (source.charAt(firstCode) == ' ' || source.charAt(firstCode) == '\t') do
        firstCode += 1
      if offset <= firstCode && lineStart <= offset then
        editor.getDocument.replaceString(lineStart, firstCode, " " * target)
        editor.getCaretModel.moveToOffset(lineStart + target)
        handled = true
    }
    handled

private object BendBackspaceHandler:
  val removeCloser: Key[Boolean] = Key.create[Boolean]("bend.remove.empty.pair.closer")
  val indentAfterDeletion: Key[(Int, Int)] = Key.create[(Int, Int)]("bend.backspace.indent.target")
