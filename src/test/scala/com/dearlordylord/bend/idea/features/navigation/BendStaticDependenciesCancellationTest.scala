package com.dearlordylord.bend.idea.features.navigation

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.util.ProgressIndicatorBase
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.dearlordylord.bend.idea.symbols.api.BendImportedSymbolCatalog
import com.dearlordylord.bend.idea.workspace.api.{BendLoadingConfiguration}
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

  def testCancellationIsForwardedIntoSourceGraphLoading(): Unit =
    val root = myFixture.addFileToProject(
      "main.bend",
      "import ./dep.bend as Dep\ndef main():\n  Dep.value()\n"
    )
    val configuration = getProject
      .getService(classOf[BendLoadingConfiguration])
      .snapshot
    val snapshot = getProject
      .getService(classOf[BendImportedSymbolCatalog])
      .navigationSnapshot(
        root,
        configuration.baseSource,
        configuration.packageCache,
        () => true
      )
    assertTrue(
      "A canceled graph load should not visit the root",
      snapshot.graph.files.isEmpty
    )
