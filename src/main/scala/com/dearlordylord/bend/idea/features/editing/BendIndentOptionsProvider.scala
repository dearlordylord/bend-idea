package com.dearlordylord.bend.idea.features.editing

import com.intellij.openapi.fileTypes.{FileType, FileTypeManager}
import com.intellij.psi.PsiFile
import com.intellij.psi.codeStyle.{
  CommonCodeStyleSettings,
  FileTypeIndentOptionsProvider
}

final class BendIndentOptionsProvider extends FileTypeIndentOptionsProvider:
  override def getFileType: FileType =
    FileTypeManager.getInstance.getFileTypeByExtension("bend")
  override def createIndentOptions(): CommonCodeStyleSettings.IndentOptions =
    val options = new CommonCodeStyleSettings.IndentOptions()
    options.INDENT_SIZE = 2
    options.CONTINUATION_INDENT_SIZE = 2
    options.TAB_SIZE = 2
    options.USE_TAB_CHARACTER = false
    options
  override def getPreviewText: String =
    "def main():\n  do IO<Unit>:\n    IO.print(\"Bend\")\n"
  override def prepareForReformat(file: PsiFile): Unit = ()
