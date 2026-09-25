package com.dearlordylord.bend.idea.features.settings

import com.dearlordylord.bend.idea.toolchain.api.{
  BendCompilerInfoResult,
  BendCompilerInfoProbe,
  BendToolchainChoices,
  BendToolchainPaths,
  BendToolchainSettings
}
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.{BaseConfigurable, ConfigurationException}
import com.intellij.openapi.progress.{ProgressIndicator, Task}
import com.intellij.util.ui.FormBuilder
import java.util.concurrent.atomic.AtomicLong
import javax.swing.{JButton, JCheckBox, JComponent, JLabel, JPanel, JTextField}

/** Application settings; the status tells users how to repair a missing Base.
  */
final class BendSettingsConfigurable extends BaseConfigurable:
  private var panel: JPanel = null
  private var executable: JTextField = null
  private var base: JTextField = null
  private var cache: JTextField = null
  private var diagnostics: JCheckBox = null
  private var status: JLabel = null
  private var compilerInfo: JLabel = null
  private var detectCompiler: JButton = null
  private val probeGeneration = new AtomicLong(0L)

  private def settings: BendToolchainSettings =
    ApplicationManager.getApplication.getService(classOf[BendToolchainSettings])

  override def getDisplayName: String = "Bend"

  override def createComponent(): JComponent =
    executable = new JTextField()
    base = new JTextField()
    cache = new JTextField()
    diagnostics = new JCheckBox("Enable background diagnostics")
    status = new JLabel()
    compilerInfo = new JLabel("Not detected")
    detectCompiler = new JButton("Detect")
    detectCompiler.addActionListener(_ => inspectCompiler())
    val compilerRow = new JPanel(
      new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0)
    )
    compilerRow.add(compilerInfo)
    compilerRow.add(detectCompiler)
    panel = FormBuilder
      .createFormBuilder()
      .addLabeledComponent("Executable (default ~/.bend/bin/bend):", executable)
      .addLabeledComponent("Compiler version:", compilerRow)
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
    inspectCompiler()

  override def reset(): Unit =
    val value = settings.choices
    executable.setText(value.executable)
    base.setText(value.baseSource)
    cache.setText(value.packageCache)
    diagnostics.setSelected(value.diagnosticsEnabled)
    refreshStatus()
    inspectCompiler()

  private def refreshStatus(): Unit =
    val selected = settings.selection.baseSource
    if java.nio.file.Files.isRegularFile(java.nio.file.Path.of(selected)) then
      status.setText("Base source: " + selected)
    else
      status.setText(
        "Base source unavailable. Select an installed base.bend file to enable Base names."
      )

  private def inspectCompiler(): Unit =
    if compilerInfo == null || detectCompiler == null then return
    val generation = probeGeneration.incrementAndGet()
    val executable = BendToolchainPaths
      .resolve(
        fields,
        System.getProperty("user.home"),
        Option(System.getenv("BEND_LIB")),
        settings.selection.configurationRevision
      )
      .executable
    val probe = ApplicationManager.getApplication
      .getService(classOf[BendCompilerInfoProbe])
    compilerInfo.setText("Detecting…")
    compilerInfo.setToolTipText(executable)
    detectCompiler.setEnabled(false)
    new Task.Backgroundable(null, "Detecting Bend compiler", true):
      private var result: BendCompilerInfoResult =
        BendCompilerInfoResult.Canceled

      override def run(indicator: ProgressIndicator): Unit =
        result = probe.inspect(
          executable,
          () => indicator.isCanceled || probeGeneration.get() != generation
        )

      override def onSuccess(): Unit = publishCompilerInfo(generation, result)

      override def onCancel(): Unit =
        publishCompilerInfo(generation, BendCompilerInfoResult.Canceled)

      override def onThrowable(error: Throwable): Unit =
        val details =
          Option(error.getMessage).getOrElse("Compiler probe failed.")
        publishCompilerInfo(
          generation,
          BendCompilerInfoResult.Unavailable(details)
        )
    .queue()

  private def publishCompilerInfo(
      generation: Long,
      result: BendCompilerInfoResult
  ): Unit =
    if generation == probeGeneration.get() && compilerInfo != null then
      val text = result match
        case BendCompilerInfoResult.Detected(info) =>
          info.version.fold("Version not reported")(v => s"Bend $v")
        case BendCompilerInfoResult.Unavailable(details) =>
          s"Compiler unavailable — $details"
        case BendCompilerInfoResult.TimedOut => "Compiler probe timed out"
        case BendCompilerInfoResult.Canceled => "Compiler probe canceled"
      compilerInfo.setText(text)
      detectCompiler.setEnabled(true)

  override def disposeUIResources(): Unit =
    val _ = probeGeneration.incrementAndGet()
    panel = null
    executable = null
    base = null
    cache = null
    diagnostics = null
    status = null
    compilerInfo = null
    detectCompiler = null
