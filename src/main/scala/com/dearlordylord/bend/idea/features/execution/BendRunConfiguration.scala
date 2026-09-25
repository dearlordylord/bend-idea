package com.dearlordylord.bend.idea.features.execution

import com.dearlordylord.bend.idea.features.execution.api.{
  BendExecutionProcessFactory,
  BendExecutionRequest
}
import com.dearlordylord.bend.idea.syntax.BendFileType
import com.dearlordylord.bend.idea.toolchain.api.BendToolchainSettings
import com.intellij.execution.{Executor, ExecutionException}
import com.intellij.execution.configurations.{
  CommandLineState,
  ConfigurationFactory,
  ConfigurationTypeBase,
  RunConfigurationBase,
  RunConfigurationOptions,
  RunProfileState,
  RuntimeConfigurationException
}
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.ui.ConsoleView
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.options.{ConfigurationException, SettingsEditor}
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBTextField
import java.awt.{GridBagConstraints, GridBagLayout, Insets}
import java.nio.file.Files
import javax.swing.{JComponent, JLabel, JPanel, JScrollPane, JTextArea}
import org.jdom.Element

final class BendRunConfigurationType
    extends ConfigurationTypeBase(
      "BendRunConfiguration",
      "Bend",
      "Run a Bend source file",
      new BendFileType().getIcon
    ):
  addFactory(new ConfigurationFactory(this):
    override def getName: String = "Bend Run"

    override def createTemplateConfiguration(
        project: Project
    ): BendRunConfiguration =
      new BendRunConfiguration(project, this, "Bend Run"))
  addFactory(new ConfigurationFactory(this):
    override def getName: String = "Bend Build"

    override def createTemplateConfiguration(
        project: Project
    ): BendBuildConfiguration =
      new BendBuildConfiguration(project, this, "Bend Build"))
  addFactory(new ConfigurationFactory(this):
    override def getName: String = "Bend Native"

    override def createTemplateConfiguration(
        project: Project
    ): BendNativeRunConfiguration =
      new BendNativeRunConfiguration(project, this, "Bend Native"))

final class BendRunConfiguration(
    project: Project,
    factory: ConfigurationFactory,
    name: String
) extends RunConfigurationBase[RunConfigurationOptions](project, factory, name):
  var rootPath: String = ""
  var executablePath: String = ""
  var workingDirectory: String = ""
  var programArguments: String = ""
  var environmentLines: String = ""

  override def getConfigurationEditor: SettingsEditor[
    ? <: com.intellij.execution.configurations.RunConfiguration
  ] =
    new BendRunConfigurationEditor(project)

  override def getState(
      executor: Executor,
      environment: ExecutionEnvironment
  ): RunProfileState =
    val request = executionRequest().fold(
      message => throw new ExecutionException(message),
      identity
    )
    new CommandLineState(environment):
      override protected def startProcess(): ProcessHandler =
        BendExecutionLaunch.start(project, request)

      override protected def createConsole(executor: Executor): ConsoleView =
        val console = super.createConsole(executor)
        console.addMessageFilter(new BendExecutionSourceFilter(project))
        console

  override def checkConfiguration(): Unit =
    executionRequest() match
      case Left(message) => throw new RuntimeConfigurationException(message)
      case Right(_)      => ()

  private[execution] def executionRequest()
      : Either[String, BendExecutionRequest] =
    val projectBase = BendExecutionConfigurationParsing.projectBase(project)
    for
      root <-
        if rootPath.trim.isEmpty then Left("Choose a Bend root source file.")
        else BendExecutionConfigurationParsing.absolute(rootPath, projectBase)
      _ <- Either.cond(
        root.toString.endsWith(".bend") && Files.isRegularFile(root),
        (),
        "Choose an existing .bend root source file."
      )
      selectedExecutable =
        if executablePath.trim.nonEmpty then executablePath.trim
        else
          ApplicationManager.getApplication
            .getService(classOf[BendToolchainSettings])
            .selection
            .executable
      executable <- BendExecutionConfigurationParsing.absolute(
        selectedExecutable,
        projectBase
      )
      _ <- Either.cond(
        Files.isRegularFile(executable) && Files.isExecutable(executable),
        (),
        "The Bend executable is missing or not executable. Configure it in Settings | Bend."
      )
      directory <-
        if workingDirectory.trim.isEmpty then Right(root.getParent)
        else
          BendExecutionConfigurationParsing.absolute(
            workingDirectory,
            projectBase
          )
      _ <- Either.cond(
        directory != null && Files.isDirectory(directory),
        (),
        "Choose an existing working directory."
      )
      environment <- BendExecutionConfigurationParsing.environment(
        environmentLines
      )
      arguments <- BendExecutionConfigurationParsing.arguments(programArguments)
    yield BendExecutionRequest(
      executable.toString,
      root.toString,
      directory.toString,
      environment,
      arguments
    )

  override def readExternal(element: Element): Unit =
    super.readExternal(element)
    rootPath = Option(element.getAttributeValue("rootPath")).getOrElse("")
    executablePath =
      Option(element.getAttributeValue("executablePath")).getOrElse("")
    workingDirectory =
      Option(element.getAttributeValue("workingDirectory")).getOrElse("")
    programArguments =
      Option(element.getAttributeValue("programArguments")).getOrElse("")
    environmentLines =
      Option(element.getAttributeValue("environmentLines")).getOrElse("")

  override def writeExternal(element: Element): Unit =
    super.writeExternal(element)
    val _ = element.setAttribute("rootPath", rootPath)
    val _ = element.setAttribute("executablePath", executablePath)
    val _ = element.setAttribute("workingDirectory", workingDirectory)
    val _ = element.setAttribute("programArguments", programArguments)
    val _ = element.setAttribute("environmentLines", environmentLines)

  override def clone(): BendRunConfiguration =
    val copied = super.clone().asInstanceOf[BendRunConfiguration]
    copied.rootPath = rootPath
    copied.executablePath = executablePath
    copied.workingDirectory = workingDirectory
    copied.programArguments = programArguments
    copied.environmentLines = environmentLines
    copied

