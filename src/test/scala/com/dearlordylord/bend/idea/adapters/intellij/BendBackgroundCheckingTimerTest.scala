package com.dearlordylord.bend.idea.adapters.intellij

import com.dearlordylord.bend.idea.adapters.cli.RealBendCompilerFixture
import com.dearlordylord.bend.idea.analysis.api.{
  BendBackgroundCheckControl,
  BendCheckService
}
import com.dearlordylord.bend.idea.analysis.checking.BendCheckAction.ScheduleBackground
import com.dearlordylord.bend.idea.analysis.model.BendCheckResult
import com.dearlordylord.bend.idea.model.FileId
import com.dearlordylord.bend.idea.toolchain.api.{
  BendToolchainChoices,
  BendToolchainSettings
}
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.*
import java.nio.file.{Files, Path}

final class BendBackgroundCheckingTimerTest extends BasePlatformTestCase:
  private var original: BendToolchainChoices = null
  private var directory: Path = null
  private var scheduler: BendBackgroundChecking = null

  override def setUp(): Unit =
    super.setUp()
    val settings = ApplicationManager.getApplication.getService(
      classOf[BendToolchainSettings]
    )
    original = settings.choices
    directory = Files.createTempDirectory("bend-background-timer-")
    val compiler = RealBendCompilerFixture.inputs
    val executable = directory.resolve("bend")
    val _ = compiler.writeLauncher(executable)
    val selectedBase = directory.resolve("base.bend")
    Files.copy(compiler.base, selectedBase)
    settings.update(
      BendToolchainChoices(
        executable = executable.toString,
        baseSource = selectedBase.toString,
        diagnosticsEnabled = false
      )
    )

  override def tearDown(): Unit =
    try
      ApplicationManager.getApplication
        .getService(classOf[BendToolchainSettings])
        .update(original)
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

  private def id(file: VirtualFile): FileId =
    val canonical = Option(file.getCanonicalPath)
    new FileId(canonical.getOrElse(file.getPath), canonical.isDefined)

  private def control: BendBackgroundCheckControl =
    getProject
      .getService(classOf[BendCheckService])
      .asInstanceOf[BendBackgroundCheckControl]

  private def waitForScheduled(root: FileId): Long =
    val until = System.nanoTime() + 5_000_000_000L
    var token = scheduler.scheduledToken(root)
    while token.isEmpty && System.nanoTime() < until do
      Thread.sleep(10)
      token = scheduler.scheduledToken(root)
    token.getOrElse(
      throw new AssertionError("No timer was installed for " + root.value)
    )

  private def awaitResult(
      root: FileId,
      predicate: BendCheckResult => Boolean
  ): BendCheckResult =
    val service = getProject.getService(classOf[BendCheckService])
    val until = System.nanoTime() + 20_000_000_000L
    var found = service.result(root).filter(predicate)
    while found.isEmpty && System.nanoTime() < until do
      Thread.sleep(25)
      found = service.result(root).filter(predicate)
    found.getOrElse(
      throw new AssertionError(
        "No matching background result for " + root.value + "; last=" +
          service
            .result(root)
            .map(result => (result.fresh, result.details.take(300)))
      )
    )

  private def openEnabledRoot(name: String): (FileId, Long) =
    val file = myFixture
      .configureByText(
        name,
        "import Base\ndef main() -> U32:\n  unknown_latest_timer\n"
      )
      .getVirtualFile
    val root = id(file)
    scheduler = getProject.getService(classOf[BendBackgroundChecking])
    myFixture.openFileInEditor(file)
    val settings = ApplicationManager.getApplication.getService(
      classOf[BendToolchainSettings]
    )
    settings.update(settings.choices.copy(diagnosticsEnabled = true))
    scheduler.configurationChanged()
    assertTrue(
      "Opening and scanning the editor root must register its adapter handle",
      scheduler.hasRootHandle(root)
    )
    (root, waitForScheduled(root))

  def testDelayedOlderScheduleCannotReplaceLatestTimerAndLatestFires(): Unit =
    val (root, firstToken) = openEnabledRoot("timer-race.bend")
    val latest = control
      .requestBackground(root)
      .getOrElse(
        throw new AssertionError(
          "Second background request must create a ticket"
        )
      )
    assertTrue(latest.token > firstToken)
    assertTrue(control.backgroundScheduleCurrent(root, latest.token))

    scheduler.applyPolicyAction(ScheduleBackground(root, latest.token, 75L))
    scheduler.applyPolicyAction(ScheduleBackground(root, firstToken, 5_000L))

    assertEquals(Some(latest.token), scheduler.scheduledToken(root))
    assertFalse(control.backgroundScheduleCurrent(root, firstToken))
    assertTrue(control.backgroundScheduleCurrent(root, latest.token))

    val checked = awaitResult(
      root,
      result =>
        result.fresh &&
          result.details.contains("unknown_latest_timer")
    )
    assertTrue(checked.details.contains("unknown_latest_timer"))

    val settings = ApplicationManager.getApplication.getService(
      classOf[BendToolchainSettings]
    )
    val disabledToken = control
      .requestBackground(root)
      .getOrElse(
        throw new AssertionError("A follow-up request must create a ticket")
      )
      .token
    assertEquals(Some(disabledToken), scheduler.scheduledToken(root))
    settings.update(settings.choices.copy(diagnosticsEnabled = false))

    assertFalse(control.backgroundScheduleCurrent(root, disabledToken))
    assertFalse(scheduler.hasRootHandle(root))
    assertEquals(None, scheduler.scheduledToken(root))
    scheduler.applyPolicyAction(ScheduleBackground(root, disabledToken, 1L))
    assertFalse(scheduler.hasRootHandle(root))
    assertEquals(None, scheduler.scheduledToken(root))

    settings.update(settings.choices.copy(diagnosticsEnabled = true))
    val probe = new BendBackgroundChecking(getProject)
    try
      probe.configurationChanged()
      val reenabledToken = control
        .requestBackground(root)
        .getOrElse(
          throw new AssertionError("Re-enabled request must create a token")
        )
        .token
      assertTrue(reenabledToken > disabledToken)
      probe.applyPolicyAction(ScheduleBackground(root, reenabledToken, 10_000L))
      assertEquals(Some(reenabledToken), probe.scheduledToken(root))
      probe.dispose()

      assertFalse(control.backgroundScheduleCurrent(root, reenabledToken))
      assertFalse(probe.hasRootHandle(root))
      assertEquals(None, probe.scheduledToken(root))
      probe.applyPolicyAction(ScheduleBackground(root, reenabledToken, 1L))
      assertFalse(probe.hasRootHandle(root))
      assertEquals(None, probe.scheduledToken(root))
    finally probe.dispose()

    settings.update(settings.choices.copy(diagnosticsEnabled = false))
