package com.dearlordylord.bend.idea.features.settings

import com.dearlordylord.bend.idea.toolchain.api.{
  BendCompilerInfoResult,
  BendLintOptions,
  BendLintTools,
  BendLintInstallationResult,
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
  private var lintEnabled: JCheckBox = null
  private var lintBun: JTextField = null
  private var lintDirectory: JTextField = null
  private var lintBackground: JCheckBox = null
  private var lintStatus: JLabel = null
  private var installLint: JButton = null
  private var rollbackLint: JButton = null
  private val lintGeneration = new AtomicLong(0L)
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
    lintEnabled = new JCheckBox(
      "Enable optional bend-lint diagnostics"
    )
    lintBun = new JTextField()
    lintDirectory = new JTextField()
    lintBackground = new JCheckBox(
      "Run bend-lint with background compiler checks"
    )
    lintStatus = new JLabel("Not installed")
    installLint = new JButton("Install / repair pinned tools")
    rollbackLint = new JButton("Use previous installation")
    installLint.addActionListener(_ => manageLint(rollback = false))
    rollbackLint.addActionListener(_ => manageLint(rollback = true))
    val lintToolsRow = new JPanel(
      new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0)
    )
    lintToolsRow.add(installLint)
    lintToolsRow.add(rollbackLint)
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
      .addSeparator()
      .addComponent(lintEnabled)
      .addLabeledComponent("Bun executable:", lintBun)
      .addLabeledComponent("Prepared bend-lint installation:", lintDirectory)
      .addComponent(lintBackground)
      .addComponent(lintToolsRow)
      .addComponent(lintStatus)
      .addComponent(
        new JLabel(
          "Pinned lint 0.3.0 uses its own Bend 2.0.39; findings are separate from compiler checks."
        )
      )
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
      diagnostics.isSelected,
      BendLintOptions(
        lintEnabled.isSelected,
        lintBun.getText,
        lintDirectory.getText,
        lintBackground.isSelected
      )
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
    lintEnabled.setSelected(value.lint.enabled)
    lintBun.setText(value.lint.bun)
    lintDirectory.setText(value.lint.directory)
    lintBackground.setSelected(value.lint.background)
    lintStatus.setText(if value.lint.directory.trim.isEmpty then "Not installed"
    else "Selected tools: " + value.lint.directory)
    refreshStatus()
    inspectCompiler()

  private def manageLint(rollback: Boolean): Unit =
    val generation = lintGeneration.incrementAndGet()
    val bun = lintBun.getText.trim
    val tools =
      ApplicationManager.getApplication.getService(classOf[BendLintTools])
    installLint.setEnabled(false)
    rollbackLint.setEnabled(false)
    lintStatus.setText(if rollback then "Selecting previous tools…"
    else "Installing verified tools…")
    new Task.Backgroundable(null, "Bend lint tools", true):
      private var result: BendLintInstallationResult =
        BendLintInstallationResult.Canceled
      override def run(indicator: ProgressIndicator): Unit =
        result = if rollback then tools.rollback()
        else
          tools.install(
            bun,
            () => indicator.isCanceled || lintGeneration.get() != generation
          )
      override def onSuccess(): Unit = publishLintTools(generation, result)
      override def onCancel(): Unit =
        publishLintTools(generation, BendLintInstallationResult.Canceled)
      override def onThrowable(error: Throwable): Unit =
        publishLintTools(
          generation,
          BendLintInstallationResult.Unavailable(
            Option(error.getMessage).getOrElse("Lint installation failed.")
          )
        )
    .queue()

  private def publishLintTools(
      generation: Long,
      result: BendLintInstallationResult
  ): Unit =
    if lintGeneration.get() != generation || lintStatus == null then return
    result match
      case BendLintInstallationResult.Installed(bun, directory, details) =>
        lintBun.setText(bun)
        lintDirectory.setText(directory)
        lintEnabled.setSelected(true)
        lintStatus.setText(details + " Apply settings to use these tools.")
      case BendLintInstallationResult.Unavailable(details) =>
        lintStatus.setText(details)
      case BendLintInstallationResult.Canceled =>
        lintStatus.setText(
          "Installation canceled; previous tools remain available."
        )
    installLint.setEnabled(true)
    rollbackLint.setEnabled(true)

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
    val _ = lintGeneration.incrementAndGet()
    panel = null
    executable = null
    base = null
    cache = null
    diagnostics = null
    lintEnabled = null
    lintBun = null
    lintDirectory = null
    lintBackground = null
    lintStatus = null
    installLint = null
    rollbackLint = null
    status = null
    compilerInfo = null
    detectCompiler = null
