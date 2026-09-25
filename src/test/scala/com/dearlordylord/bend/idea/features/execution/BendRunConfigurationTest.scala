package com.dearlordylord.bend.idea.features.execution

import com.dearlordylord.bend.idea.adapters.cli.RealBendCompilerFixture
import com.dearlordylord.bend.idea.features.execution.api.{
  BendExecutionProcessFactory,
  BendExecutionRequest
}
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.process.{ProcessAdapter, ProcessEvent}
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.awt.{Component, Container}
import java.util.concurrent.{CountDownLatch, TimeUnit}
import scala.annotation.nowarn
import org.junit.Assert.*

final class BendRunConfigurationTest extends BasePlatformTestCase:
  private var processDir: Path = null

  override def setUp(): Unit =
    super.setUp()
    processDir = Files.createTempDirectory("bend-run-configuration-")

  override def tearDown(): Unit =
    try
      if processDir != null then
        val paths = Files.walk(processDir)
        try
          paths
            .sorted(java.util.Comparator.reverseOrder())
            .forEach(path => {
              val _ = Files.deleteIfExists(path)
            })
        finally paths.close()
    finally super.tearDown()

  def testRunPassesPathsArgumentsAndEnvironmentSavesDocumentsAndStops(): Unit =
    val rootPath = processDir.resolve("folder with spaces/main source.bend")
    Files.createDirectories(rootPath.getParent)
    Files.writeString(rootPath, "import Base\ndef main() -> U32:\n  0\n")
    val root = Option(
      LocalFileSystem.getInstance().refreshAndFindFileByNioFile(rootPath)
    ).getOrElse(throw new AssertionError("Physical Bend source was not found"))
    val working = Files.createDirectory(processDir.resolve("working directory"))
    val capture = processDir.resolve("captured launch.txt")
    val marker = processDir.resolve("started")
    val executable = processDir.resolve("bend fixture")
    Files.writeString(
      executable,
      "#!/bin/sh\n" +
        "if [ \"${3:-}\" = \"--sleep\" ]; then\n" +
        "  printf x > " + shellQuote(marker.toString) + "\n" +
        "  while :; do sleep 1; done\n" +
        "fi\n" +
        "printf 'PWD=<%s>\\n' \"$PWD\" > " + shellQuote(
          capture.toString
        ) + "\n" +
        "for arg in \"$@\"; do printf 'ARG=<%s>\\n' \"$arg\" >> " + shellQuote(
          capture.toString
        ) + "; done\n" +
        "printf 'ENV=<%s>\\n' \"$BEND_TEST_VALUE\" >> " + shellQuote(
          capture.toString
        ) + "\n" +
        "printf 'SOURCE\\n' >> " + shellQuote(capture.toString) + "\n" +
        "cat \"$1\" >> " + shellQuote(capture.toString) + "\n" +
        "exit 7\n"
    )
    assertTrue(executable.toFile.setExecutable(true))

    myFixture.openFileInEditor(root)
    WriteCommandAction.runWriteCommandAction(
      getProject,
      new Runnable:
        override def run(): Unit =
          myFixture.getEditor.getDocument.setText(
            "import Base\ndef main() -> U32:\n  1\n"
          )
    )
    PsiDocumentManager.getInstance(getProject).commitAllDocuments()
    assertTrue(
      "The editor has an unsaved source revision before launch",
      FileDocumentManager
        .getInstance()
        .isFileModified(root)
    )
    assertFalse(
      "Unsaved editor text has not reached disk yet",
      Files.readString(rootPath).contains("  1\n")
    )

    val configuration = runConfiguration()
    configuration.rootPath = root.getPath
    configuration.executablePath = executable.toString
    configuration.workingDirectory = working.toString
    configuration.programArguments = "--flag \"value with spaces\""
    configuration.environmentLines = "BEND_TEST_VALUE=hello world"
    configuration.checkConfiguration()
    val request = configuration.executionRequest().toOption.get
    assertEquals(List("--flag", "value with spaces"), request.arguments)

    val handler = BendExecutionLaunch.start(getProject, request)
    val finished = observeTermination(handler)
    handler.startNotify()
    assertTrue("Bend run should exit", finished.await(10, TimeUnit.SECONDS))
    assertEquals(Int.box(7), handler.getExitCode)
    val observed = Files.readString(capture, StandardCharsets.UTF_8)
    assertTrue(observed.contains(s"PWD=<${working.toString}>"))
    assertTrue(observed.contains(s"ARG=<${root.getPath}>"))
    assertTrue(observed.contains("ARG=<-->"))
    assertTrue(observed.contains("ARG=<--flag>"))
    assertTrue(observed.contains("ARG=<value with spaces>"))
    assertTrue(observed.contains("ENV=<hello world>"))
    assertTrue(
      "Run saves open editor documents before process start",
      observed.contains("  1\n")
    )

    val stopRequest = request.copy(arguments = List("--sleep"))
    val stopping = getProject
      .getService(classOf[BendExecutionProcessFactory])
      .start(stopRequest)
    val stopped = observeTermination(stopping)
    stopping.startNotify()
    val startDeadline = System.nanoTime() + 5_000_000_000L
    while !Files.exists(marker) && System.nanoTime() < startDeadline do
      Thread.sleep(20)
    assertTrue("The child process should start", Files.exists(marker))
    stopping.destroyProcess()
    assertTrue(
      "Stop should terminate the process tree",
      stopped.await(5, TimeUnit.SECONDS)
    )
    assertTrue(stopping.isProcessTerminated)

  def testPinnedCompilerRunsPureMainOnlyThroughExplicitExecution(): Unit =
    val pinned = RealBendCompilerFixture.inputs
    val executable = processDir.resolve("bend pinned")
    val _ = pinned.writeLauncher(executable)
    val root = processDir.resolve("pure-main.bend")
    Files.writeString(root, "import Base\ndef main() -> U32:\n  1\n")
    val request = BendExecutionRequest(
      executable.toString,
      root.toString,
      processDir.toString,
      Map(
        "BEND_TEST_COMPILER_DIR" -> pinned.compilerDirectory.toString,
        "BEND_TEST_BUN" -> pinned.bunExecutable.toString,
        "BEND_NO_TELEMETRY" -> "1",
        "NO_COLOR" -> "1"
      ),
      Nil
    )
    val output = new StringBuilder
    val handler = getProject
      .getService(classOf[BendExecutionProcessFactory])
      .start(request)
    val finished = new CountDownLatch(1)
    handler.addProcessListener(new ProcessAdapter:
      override def onTextAvailable(
          event: ProcessEvent,
          outputType: com.intellij.openapi.util.Key[?]
      ): Unit = output.synchronized {
        val _ = output.append(event.getText)
      }
      override def processTerminated(event: ProcessEvent): Unit =
        finished.countDown())
    handler.startNotify()
    assertTrue(
      "Pinned Bend run should exit",
      finished.await(30, TimeUnit.SECONDS)
    )
    assertEquals(Int.box(0), handler.getExitCode)
    assertTrue(
      s"Expected pure main output; observed: $output",
      output.synchronized { output.toString }.endsWith("1\n")
    )

  def testPinnedCompilerKeepsIoMainExecutionBehavior(): Unit =
    val pinned = RealBendCompilerFixture.inputs
    val executable = processDir.resolve("bend pinned io")
    val _ = pinned.writeLauncher(executable)
    val root = processDir.resolve("io-main.bend")
    Files.writeString(
      root,
      "import Base\ndef main() -> IO(Unit):\n  do IO<Unit>:\n    IO.print(\"IO_MAIN_EXECUTED\")\n"
    )
    val request = BendExecutionRequest(
      executable.toString,
      root.toString,
      processDir.toString,
      Map(
        "BEND_TEST_COMPILER_DIR" -> pinned.compilerDirectory.toString,
        "BEND_TEST_BUN" -> pinned.bunExecutable.toString,
        "BEND_NO_TELEMETRY" -> "1",
        "NO_COLOR" -> "1"
      ),
      Nil
    )
    val output = new StringBuilder
    val handler = getProject
      .getService(classOf[BendExecutionProcessFactory])
      .start(request)
    val finished = new CountDownLatch(1)
    handler.addProcessListener(new ProcessAdapter:
      override def onTextAvailable(
          event: ProcessEvent,
          outputType: com.intellij.openapi.util.Key[?]
      ): Unit = output.synchronized {
        val _ = output.append(event.getText)
      }
      override def processTerminated(event: ProcessEvent): Unit =
        finished.countDown())
    handler.startNotify()
    assertTrue(
      "Pinned Bend IO run should exit",
      finished.await(30, TimeUnit.SECONDS)
    )
    assertEquals(Int.box(0), handler.getExitCode)
    assertTrue(
      s"Expected IO main output; observed: $output",
      output.synchronized { output.toString }.endsWith("IO_MAIN_EXECUTED\n")
    )

  def testRunConsoleLinksOnlyAbsoluteExistingBendLocations(): Unit =
    val path = processDir.resolve("linked.bend")
    Files.writeString(path, "def main(): 0\n")
    val _ = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)
    val filter = new BendExecutionSourceFilter(getProject)
    assertNull(filter.applyFilter("linked.bend:1:1: diagnostic", 28))
    val absolute = s"$path:1:5: diagnostic"
    val prefixLength = "earlier console output\n".length
    val result = filter.applyFilter(absolute, prefixLength + absolute.length)
    assertNotNull(result)
    assertEquals(prefixLength, highlightStart(result))
    assertEquals(
      prefixLength + absolute.length,
      highlightEnd(result)
    )

  def testRunRootSourceMustBeSelectedWithTheFileChooser(): Unit =
    val configurationType = new BendRunConfigurationType
    val factories = configurationType.getConfigurationFactories
    val runEditor = runConfiguration().getConfigurationEditor
    val buildEditor = new BendBuildConfiguration(
      getProject,
      factories(1),
      "fixture"
    ).getConfigurationEditor
    List(runEditor, buildEditor).foreach { editor =>
      val fields = descendants(editor.getComponent).collect {
        case field: TextFieldWithBrowseButton => field
      }
      assertEquals(1, fields.size)
      assertFalse(
        "Root source is selected, not typed manually",
        fields.head.isEditable
      )
    }

  def testRunBuildAndNativeFactoriesHaveDistinctNames(): Unit =
    val configurationType = new BendRunConfigurationType
    val factoryNames = configurationType.getConfigurationFactories.toList.map(
      _.getName
    )
    assertEquals(List("Bend Run", "Bend Build", "Bend Native"), factoryNames)

  @nowarn("cat=deprecation")
  private def highlightStart(
      result: com.intellij.execution.filters.Filter.Result
  ): Int = result.getHighlightStartOffset

  @nowarn("cat=deprecation")
  private def highlightEnd(
      result: com.intellij.execution.filters.Filter.Result
  ): Int = result.getHighlightEndOffset

  private def runConfiguration(): BendRunConfiguration =
    val configType = new BendRunConfigurationType
    val factory: ConfigurationFactory =
      configType.getConfigurationFactories.head
    new BendRunConfiguration(getProject, factory, "fixture")

  private def descendants(component: Component): List[Component] =
    component :: (component match
      case container: Container =>
        container.getComponents.toList.flatMap(descendants)
      case _ => Nil)

  private def observeTermination(
      handler: com.intellij.execution.process.ProcessHandler
  ): CountDownLatch =
    val terminated = new CountDownLatch(1)
    handler.addProcessListener(new ProcessAdapter:
      override def processTerminated(event: ProcessEvent): Unit =
        terminated.countDown())
    terminated

  private def shellQuote(value: String): String =
    "'" + value.replace("'", "'\\''") + "'"
