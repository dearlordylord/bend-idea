package com.dearlordylord.bend.idea.syntax.psi

import com.dearlordylord.bend.idea.syntax.parser.BendLayoutPolicy
import com.intellij.psi.PsiFile
import com.intellij.psi.codeStyle.{
  CodeStyleSettings,
  CommonCodeStyleSettings,
  FileIndentOptionsProvider
}

/** Supplies effective per-file indentation to IntelliJ's formatting engine. */
final class BendFileIndentOptionsProvider extends FileIndentOptionsProvider:
  override def getIndentOptions(
      settings: CodeStyleSettings,
      file: PsiFile
  ): CommonCodeStyleSettings.IndentOptions =
    if file.getFileType.getDefaultExtension != "bend" || file.getVirtualFile == null
    then return null
    if !BendEffectiveIndent.editorConfigEnabled(file) then return null
    val base = settings.getIndentOptions(file.getFileType)
    val effective = BendEffectiveIndent.indentOverride(
      file.getVirtualFile.getPath,
      BendLayoutPolicy.Settings(
        base.INDENT_SIZE,
        base.USE_TAB_CHARACTER,
        base.TAB_SIZE
      ),
      wrapping = false
    )
    if effective.isEmpty || !effective.get.valid then return null
    val result = new CommonCodeStyleSettings.IndentOptions()
    result.copyFrom(base)
    result.INDENT_SIZE = effective.get.indentSize
    result.TAB_SIZE = effective.get.tabWidth
    result.USE_TAB_CHARACTER = effective.get.useTabs
    result

  override def useOnFullReformat(): Boolean = true
