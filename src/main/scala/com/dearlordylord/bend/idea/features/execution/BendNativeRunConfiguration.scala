package com.dearlordylord.bend.idea.features.execution

import com.dearlordylord.bend.idea.features.execution.api.{
  BendNativeProcessFactory,
  BendNativeRunRequest
}
import com.intellij.execution.{Executor, ExecutionException}
import com.intellij.execution.configurations.{
  CommandLineState,
  ConfigurationFactory,
  RunConfigurationBase,
  RunConfigurationOptions,
  RunProfileState,
  RuntimeConfigurationException
}
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.options.{ConfigurationException, SettingsEditor}
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBTextField
import java.awt.GridBagLayout
import java.nio.file.Files
import javax.swing.{JComponent, JPanel, JTextArea}
import org.jdom.Element

final class BendNativeRunConfiguration(
    project: Project,
    factory: ConfigurationFactory,
    name: String
) extends RunConfigurationBase[RunConfigurationOptions](project, factory, name):
  var executablePath: String = ""
  var workingDirectory: String = ""
  var threads: String = ""
  var gpu: String = ""
  var programArguments: String = ""
  var environmentLines: String = ""

  override def getConfigurationEditor: SettingsEditor[
    ? <: com.intellij.execution.configurations.RunConfiguration
  ] = new BendNativeRunConfigurationEditor

  override def checkConfiguration(): Unit =
    nativeRequest() match
      case Left(message) => throw new RuntimeConfigurationException(message)
      case Right(_)      => ()

  override def getState(
      executor: Executor,
      environment: ExecutionEnvironment
  ): RunProfileState =
    val request = nativeRequest().fold(
      message => throw new ExecutionException(message),
      identity
    )
    new CommandLineState(environment):
      override protected def startProcess(): ProcessHandler =
        BendExecutionLaunch.saveAllDocuments(project)
        project
          .getService(classOf[BendNativeProcessFactory])
          .start(request)

  private[execution] def nativeRequest(): Either[String, BendNativeRunRequest] =
    val base = BendExecutionConfigurationParsing.projectBase(project)
    for
      executable <-
        if executablePath.trim.isEmpty then Left("Choose a native executable.")
        else BendExecutionConfigurationParsing.absolute(executablePath, base)
      _ <- Either.cond(
        Files.isRegularFile(executable) && Files.isExecutable(executable),
        (),
        "Choose an existing executable Bend build."
      )
      directory <-
        if workingDirectory.trim.isEmpty then Right(executable.getParent)
        else BendExecutionConfigurationParsing.absolute(workingDirectory, base)
      _ <- Either.cond(
        directory != null && Files.isDirectory(directory),
        (),
        "Choose an existing working directory."
      )
      threadCount <- parseThreads(threads)
      gpuOption <- parseGpu(gpu)
      args <- BendExecutionConfigurationParsing.arguments(programArguments)
      env <- BendExecutionConfigurationParsing.environment(environmentLines)
    yield BendNativeRunRequest(
      executable.toString,
      directory.toString,
      env,
      threadCount,
      gpuOption,
      args
    )

  private def parseThreads(value: String): Either[String, Option[Int]] =
    if value.trim.isEmpty then Right(None)
    else
      value.trim.toIntOption match
        case Some(count) if count >= 1 && count <= 128 => Right(Some(count))
        case _ => Left("Threads must be a whole number from 1 to 128.")

  private def parseGpu(value: String): Either[String, Option[String]] =
    val selected = value.trim
    if selected.isEmpty then Right(None)
    else if selected == "on" || selected == "off" then Right(Some(selected))
    else if selected.matches("[1-9][0-9]*(MB|GB)") then Right(Some(selected))
    else Left("GPU must be on, off, or a positive memory size such as 4GB.")

  override def readExternal(element: Element): Unit =
    super.readExternal(element)
    executablePath =
      Option(element.getAttributeValue("executablePath")).getOrElse("")
    workingDirectory =
      Option(element.getAttributeValue("workingDirectory")).getOrElse("")
    threads = Option(element.getAttributeValue("threads")).getOrElse("")
    gpu = Option(element.getAttributeValue("gpu")).getOrElse("")
    programArguments =
      Option(element.getAttributeValue("programArguments")).getOrElse("")
    environmentLines =
      Option(element.getAttributeValue("environmentLines")).getOrElse("")

  override def writeExternal(element: Element): Unit =
    super.writeExternal(element)
    val _ = element.setAttribute("executablePath", executablePath)
    val _ = element.setAttribute("workingDirectory", workingDirectory)
    val _ = element.setAttribute("threads", threads)
    val _ = element.setAttribute("gpu", gpu)
    val _ = element.setAttribute("programArguments", programArguments)
    val _ = element.setAttribute("environmentLines", environmentLines)

  override def clone(): BendNativeRunConfiguration =
    val copied = super.clone() match
      case configuration: BendNativeRunConfiguration => configuration
      case _                                         =>
        throw new IllegalStateException(
          "Unexpected native run configuration clone type"
        )
    copied.executablePath = executablePath
    copied.workingDirectory = workingDirectory
    copied.threads = threads
    copied.gpu = gpu
    copied.programArguments = programArguments
    copied.environmentLines = environmentLines
    copied

private final class BendNativeRunConfigurationEditor
    extends SettingsEditor[BendNativeRunConfiguration]:
  private val executable = new JBTextField()
  private val workingDirectory = new JBTextField()
  private val threads = new JBTextField()
  private val gpu = new JBTextField()
  private val arguments = new JBTextField()
  private val environment = new JTextArea(4, 40)
  private val panel = new JPanel(new GridBagLayout())
  private val fields = List(
    "Native executable" -> executable,
    "Working directory (blank keeps companion files beside executable)" -> workingDirectory,
    "Threads (blank uses runtime default; 1 to 128)" -> threads,
    "GPU (blank uses runtime default; on, off, 4GB, or 512MB)" -> gpu,
    "Program arguments" -> arguments
  )
  fields.zipWithIndex.foreach { case ((label, field), row) =>
    BendExecutionEditorRows.add(panel, label, field, row)
  }
  BendExecutionEditorRows.addArea(
    panel,
    "Environment (one KEY=VALUE per line)",
    environment,
    fields.size
  )
  BendExecutionEditorRows.addNote(
    panel,
    "All open IDE documents are saved before each launch.",
    fields.size + 1
  )

  override protected def createEditor(): JComponent = panel

  override protected def resetEditorFrom(
      settings: BendNativeRunConfiguration
  ): Unit =
    executable.setText(settings.executablePath)
    workingDirectory.setText(settings.workingDirectory)
    threads.setText(settings.threads)
    gpu.setText(settings.gpu)
    arguments.setText(settings.programArguments)
    environment.setText(settings.environmentLines)

  override protected def applyEditorTo(
      settings: BendNativeRunConfiguration
  ): Unit =
    settings.executablePath = executable.getText
    settings.workingDirectory = workingDirectory.getText
    settings.threads = threads.getText
    settings.gpu = gpu.getText
    settings.programArguments = arguments.getText
    settings.environmentLines = environment.getText
    settings.nativeRequest() match
      case Left(message) => throw new ConfigurationException(message)
      case Right(_)      => ()
