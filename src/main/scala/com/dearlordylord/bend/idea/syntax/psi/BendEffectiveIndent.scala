package com.dearlordylord.bend.idea.syntax.psi

import com.dearlordylord.bend.idea.syntax.parser.BendLayoutPolicy
import com.intellij.application.options.CodeStyle
import com.intellij.psi.PsiFile
import org.editorconfig.core.EditorConfig
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

/** Reads IntelliJ defaults and EditorConfig properties for one physical file.
  */
object BendEffectiveIndent:
  def forFile(file: PsiFile): BendLayoutPolicy.Settings =
    val options = CodeStyle.getIndentOptions(file)
    val base = BendLayoutPolicy.Settings(
      options.INDENT_SIZE,
      options.USE_TAB_CHARACTER,
      options.TAB_SIZE
    )
    Option(file.getVirtualFile) match
      case None              => base
      case Some(virtualFile) => forPath(virtualFile.getPath, base)

  def forPath(
      path: String,
      base: BendLayoutPolicy.Settings
  ): BendLayoutPolicy.Settings =
    indentOverride(path, base).getOrElse(base)

  def indentOverride(
      path: String,
      base: BendLayoutPolicy.Settings
  ): Option[BendLayoutPolicy.Settings] =
    try
      val properties = new EditorConfig()
        .getProperties(path)
        .asScala
        .map(pair =>
          pair.getKey.toLowerCase(java.util.Locale.ROOT) -> pair.getVal
        )
        .toMap
      if !properties.keysIterator.exists(
          Set("indent_style", "indent_size", "tab_width")
        )
      then None
      else
        Some(
          BendLayoutPolicy
            .fromEditorConfig(properties, base)
            .getOrElse(base.copy(indentSize = 0))
        )
    catch case NonFatal(_) => Some(base.copy(indentSize = 0))
