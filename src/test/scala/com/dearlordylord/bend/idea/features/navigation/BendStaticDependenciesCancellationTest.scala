package com.dearlordylord.bend.idea.features.navigation

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.util.ProgressIndicatorBase
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.*

final class BendStaticDependenciesCancellationTest extends BasePlatformTestCase:
  def testDependencyScanHonorsBackgroundCancellation(): Unit =
    val root = myFixture.addFileToProject(
      "main.bend",
      "def main():\n  pending_call()\n"
    )
    val indicator = new ProgressIndicatorBase
    indicator.cancel()
    val operation = new Runnable:
      override def run(): Unit =
        val _ = ReadAction.compute(() => BendStaticDependencies.inspect(root))

    val error = assertThrows(
      classOf[ProcessCanceledException],
      () => ProgressManager.getInstance().runProcess(operation, indicator)
    )
    assertNotNull(error)