private final class BendRunConfigurationEditor(project: Project)
    extends SettingsEditor[BendRunConfiguration]:
  private val rootPath = BendRootSourceField.create(project)
  private val executable = new JBTextField()
  private val workingDirectory = new JBTextField()
  private val arguments = new JBTextField()
  private val environment = new JTextArea(4, 40)
  private val panel = new JPanel(new GridBagLayout())
  private val fields = List(
    "Root source (.bend)" -> rootPath,
    "Bend executable (blank uses Settings | Bend)" -> executable,
    "Working directory (blank uses root directory)" -> workingDirectory,
    "Program arguments" -> arguments
  )
  fields.zipWithIndex.foreach { case ((label, field), row) =>
    val constraints = new GridBagConstraints()
    constraints.gridx = 0
    constraints.gridy = row
    constraints.anchor = GridBagConstraints.WEST
    constraints.insets = new Insets(4, 4, 4, 8)
    panel.add(new JLabel(label), constraints)
    constraints.gridx = 1
    constraints.weightx = 1.0
    constraints.fill = GridBagConstraints.HORIZONTAL
    constraints.insets = new Insets(4, 0, 4, 4)
    panel.add(field, constraints)
  }
  private val envLabel = new GridBagConstraints()
  envLabel.gridx = 0
  envLabel.gridy = fields.size
  envLabel.anchor = GridBagConstraints.NORTHWEST
  envLabel.insets = new Insets(4, 4, 4, 8)
  panel.add(new JLabel("Environment (one KEY=VALUE per line)"), envLabel)
  private val envField = new GridBagConstraints()
  envField.gridx = 1
  envField.gridy = fields.size
  envField.weightx = 1.0
  envField.fill = GridBagConstraints.HORIZONTAL
  envField.insets = new Insets(4, 0, 4, 4)
  panel.add(new JScrollPane(environment), envField)
  private val savePolicy = new GridBagConstraints()
  savePolicy.gridx = 0
  savePolicy.gridy = fields.size + 1
  savePolicy.gridwidth = 2
  savePolicy.anchor = GridBagConstraints.WEST
  savePolicy.insets = new Insets(8, 4, 4, 4)
  panel.add(
    new JLabel("All open IDE documents are saved before each launch."),
    savePolicy
  )

  override protected def createEditor(): JComponent = panel

  override protected def resetEditorFrom(settings: BendRunConfiguration): Unit =
    rootPath.setText(settings.rootPath)
    executable.setText(settings.executablePath)
    workingDirectory.setText(settings.workingDirectory)
    arguments.setText(settings.programArguments)
    environment.setText(settings.environmentLines)

  override protected def applyEditorTo(
      settings: BendRunConfiguration
  ): Unit =
    settings.rootPath = rootPath.getText
    settings.executablePath = executable.getText
    settings.workingDirectory = workingDirectory.getText
    settings.programArguments = arguments.getText
    settings.environmentLines = environment.getText
    settings.executionRequest() match
      case Left(message) => throw new ConfigurationException(message)
      case Right(_)      => ()

private object BendExecutionLaunch:
  def start(project: Project, request: BendExecutionRequest): ProcessHandler =
    saveAllDocuments(project)
    project
      .getService(classOf[BendExecutionProcessFactory])
      .start(request)

  def saveAllDocuments(project: Project): Unit =
    val application = ApplicationManager.getApplication
    val save = new Runnable:
      override def run(): Unit =
        FileDocumentManager.getInstance().saveAllDocuments()
    if application.isDispatchThread then save.run()
    else application.invokeAndWait(save)
