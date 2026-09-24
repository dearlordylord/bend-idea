package com.dearlordylord.bend.idea.features.execution

import com.dearlordylord.bend.idea.features.execution.api.{
  BendBuildOutputKind,
  BendBuildProcessFactory,
  BendBuildRequest
}
import com.dearlordylord.bend.idea.syntax.psi.BendForeignPaths
import com.dearlordylord.bend.idea.toolchain.api.BendToolchainSettings
import com.dearlordylord.bend.idea.workspace.api.{
  BendLoadingConfiguration,
  BendSourceCatalog,
  BendWorkspaceGraph
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
import com.intellij.execution.process.{
  ProcessAdapter,
  ProcessEvent,
  ProcessHandler
}
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.ui.{ConsoleView, ConsoleViewContentType}
import com.intellij.execution.filters.OpenFileHyperlinkInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.{ConfigurationException, SettingsEditor}
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.components.JBTextField
import java.awt.{GridBagConstraints, GridBagLayout, Insets}
import java.nio.file.{Files, Path}
import javax.swing.{
  JComboBox,
  JComponent,
  JLabel,
  JPanel,
  JScrollPane,
  JTextArea
}
import org.jdom.Element
import scala.util.control.NonFatal

final class BendBuildConfiguration(
    project: Project,
    factory: ConfigurationFactory,
    name: String
) extends RunConfigurationBase[RunConfigurationOptions](project, factory, name):
  var rootPath: String = ""
  var executablePath: String = ""
  var outputPath: String = ""
  var outputKind: BendBuildOutputKind = BendBuildOutputKind.Native
  var workingDirectory: String = ""
  var environmentLines: String = ""

  override def getConfigurationEditor: SettingsEditor[
    ? <: com.intellij.execution.configurations.RunConfiguration
  ] = new BendBuildConfigurationEditor

  override def checkConfiguration(): Unit =
    buildRequest() match
      case Left(message) => throw new RuntimeConfigurationException(message)
      case Right(_)      => ()

  override def getState(
      executor: Executor,
      environment: ExecutionEnvironment
  ): RunProfileState = new CommandLineState(environment):
    private var console: ConsoleView = null

    override protected def createConsole(executor: Executor): ConsoleView =
      val created = super.createConsole(executor)
      console = created
      created

    override protected def startProcess(): ProcessHandler =
      BendExecutionLaunch.saveAllDocuments(project)
      val request = buildRequest().fold(
        message => throw new ExecutionException(message),
        identity
      )
      val handler = project
        .getService(classOf[BendBuildProcessFactory])
        .start(request)
      handler.addProcessListener(new ProcessAdapter:
        override def processTerminated(event: ProcessEvent): Unit =
          if event.getExitCode == 0 then
            ApplicationManager.getApplication.invokeLater(() =>
              BendBuildConfiguration.refreshOutput(project, request, console)
            ))
      handler

  private[execution] def buildRequest(): Either[String, BendBuildRequest] =
    val base = BendExecutionConfigurationParsing.projectBase(project)
    val selectedExecutable =
      if executablePath.trim.nonEmpty then executablePath.trim
      else
        ApplicationManager.getApplication
          .getService(classOf[BendToolchainSettings])
          .selection
          .executable
    for
      root <-
        if rootPath.trim.isEmpty then Left("Choose a Bend root source file.")
        else BendExecutionConfigurationParsing.absolute(rootPath, base)
      _ <- Either.cond(
        root.toString.endsWith(".bend") && Files.isRegularFile(root),
        (),
        "Choose an existing .bend root source file."
      )
      executable <- BendBuildConfiguration.executable(selectedExecutable, base)
      directory <-
        if workingDirectory.trim.isEmpty then Right(root.getParent)
        else BendExecutionConfigurationParsing.absolute(workingDirectory, base)
      _ <- Either.cond(
        directory != null && Files.isDirectory(directory),
        (),
        "Choose an existing working directory."
      )
      outputText =
        if outputPath.trim.nonEmpty then outputPath
        else BendBuildConfiguration.defaultOutput(root, outputKind)
      output <- BendExecutionConfigurationParsing.absolute(outputText, base)
      _ <- Either.cond(
        output.getParent != null && Files.isDirectory(output.getParent),
        (),
        "Choose an output file in an existing directory."
      )
      _ <- Either.cond(
        !Files.isDirectory(output),
        (),
        "The output path is a directory. Choose an output file."
      )
      _ <- BendBuildConfiguration.validateKind(output, outputKind)
      environment <- BendExecutionConfigurationParsing.environment(
        environmentLines
      )
      rootSource <- project
        .getService(classOf[BendSourceCatalog])
        .source(root.toString)
        .toRight(
          "The Bend root source is unavailable in the current workspace."
        )
      (basePath, packageCache) = project
        .getService(classOf[BendLoadingConfiguration])
        .paths
      graph = project
        .getService(classOf[BendWorkspaceGraph])
        .load(rootSource, basePath, packageCache)
      inputPaths = graph.files.flatMap { file =>
        val bendSources = List(Path.of(file.source.path))
        val foreignSources = BendForeignPaths
          .inSource(project, file.source.text)
          .flatMap(path =>
            BendForeignPaths.resolve(file.source.path, path.spelling)
          )
        bendSources ++ foreignSources
      }
      _ <- Either.cond(
        !inputPaths.exists(input =>
          BendBuildConfiguration.samePath(output, input)
        ),
        (),
        "The output path would overwrite a Bend or foreign source in the loaded graph."
      )
    yield BendBuildRequest(
      executable.toString,
      root.toString,
      output.toString,
      outputKind,
      directory.toString,
      environment
    )

  override def readExternal(element: Element): Unit =
    super.readExternal(element)
    rootPath = Option(element.getAttributeValue("rootPath")).getOrElse("")
    executablePath =
      Option(element.getAttributeValue("executablePath")).getOrElse("")
    outputPath = Option(element.getAttributeValue("outputPath")).getOrElse("")
    outputKind = BendBuildConfiguration.parseKind(
      element.getAttributeValue("outputKind")
    )
    workingDirectory =
      Option(element.getAttributeValue("workingDirectory")).getOrElse("")
    environmentLines =
      Option(element.getAttributeValue("environmentLines")).getOrElse("")

  override def writeExternal(element: Element): Unit =
    super.writeExternal(element)
    val _ = element.setAttribute("rootPath", rootPath)
    val _ = element.setAttribute("executablePath", executablePath)
    val _ = element.setAttribute("outputPath", outputPath)
    val _ = element.setAttribute("outputKind", outputKind.toString)
    val _ = element.setAttribute("workingDirectory", workingDirectory)
    val _ = element.setAttribute("environmentLines", environmentLines)

  override def clone(): BendBuildConfiguration =
    val copied = super.clone().asInstanceOf[BendBuildConfiguration]
    copied.rootPath = rootPath
    copied.executablePath = executablePath
    copied.outputPath = outputPath
    copied.outputKind = outputKind
    copied.workingDirectory = workingDirectory
    copied.environmentLines = environmentLines
    copied

private object BendBuildConfiguration:
  def executable(value: String, base: Path): Either[String, Path] =
    BendExecutionConfigurationParsing
      .absolute(value, base)
      .flatMap(path =>
        Either.cond(
          Files.isRegularFile(path) && Files.isExecutable(path),
          path,
          "The Bend executable is missing or not executable. Configure it in Settings | Bend."
        )
      )

  def defaultOutput(root: Path, kind: BendBuildOutputKind): String =
    val name = root.getFileName.toString.stripSuffix(".bend")
    val outputName = kind match
      case BendBuildOutputKind.Native           => name
      case BendBuildOutputKind.CSource          => name + ".c"
      case BendBuildOutputKind.JavaScriptSource => name + ".js"
    root.resolveSibling(outputName).toString

  def parseKind(value: String): BendBuildOutputKind =
    Option(value)
      .flatMap(_.toLowerCase(java.util.Locale.ROOT) match
        case "csource"          => Some(BendBuildOutputKind.CSource)
        case "javascriptsource" => Some(BendBuildOutputKind.JavaScriptSource)
        case "native"           => Some(BendBuildOutputKind.Native)
        case _                  => None)
      .getOrElse(BendBuildOutputKind.Native)

  def validateKind(
      output: Path,
      kind: BendBuildOutputKind
  ): Either[String, Unit] =
    val name = output.getFileName.toString
    kind match
      case BendBuildOutputKind.CSource =>
        Either.cond(
          name.endsWith(".c"),
          (),
          "C source output must use the .c extension."
        )
      case BendBuildOutputKind.JavaScriptSource =>
        Either.cond(
          name.endsWith(".js"),
          (),
          "JavaScript output must use the .js extension."
        )
      case BendBuildOutputKind.Native =>
        Either.cond(
          !name.endsWith(".c") && !name.endsWith(".js"),
          (),
          "Native output cannot use the .c or .js extension."
        )

  def samePath(output: Path, input: Path): Boolean =
    canonical(output) == canonical(input)

  private def canonical(path: Path): Path =
    val absolute = path.toAbsolutePath.normalize()
    try absolute.toRealPath()
    catch
      case NonFatal(_) =>
        val parent = absolute.getParent
        if parent != null && Files.isDirectory(parent) then
          try parent.toRealPath().resolve(absolute.getFileName).normalize()
          catch case NonFatal(_) => absolute
        else absolute

  private def refreshOutput(
      project: Project,
      request: BendBuildRequest,
      console: ConsoleView
  ): Unit =
    val output = Path.of(request.output)
    val vfs = LocalFileSystem.getInstance()
    val generated = vfs.refreshAndFindFileByNioFile(output)
    request.outputKind match
      case BendBuildOutputKind.Native =>
        val companion = Path.of(request.output + ".gpu")
        if Files.isRegularFile(companion) then
          val _ = vfs.refreshAndFindFileByNioFile(companion)
          if console != null then
            console.print(
              s"GPU companion created at $companion; keep it beside the executable.\n",
              ConsoleViewContentType.NORMAL_OUTPUT
            )
      case _ if generated != null && console != null =>
        console.print(
          "Generated source: ",
          ConsoleViewContentType.NORMAL_OUTPUT
        )
        console.printHyperlink(
          output.getFileName.toString,
          new OpenFileHyperlinkInfo(project, generated, 0)
        )
        console.print("\n", ConsoleViewContentType.NORMAL_OUTPUT)
      case _ => ()

private final class BendBuildConfigurationEditor
    extends SettingsEditor[BendBuildConfiguration]:
  private val rootPath = new JBTextField()
  private val executable = new JBTextField()
  private val output = new JBTextField()
  private val kind = new JComboBox[String](
    Array("Native executable", "C source", "JavaScript source")
  )
  private val workingDirectory = new JBTextField()
  private val environment = new JTextArea(4, 40)
  private val panel = new JPanel(new GridBagLayout())
  private val fields = List(
    "Root source (.bend)" -> rootPath,
    "Bend executable (blank uses Settings | Bend)" -> executable,
    "Output path (blank chooses from root and output kind)" -> output,
    "Working directory (blank uses root directory)" -> workingDirectory
  )
  fields.zipWithIndex.foreach { case ((label, field), row) =>
    BendExecutionEditorRows.add(panel, label, field, row)
  }
  BendExecutionEditorRows.add(panel, "Output kind", kind, fields.size)
  BendExecutionEditorRows.addArea(
    panel,
    "Environment (one KEY=VALUE per line)",
    environment,
    fields.size + 1
  )
  BendExecutionEditorRows.addNote(
    panel,
    "All open IDE documents are saved before each build.",
    fields.size + 2
  )

  override protected def createEditor(): JComponent = panel

  override protected def resetEditorFrom(
      settings: BendBuildConfiguration
  ): Unit =
    rootPath.setText(settings.rootPath)
    executable.setText(settings.executablePath)
    output.setText(settings.outputPath)
    kind.setSelectedIndex(settings.outputKind.ordinal)
    workingDirectory.setText(settings.workingDirectory)
    environment.setText(settings.environmentLines)

  override protected def applyEditorTo(
      settings: BendBuildConfiguration
  ): Unit =
    settings.rootPath = rootPath.getText
    settings.executablePath = executable.getText
    settings.outputPath = output.getText
    settings.outputKind = kind.getSelectedIndex match
      case 1 => BendBuildOutputKind.CSource
      case 2 => BendBuildOutputKind.JavaScriptSource
      case _ => BendBuildOutputKind.Native
    settings.workingDirectory = workingDirectory.getText
    settings.environmentLines = environment.getText
    settings.buildRequest() match
      case Left(message) => throw new ConfigurationException(message)
      case Right(_)      => ()

private[execution] object BendExecutionEditorRows:
  def add(
      panel: JPanel,
      label: String,
      field: JComponent,
      row: Int
  ): Unit =
    val labelConstraints = new GridBagConstraints()
    labelConstraints.gridx = 0
    labelConstraints.gridy = row
    labelConstraints.anchor = GridBagConstraints.WEST
    labelConstraints.insets = new Insets(4, 4, 4, 8)
    panel.add(new JLabel(label), labelConstraints)
    val fieldConstraints = new GridBagConstraints()
    fieldConstraints.gridx = 1
    fieldConstraints.gridy = row
    fieldConstraints.weightx = 1.0
    fieldConstraints.fill = GridBagConstraints.HORIZONTAL
    fieldConstraints.insets = new Insets(4, 0, 4, 4)
    panel.add(field, fieldConstraints)

  def addArea(panel: JPanel, label: String, area: JTextArea, row: Int): Unit =
    val labelConstraints = new GridBagConstraints()
    labelConstraints.gridx = 0
    labelConstraints.gridy = row
    labelConstraints.anchor = GridBagConstraints.NORTHWEST
    labelConstraints.insets = new Insets(4, 4, 4, 8)
    panel.add(new JLabel(label), labelConstraints)
    val fieldConstraints = new GridBagConstraints()
    fieldConstraints.gridx = 1
    fieldConstraints.gridy = row
    fieldConstraints.weightx = 1.0
    fieldConstraints.fill = GridBagConstraints.HORIZONTAL
    fieldConstraints.insets = new Insets(4, 0, 4, 4)
    panel.add(new JScrollPane(area), fieldConstraints)

  def addNote(panel: JPanel, text: String, row: Int): Unit =
    val constraints = new GridBagConstraints()
    constraints.gridx = 0
    constraints.gridy = row
    constraints.gridwidth = 2
    constraints.anchor = GridBagConstraints.WEST
    constraints.insets = new Insets(8, 4, 4, 4)
    panel.add(new JLabel(text), constraints)
