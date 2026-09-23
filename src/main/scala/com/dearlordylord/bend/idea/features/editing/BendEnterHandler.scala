package com.dearlordylord.bend.idea.features.editing

import com.dearlordylord.bend.idea.syntax.BendLanguage
import com.dearlordylord.bend.idea.syntax.parser.BendIndentPolicy
import com.intellij.application.options.CodeStyle
import com.intellij.codeInsight.editorActions.enter.EnterHandlerDelegateAdapter
import com.intellij.codeInsight.editorActions.enter.EnterHandlerDelegate.Result
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiFile

final class BendEnterHandler extends EnterHandlerDelegateAdapter:
  override def postProcessEnter(file: PsiFile, editor: Editor, context: DataContext): Result =
    if file.getLanguage != BendLanguage.instance then return Result.Continue
    val document = editor.getDocument
    val caret = editor.getCaretModel.getOffset
    val source = document.getText
    val step = math.max(1, CodeStyle.getIndentOptions(file).INDENT_SIZE)
    BendIndentPolicy.afterEnter(source, caret, step).foreach { indent =>
      val lineStart = source.lastIndexOf('\n', caret - 1) + 1
      var firstCode = lineStart
      while firstCode < source.length && (source.charAt(firstCode) == ' ' || source.charAt(firstCode) == '\t') do
        firstCode += 1
      if firstCode == source.length || source.charAt(firstCode) == '\n' || source.charAt(firstCode) == '\r' ||
          caret <= firstCode then
        document.replaceString(lineStart, firstCode, " " * indent)
        editor.getCaretModel.moveToOffset(lineStart + indent)
    }
    Result.Continue
