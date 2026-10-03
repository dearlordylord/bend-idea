package com.dearlordylord.bend.idea.syntax.psi

import com.dearlordylord.bend.idea.syntax.parser.BendLayoutPolicy
import com.intellij.application.options.CodeStyle
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId
import com.intellij.psi.codeStyle.CodeStyleSettings
import com.intellij.psi.PsiFile
import org.editorconfig.core.EditorConfig
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

/** Reads IntelliJ defaults and EditorConfig properties for one physical file.
  */
object BendEffectiveIndent:
  def forFormattingFile(file: PsiFile): BendLayoutPolicy.Settings =
    effective(file, true)

  /** Typing indentation is independent of wrapping validity or width. */
  def forFile(file: PsiFile): BendLayoutPolicy.Settings = effective(file, false)

  private def effective(
      file: PsiFile,
      wrapping: Boolean
  ): BendLayoutPolicy.Settings =
    val options = CodeStyle.getIndentOptions(file)
    val base = BendLayoutPolicy.Settings(
      options.INDENT_SIZE,
      options.USE_TAB_CHARACTER,
      options.TAB_SIZE
    )
    if !editorConfigEnabled(file) then return base
    Option(file.getVirtualFile) match
      case None              => base
      case Some(virtualFile) =>
        indentOverride(virtualFile.getPath, base, wrapping).getOrElse(base)

  /** The optional IDE plugin owns its enable toggle; offline path lookup does
    * not. Reflection keeps the bridge loadable when that optional plugin is
    * absent.
    */
  def editorConfigEnabled(file: PsiFile): Boolean =
    Option(
      PluginManagerCore.getPlugin(
        PluginId.getId("org.editorconfig.editorconfigjetbrains")
      )
    )
      .filter(_.isEnabled) match
      case None         => true
      case Some(plugin) =>
        try
          val loader = plugin.getPluginClassLoader
          val customClass =
            loader.loadClass("org.editorconfig.settings.EditorConfigSettings")
          val settings = CodeStyle.getSettings(file.getProject)
          // Materialize the plugin's default (enabled) without replacing an
          // existing setting before delegating to its own enable policy.
          classOf[CodeStyleSettings]
            .getMethod("getCustomSettings", classOf[Class[?]])
            .invoke(settings, customClass)
          loader
            .loadClass("org.editorconfig.Utils")
            .getMethod("isEnabled", classOf[CodeStyleSettings])
            .invoke(null, settings) match
            case enabled: java.lang.Boolean => enabled.booleanValue()
            case _                          => true
        catch
          case NonFatal(_)     => true
          case _: LinkageError => true

  def forPath(
      path: String,
      base: BendLayoutPolicy.Settings
  ): BendLayoutPolicy.Settings =
    indentOverride(path, base).getOrElse(base)

  def indentOverride(
      path: String,
      base: BendLayoutPolicy.Settings,
      wrapping: Boolean = true
  ): Option[BendLayoutPolicy.Settings] =
    try
      val properties = new EditorConfig()
        .getProperties(path)
        .asScala
        .map(pair =>
          pair.getKey.toLowerCase(java.util.Locale.ROOT) -> pair.getVal
        )
        .toMap
      val relevant =
        if wrapping then properties else properties - "bend_max_line_length"
      if !relevant.keysIterator.exists(
          Set(
            "indent_style",
            "indent_size",
            "tab_width",
            "bend_max_line_length"
          )
        )
      then None
      else
        Some(
          BendLayoutPolicy
            .fromEditorConfig(relevant, base)
            .getOrElse(base.copy(indentSize = 0))
        )
    catch case NonFatal(_) => Some(base.copy(indentSize = 0))
