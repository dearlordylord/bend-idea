package com.dearlordylord.bend.idea.features.checking

import com.dearlordylord.bend.idea.adapters.cli.RealBendCompilerFixture
import com.dearlordylord.bend.idea.analysis.api.BendCheckService
import com.dearlordylord.bend.idea.analysis.model.{
  BendCheckOutcome,
  BendLocation,
  BendTextRange
}
import com.dearlordylord.bend.idea.model.FileId
import com.dearlordylord.bend.idea.test.VfsTestRoots
import com.dearlordylord.bend.idea.toolchain.api.{
  BendToolchainChoices,
  BendToolchainSettings
}
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files
import org.junit.Assert.*
import scala.jdk.CollectionConverters.*

/** Real compiler spans are rendered unchanged, including when trivia follows a
  * law.
  */
final class BendLawDiagnosticRangeTest extends BasePlatformTestCase:
  def testLawErrorHighlightsOnlyCompilerTokenBeforeTrailingTrivia(): Unit =
    VfsTestRoots.allowSystemTemporaryDirectory(getTestRootDisposable)
    val settings = ApplicationManager.getApplication.getService(
      classOf[BendToolchainSettings]
    )
    val original = settings.choices
    val directory = Files.createTempDirectory("bend-law-range-editor-")
    try
      val compiler = RealBendCompilerFixture.inputs
      val executable = directory.resolve("bend")
      val _ =
        compiler.writeStructuredLauncher(executable, structuredChecks = true)
      val base = directory.resolve("base.bend")
      val _ = Files.copy(compiler.base, base)
      settings.update(
        BendToolchainChoices(
          executable = executable.toString,
          baseSource = base.toString,
          diagnosticsEnabled = false
        )
      )
      val text = "law unfinished : 1\n# trailing comment\n\n"
      myFixture.configureByText("law-range.bend", text)
      val virtual = myFixture.getFile.getVirtualFile
      val id = new FileId(
        Option(virtual.getCanonicalPath).getOrElse(virtual.getPath),
        virtual.getCanonicalPath != null
      )
      myFixture.performEditorAction("Bend.CheckCurrentFile")
      val service = getProject.getService(classOf[BendCheckService])
      val deadline = System.nanoTime() + 15_000_000_000L
      while service.result(id).isEmpty && System.nanoTime() < deadline do
        Thread.sleep(50)
      val result = service
        .result(id)
        .getOrElse(throw new AssertionError("Law check did not publish"))
      assertEquals(result.details, BendCheckOutcome.Failed, result.outcome)
      val start = text.indexOf('1')
      assertEquals(
        BendLocation.SourceRange(id, BendTextRange(start, start + 1)),
        result.diagnostics.head.location
      )
      val diagnostics = myFixture
        .doHighlighting()
        .asScala
        .filter(info =>
          info.getSeverity == HighlightSeverity.ERROR &&
            Option(info.getDescription)
              .contains(result.diagnostics.head.message)
        )
      assertFalse(
        "Compiler diagnostic must appear in native editor",
        diagnostics.isEmpty
      )
      assertTrue(
        diagnostics.forall(info =>
          info.getStartOffset == start && info.getEndOffset == start + 1
        )
      )
    finally
      settings.update(original)
      val paths = Files.walk(directory)
      try
        paths
          .sorted(java.util.Comparator.reverseOrder())
          .forEach(path => {
            val _ = Files.deleteIfExists(path)
          })
      finally paths.close()
