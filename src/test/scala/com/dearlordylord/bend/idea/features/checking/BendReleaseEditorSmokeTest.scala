package com.dearlordylord.bend.idea.features.checking

import com.dearlordylord.bend.idea.analysis.api.BendCheckService
import com.dearlordylord.bend.idea.analysis.model.{
  BendCheckOutcome,
  BendCheckResult,
  BendCompleteness,
  BendLocation,
  BendReliance
}
import com.dearlordylord.bend.idea.model.FileId
import com.dearlordylord.bend.idea.toolchain.api.{
  BendToolchainChoices,
  BendToolchainSettings
}
import com.intellij.openapi.application.ApplicationManager
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import scala.jdk.CollectionConverters.*

/** Run by the release-mode test task with the executable and Base from one
  * release archive. The ordinary pinned suite leaves these optional inputs
  * unset.
  */
final class BendReleaseEditorSmokeTest extends BasePlatformTestCase:
  def testPublishedCompilerThroughCheckCurrentFileAction(): Unit =
    val executable = Option(System.getenv("BEND_TEST_CURRENT_COMPILER"))
    val base = Option(System.getenv("BEND_TEST_CURRENT_BASE"))
    if executable.isEmpty || base.isEmpty then
      assumeTrue("Published Bend release inputs are not configured", false)
    else
      val settings = ApplicationManager.getApplication.getService(
        classOf[BendToolchainSettings]
      )
      val original = settings.choices
      try
        settings.update(
          BendToolchainChoices(
            executable = executable.get,
            baseSource = base.get,
            diagnosticsEnabled = false
          )
        )
        val complete = check(
          "release-complete.bend",
          "import Base\ndef main() -> U32:\n  1\n"
        )
        assertEquals(
          complete.details,
          BendCheckOutcome.Success,
          complete.outcome
        )
        assertEquals(BendCompleteness.Complete, complete.completeness)
        assertEquals(BendReliance.None, complete.reliance)

        val todo = check(
          "release-todo.bend",
          "import Base\ndef main() -> U32:\n  ?TODO\n"
        )
        assertEquals(todo.details, BendCheckOutcome.Failed, todo.outcome)
        assertEquals(BendCompleteness.Incomplete, todo.completeness)

        val errorSource = "import Base\ndef main() -> U32:\n  missing_name\n"
        val error = check("release-error.bend", errorSource)
        assertEquals(error.details, BendCheckOutcome.Failed, error.outcome)
        assertEquals(
          BendLocation.SourceLine(error.key.root, 2),
          error.diagnostics.head.location
        )
        val errorOffset = errorSource.indexOf("missing_name")
        assertTrue(
          "The published compiler error should be visible in the editor",
          myFixture.doHighlighting().asScala.exists { info =>
            info.getSeverity == HighlightSeverity.ERROR &&
            info.getStartOffset <= errorOffset && info.getEndOffset > errorOffset
          }
        )

        val unsafe = check(
          "release-unsafe.bend",
          "import Base\n@unsafe def main() -> U32:\n  1\n"
        )
        assertEquals(unsafe.details, BendCheckOutcome.Success, unsafe.outcome)
        assertEquals(BendCompleteness.Complete, unsafe.completeness)
        assertEquals(BendReliance.UnsafeOrForeign, unsafe.reliance)
      finally settings.update(original)

  private def check(name: String, source: String): BendCheckResult =
    myFixture.configureByText(name, source)
    val file = myFixture.getFile.getVirtualFile
    val id = new FileId(
      Option(file.getCanonicalPath).getOrElse(file.getPath),
      file.getCanonicalPath != null
    )
    myFixture.performEditorAction("Bend.CheckCurrentFile")
    val service = getProject.getService(classOf[BendCheckService])
    val deadline = System.nanoTime() + 20_000_000_000L
    while service.result(id).isEmpty && System.nanoTime() < deadline do
      Thread.sleep(50)
    service
      .result(id)
      .getOrElse(
        throw new AssertionError(
          s"Bend release check did not publish for $name"
        )
      )
