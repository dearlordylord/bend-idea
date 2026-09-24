package com.dearlordylord.bend.idea.features.settings

import com.dearlordylord.bend.idea.toolchain.api.{
  BendToolchainChoices,
  BendToolchainSettings
}
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.{BaseConfigurable, ConfigurationException}
import com.intellij.util.ui.FormBuilder
import javax.swing.{JCheckBox, JComponent, JLabel, JPanel, JTextField}

/** Application settings; the status tells users how to repair a missing Base.
  */
final class BendSettingsConfigurable extends BaseConfigurable:
  private var panel: JPanel = null
  private var executable: JTextField = null
  private var base: JTextField = null
  private var cache: JTextField = null
  private var diagnostics: JCheckBox = null
  private var status: JLabel = null

  private def settings: BendToolchainSettings =
    ApplicationManager.getApplication.getService(classOf[BendToolchainSettings])

  override def getDisplayName: String = "Bend"

  override def createComponent(): JComponent =
    executable = new JTextField()
    base = new JTextField()
    cache = new JTextField()
    diagnostics = new JCheckBox("Enable background diagnostics")
    status = new JLabel()
    panel = FormBuilder
      .createFormBuilder()
      .addLabeledComponent("Executable (default ~/.bend/bin/bend):", executable)
      .addLabeledComponent(
        "Base source (default ~/.bend/bend2/base.bend):",
        base
      )
      .addLabeledComponent(
        "Package cache (default BEND_LIB or ~/.bend/lib):",
        cache
      )
      .addComponent(diagnostics)
      .addComponent(status)
      .addComponentFillVertically(new JPanel(), 0)
      .getPanel
    reset()
    panel

  override def isModified: Boolean = fields != settings.choices

  private def fields: BendToolchainChoices =
    BendToolchainChoices(
      executable.getText,
      base.getText,
      cache.getText,
      diagnostics.isSelected
    )

  override def apply(): Unit =
    try settings.update(fields)
    catch
      case error: IllegalArgumentException =>
        throw new ConfigurationException(error.getMessage)
    refreshStatus()

  override def reset(): Unit =
    val value = settings.choices
    executable.setText(value.executable)
    base.setText(value.baseSource)
    cache.setText(value.packageCache)
    diagnostics.setSelected(value.diagnosticsEnabled)
    refreshStatus()

  private def refreshStatus(): Unit =
    val selected = settings.selection.baseSource
    if java.nio.file.Files.isRegularFile(java.nio.file.Path.of(selected)) then
      status.setText("Base source: " + selected)
    else
      status.setText(
        "Base source unavailable. Select an installed base.bend file to enable Base names."
      )

  override def disposeUIResources(): Unit =
    panel = null
    executable = null
    base = null
    cache = null
    diagnostics = null
    status = null
