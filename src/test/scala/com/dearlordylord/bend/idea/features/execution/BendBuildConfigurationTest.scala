package com.dearlordylord.bend.idea.features.execution

import com.dearlordylord.bend.idea.adapters.cli.RealBendCompilerFixture
import com.dearlordylord.bend.idea.features.execution.api.{
  BendBuildOutputKind,
  BendBuildProcessFactory,
  BendNativeProcessFactory,
  BendNativeRunRequest
}
import com.dearlordylord.bend.idea.syntax.psi.BendForeignPaths
import com.dearlordylord.bend.idea.toolchain.api.{
  BendToolchainChoices,
  BendToolchainSettings
}
import com.intellij.execution.process.{
  ProcessAdapter,
  ProcessEvent,
  ProcessHandler
}
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.{Files, Path}
import java.awt.{Component, Container}
import java.util.concurrent.{CountDownLatch, TimeUnit}
import org.junit.Assert.*

final class BendBuildConfigurationTest extends BasePlatformTestCase:
  private var directory: Path = null
  private var originalChoices: BendToolchainChoices = null
  private var compiler: RealBendCompilerFixture.Inputs = null
  private var launcher: Path = null
  private var environment: Map[String, String] = Map.empty

  override def setUp(): Unit =
    super.setUp()
    directory = Files.createTempDirectory("bend-build-configuration-")
    val settings = ApplicationManager.getApplication.getService(
      classOf[BendToolchainSettings]
    )
    originalChoices = settings.choices
    compiler = RealBendCompilerFixture.inputs
    launcher = compiler.writeLauncher(directory.resolve("bend launcher"))
    val selectedBase = directory.resolve("base.bend")
    Files.copy(compiler.base, selectedBase)
    settings.update(
      BendToolchainChoices(
        executable = launcher.toString,
        baseSource = selectedBase.toString
      )
    )
    environment = Map(
      "BEND_TEST_COMPILER_DIR" -> compiler.compilerDirectory.toString,
      "BEND_TEST_BUN" -> compiler.bunExecutable.toString,
      "BEND_NO_TELEMETRY" -> "1",
      "NO_COLOR" -> "1"
    )

  override def tearDown(): Unit =
    try
      ApplicationManager.getApplication
        .getService(classOf[BendToolchainSettings])
        .update(originalChoices)
      if directory != null then
        val paths = Files.walk(directory)
        try
          paths
            .sorted(java.util.Comparator.reverseOrder())
            .forEach(path => {
              val _ = Files.deleteIfExists(path)
            })
        finally paths.close()
    finally super.tearDown()

  def testBuildRejectsDirectoriesAndCanonicalGraphInputs(): Unit =
    val rootPath = directory.resolve("main.bend")
    val dependency = directory.resolve("dep.bend")
    Files.writeString(
      rootPath,
      "import Base\nimport dep.bend as Dep\ndef main() -> U32:\n  1\n"
    )
    Files.writeString(dependency, "def value() -> U32:\n  1\n")
    refresh(rootPath)
    refresh(dependency)
    val configuration = buildConfiguration()
    configuration.rootPath = rootPath.toString
    configuration.executablePath = launcher.toString
    configuration.outputKind = BendBuildOutputKind.Native
    configuration.outputPath = dependency.toString
    assertTrue(
      configuration.buildRequest().left.toOption.get.contains("overwrite")
    )

    configuration.outputPath = directory.toString
    assertTrue(
      configuration.buildRequest().left.toOption.get.contains("directory")
    )

    configuration.outputPath = directory.resolve("main.js").toString
    configuration.outputKind = BendBuildOutputKind.CSource
    assertTrue(
      configuration.buildRequest().left.toOption.get.contains(".c extension")
    )

    val alias = directory.resolve("dependency alias.bend")
    Files.createSymbolicLink(alias, dependency)
    configuration.outputPath = alias.toString
    configuration.outputKind = BendBuildOutputKind.Native
    assertTrue(
      "A symlink to a loaded input is also protected",
      configuration.buildRequest().left.toOption.get.contains("overwrite")
    )

    val foreignInput = directory.resolve("foreign.c")
    Files.writeString(foreignInput, "int foreign(void);\n")
    val foreignRoot = directory.resolve("foreign-root.bend")
    Files.writeString(
      foreignRoot,
      "def foreign() -> U32:\n  import \"foreign.c\"\ndef main() -> U32:\n  1\n"
    )
    refresh(foreignRoot)
    assertEquals(
      List("foreign.c"),
      BendForeignPaths
        .inSource(getProject, Files.readString(foreignRoot))
        .map(_.spelling)
    )
    configuration.rootPath = foreignRoot.toString
    configuration.outputPath = foreignInput.toString
    configuration.outputKind = BendBuildOutputKind.CSource
    val foreignBuild = configuration.buildRequest()
    assertTrue(
      s"Output cannot replace an input named in a foreign definition: $foreignBuild",
      foreignBuild.left.toOption.exists(_.contains("foreign source"))
    )

  def testPinnedCompilerEmitsCAndJavaScriptAndReportsErrors(): Unit =
    val rootPath = writeRoot("emission.bend")
    val configuration = configuredBuild(rootPath)
    val cOutput = directory.resolve("generated.c")
    configuration.outputKind = BendBuildOutputKind.CSource
    configuration.outputPath = cOutput.toString
    val cRequest = configuration.buildRequest().toOption.get
    val cResult = run(
      getProject.getService(classOf[BendBuildProcessFactory]).start(cRequest)
    )
    assertEquals(Int.box(0), cResult._1)
    assertTrue(Files.isRegularFile(cOutput))
    assertTrue(Files.readString(cOutput).contains("#include"))

    val jsOutput = directory.resolve("generated.js")
    configuration.outputKind = BendBuildOutputKind.JavaScriptSource
    configuration.outputPath = jsOutput.toString
    val jsRequest = configuration.buildRequest().toOption.get
    val jsResult = run(
      getProject.getService(classOf[BendBuildProcessFactory]).start(jsRequest)
    )
    assertEquals(Int.box(0), jsResult._1)
    assertTrue(Files.isRegularFile(jsOutput))
    assertTrue(Files.readString(jsOutput).contains("function"))

    val invalid = directory.resolve("invalid.bend")
    Files.writeString(invalid, "def main( -> U32:\n  1\n")
    refresh(invalid)
    configuration.rootPath = invalid.toString
    configuration.outputPath = directory.resolve("invalid.c").toString
    configuration.outputKind = BendBuildOutputKind.CSource
    val failed = run(
      getProject
        .getService(classOf[BendBuildProcessFactory])
        .start(configuration.buildRequest().toOption.get)
    )
    assertNotEquals(Int.box(0), failed._1)
    assertTrue(
      "Compiler diagnostics reach the process console",
      failed._2.nonEmpty
    )

  def testMissingNativeBackendIsReportedByPinnedCompiler(): Unit =
    val rootPath = writeRoot("missing-backend.bend")
    val configuration = configuredBuild(rootPath)
    configuration.outputPath = directory.resolve("no-clang").toString
    configuration.outputKind = BendBuildOutputKind.Native
    configuration.environmentLines = "PATH=/path/without/compiler"
    val request = configuration.buildRequest().toOption.get
    val result = run(
      getProject.getService(classOf[BendBuildProcessFactory]).start(request)
    )
    assertNotEquals(Int.box(0), result._1)
    assertTrue(result._2.contains("needs clang"))

  def testPinnedNativeBuildAndRunSmoke(): Unit =
    val rootPath = writeRoot("native-smoke.bend")
    val output = directory.resolve("native-smoke")
    val configuration = configuredBuild(rootPath)
    configuration.outputKind = BendBuildOutputKind.Native
    configuration.outputPath = output.toString
    val built = run(
      getProject
        .getService(classOf[BendBuildProcessFactory])
        .start(configuration.buildRequest().toOption.get)
    )
    assertEquals(Int.box(0), built._1)
    assertTrue(Files.isExecutable(output))

    val request = BendNativeRunRequest(
      output.toString,
      directory.toString,
      environment,
      Some(2),
      Some("off"),
      Nil
    )
    val launched = run(
      getProject.getService(classOf[BendNativeProcessFactory]).start(request)
    )
    assertEquals(Int.box(0), launched._1)
    assertTrue(
      s"Native build should execute its generated program: ${launched._2}",
      launched._2.endsWith("1\n")
    )

  def testNativeRunOptionsAreValidatedAndOrderedBeforeArguments(): Unit =
    val executable = directory.resolve("native fake")
    val capture = directory.resolve("native args.txt")
    Files.writeString(
      executable,
      "#!/bin/sh\nprintf '<%s>\\n' \"$@\" > " + shellQuote(capture.toString)
    )
    assertTrue(executable.toFile.setExecutable(true))
    val configuration = nativeConfiguration()
    configuration.executablePath = executable.toString
    configuration.threads = "4"
    configuration.gpu = "4GB"
    configuration.programArguments = "--user \"two words\""
    configuration.environmentLines = "TEST_VALUE=kept"
    val request = configuration.nativeRequest().toOption.get
    val result = run(
      getProject.getService(classOf[BendNativeProcessFactory]).start(request)
    )
    assertEquals(Int.box(0), result._1)
    assertEquals(
      "<--threads>\n<4>\n<--gpu>\n<4GB>\n<-->\n<--user>\n<two words>\n",
      Files.readString(capture)
    )
    configuration.threads = "129"
    assertTrue(configuration.nativeRequest().isLeft)
    configuration.threads = "4"
    configuration.gpu = "4TB"
    assertTrue(configuration.nativeRequest().isLeft)

  def testBuildRootSourceMustBeSelectedWithTheFileChooser(): Unit =
    val editor = buildConfiguration().getConfigurationEditor
    val fields = descendants(editor.getComponent).collect {
      case field: TextFieldWithBrowseButton => field
    }
    assertEquals(1, fields.size)
    assertFalse(
      "Root source is selected, not typed manually",
      fields.head.isEditable
    )

  private def writeRoot(name: String): Path =
    val path = directory.resolve(name)
    Files.writeString(path, "import Base\ndef main() -> U32:\n  1\n")
    refresh(path)
    path

  private def refresh(path: Path): Unit =
    val _ = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)

  private def buildConfiguration(): BendBuildConfiguration =
    val configurationType = new BendRunConfigurationType
    val factory = configurationType.getConfigurationFactories.apply(1)
    new BendBuildConfiguration(getProject, factory, "test build")

  private def descendants(component: Component): List[Component] =
    component :: (component match
      case container: Container =>
        container.getComponents.toList.flatMap(descendants)
      case _ => Nil)

  private def nativeConfiguration(): BendNativeRunConfiguration =
    val configurationType = new BendRunConfigurationType
    val factory = configurationType.getConfigurationFactories.apply(2)
    new BendNativeRunConfiguration(getProject, factory, "test native")

  private def configuredBuild(root: Path): BendBuildConfiguration =
    val configuration = buildConfiguration()
    configuration.rootPath = root.toString
    configuration.executablePath = launcher.toString
    configuration.environmentLines = environment.toList
      .map { case (key, value) => s"$key=$value" }
      .mkString("\n")
    configuration

  private def run(handler: ProcessHandler): (Integer, String) =
    val output = new StringBuilder
    val finished = new CountDownLatch(1)
    handler.addProcessListener(new ProcessAdapter:
      override def onTextAvailable(
          event: ProcessEvent,
          outputType: Key[?]
      ): Unit = output.synchronized {
        val _ = output.append(event.getText)
      }
      override def processTerminated(event: ProcessEvent): Unit =
        finished.countDown())
    handler.startNotify()
    assertTrue(
      "Bend subprocess should terminate",
      finished.await(60, TimeUnit.SECONDS)
    )
    (Int.box(handler.getExitCode), output.synchronized { output.toString })

  private def shellQuote(value: String): String =
    "'" + value.replace("'", "'\\''") + "'"
