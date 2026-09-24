package com.dearlordylord.bend.idea.features.editing

import com.dearlordylord.bend.idea.syntax.BendLanguage
import com.dearlordylord.bend.idea.syntax.lexer.{BendLexer, BendTokens}
import com.dearlordylord.bend.idea.syntax.parser.BendTypeAngleContext
import com.intellij.codeInsight.editorActions.TypedHandlerDelegate
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile

/** Quote handling uses the current lexer state, including unfinished literals.
  */
final class BendTypedHandler extends TypedHandlerDelegate:
  override def beforeCharTyped(
      c: Char,
      project: Project,
      editor: Editor,
      file: PsiFile,
      fileType: FileType
  ): TypedHandlerDelegate.Result =
    if file.getLanguage != BendLanguage.instance || (c != '"' && c != '\'' && c != '<' && c != '>') ||
      editor.getSelectionModel.hasSelection
    then return TypedHandlerDelegate.Result.CONTINUE
    val offset = editor.getCaretModel.getOffset
    val source = editor.getDocument.getCharsSequence
    val lexer = new BendLexer()
    lexer.start(source)
    var endedInComment = false
    while lexer.getTokenType != null && lexer.getTokenEnd <= offset do
      endedInComment =
        lexer.getTokenType == BendTokens.Comment && lexer.getTokenEnd == offset
      lexer.advance()
    if endedInComment || lexer.getTokenType == BendTokens.Comment && lexer.getTokenStart <= offset
    then
      if c == '"' || c == '\'' then
        editor.getDocument.insertString(offset, c.toString)
        editor.getCaretModel.moveToOffset(offset + 1)
        TypedHandlerDelegate.Result.STOP
      else TypedHandlerDelegate.Result.CONTINUE
    else
      val mode = lexer.getState & 3
      if c == '<' || c == '>' then
        if mode != 0 then return TypedHandlerDelegate.Result.CONTINUE
        if c == '<' && BendTypeAngleContext.at(file, offset) then
          editor.getDocument.insertString(offset, "<>")
          BendInsertedAngles.add(editor, offset)
          editor.getCaretModel.moveToOffset(offset + 1)
          return TypedHandlerDelegate.Result.STOP
        if c == '>' && BendInsertedAngles.consumeCloserAt(editor, offset) then
          editor.getCaretModel.moveToOffset(offset + 1)
          return TypedHandlerDelegate.Result.STOP
        return TypedHandlerDelegate.Result.CONTINUE
      val wantedMode = if c == '"' then 1 else 2
      var slashes = 0
      var previous = offset - 1
      while previous >= 0 && source.charAt(previous) == '\\' do
        slashes += 1
        previous -= 1
      if mode == wantedMode && slashes % 2 == 0 && offset < source.length && source
          .charAt(offset) == c
      then
        editor.getCaretModel.moveToOffset(offset + 1)
        TypedHandlerDelegate.Result.STOP
      else if mode != 0 then
        editor.getDocument.insertString(offset, c.toString)
        editor.getCaretModel.moveToOffset(offset + 1)
        TypedHandlerDelegate.Result.STOP
      else
        editor.getDocument.insertString(offset, s"$c$c")
        editor.getCaretModel.moveToOffset(offset + 1)
        TypedHandlerDelegate.Result.STOP
