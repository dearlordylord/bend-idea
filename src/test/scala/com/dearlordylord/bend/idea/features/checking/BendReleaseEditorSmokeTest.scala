package com.dearlordylord.bend.idea.features.checking

import com.dearlordylord.bend.idea.analysis.api.BendCheckService
import com.dearlordylord.bend.idea.symbols.api.BendSourceSymbols
import com.intellij.codeInsight.navigation.actions.GotoDeclarationAction
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.{PsiDocumentManager, PsiErrorElement}
import com.intellij.psi.util.PsiTreeUtil
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
  private def withReleaseCompiler(run: () => Unit): Unit =
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
        run()
      finally settings.update(original)

  def testPublishedCompilerThroughCheckCurrentFileAction(): Unit =
    withReleaseCompiler(compilerVerdicts)

  private def compilerVerdicts: () => Unit = () => {
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

    val named =
      check("release-named.bend", "import Base\ndef main() -> U32:\n  ?need\n")
    assertEquals(named.details, BendCheckOutcome.Failed, named.outcome)
    assertEquals(BendCompleteness.Incomplete, named.completeness)

    val foreign = check(
      "release-foreign.bend",
      "import Base\nlaw foreign:\n  IO(Unit)\ndef foreign():\n  import \"./never-executed.js\"\ndef main() -> IO(Unit):\n  foreign()\n"
    )
    assertEquals(foreign.details, BendCheckOutcome.Success, foreign.outcome)
    assertEquals(BendReliance.UnsafeOrForeign, foreign.reliance)

    val effect = check(
      "release-effect.bend",
      "import Base\ndef main() -> IO(Unit):\n  do IO<Unit>:\n    IO.print(\"SIDE_EFFECT_MARKER\")\n"
    )
    assertEquals(effect.details, BendCheckOutcome.Success, effect.outcome)
    assertFalse(effect.details.contains("SIDE_EFFECT_MARKER"))

    val errorSource = "import Base\ndef main() -> U32:\n  missing_name\n"
    val error = check("release-error.bend", errorSource)
    assertEquals(error.details, BendCheckOutcome.Failed, error.outcome)
    assertDiagnosticAt(error, error.key.root, errorSource, 2, "missing_name")
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
  }

  def testSourceParsingNavigationAndFormattingWithCompilerComparison(): Unit =
    withReleaseCompiler(sourceAndFormatting)

  private def sourceAndFormatting: () => Unit = () => {
    val source =
      "import Base\ntype Choice is Data:\n  Left{}\n  Right{value: U32}\ndef choose(x: Choice) -> U32:\n  match x:\n    case Left{}:\n      0\n    case Right{value}:\n      value\ndef main() -> U32:\n  choose(Right{1})\n"
    val checked = check("release-syntax.bend", source)
    assertEquals(checked.details, BendCheckOutcome.Success, checked.outcome)
    assertTrue(
      "Representative source must parse without PSI errors",
      PsiTreeUtil
        .findChildrenOfType(myFixture.getFile, classOf[PsiErrorElement])
        .isEmpty
    )
    assertEquals(
      List("Choice", "Left", "Right", "choose", "main"),
      BendSourceSymbols.declarations(myFixture.getFile).map(_.name)
    )
    myFixture.getEditor.getCaretModel.moveToOffset(
      source.lastIndexOf("choose(") + 2
    )
    val target = GotoDeclarationAction.findTargetElement(
      getProject,
      myFixture.getEditor,
      myFixture.getCaretOffset
    )
    assertNotNull(
      "Source navigation must remain available with the release compiler configured",
      target
    )
    assertTrue(target.getText.contains("choose"))

    val original =
      "import Base\n# preserve this comment\ndef identity(x: U32,y: U32) -> U32:\n    x\n"
    val before = check("release-format.bend", original)
    assertEquals(before.details, BendCheckOutcome.Success, before.outcome)
    myFixture.performEditorAction(IdeActions.ACTION_EDITOR_REFORMAT)
    val formatted = myFixture.getEditor.getDocument.getText
    assertEquals(
      "import Base\n# preserve this comment\ndef identity(x: U32, y: U32) -> U32:\n  x\n",
      formatted
    )
    myFixture.performEditorAction(IdeActions.ACTION_EDITOR_REFORMAT)
    assertEquals(formatted, myFixture.getEditor.getDocument.getText)
    val after = check("release-formatted.bend", formatted)
    assertEquals(after.details, before.outcome, after.outcome)
    assertEquals(before.completeness, after.completeness)
    assertEquals(before.reliance, after.reliance)
  }

  def testUnsavedDependencyFeedsNavigationAndCompilerDiagnostics(): Unit =
    withReleaseCompiler(currentDependency)

  private def currentDependency: () => Unit = () => {
    val dependency = myFixture.addFileToProject(
      "release-dependency.bend",
      "import Base\ndef value() -> U32:\n  1\n"
    )
    val root = myFixture.addFileToProject(
      "release-root.bend",
      "import ./release-dependency.bend as Dep\ndef main() -> U32:\n  Dep.value()\n"
    )
    myFixture.configureFromExistingVirtualFile(root.getVirtualFile)
    val complete = checkConfigured()
    assertEquals(complete.details, BendCheckOutcome.Success, complete.outcome)
    val offset = root.getText.indexOf("Dep.value") + 5
    myFixture.getEditor.getCaretModel.moveToOffset(offset)
    val target = GotoDeclarationAction.findTargetElement(
      getProject,
      myFixture.getEditor,
      offset
    )
    assertNotNull(target)
    assertEquals(dependency, target.getContainingFile)
    val document =
      PsiDocumentManager.getInstance(getProject).getDocument(dependency)
    assertNotNull(document)
    WriteCommandAction.runWriteCommandAction(
      getProject,
      new Runnable {
        override def run(): Unit = document.setText(
          "import Base\ndef value() -> U32:\n  missing_dependency_value\n"
        )
      }
    )
    PsiDocumentManager.getInstance(getProject).commitAllDocuments()
    val failed = checkConfigured()
    assertEquals(failed.details, BendCheckOutcome.Failed, failed.outcome)
    assertTrue(failed.details.contains("missing_dependency_value"))
    val virtual = dependency.getVirtualFile
    val id = new FileId(
      Option(virtual.getCanonicalPath).getOrElse(virtual.getPath),
      virtual.getCanonicalPath != null
    )
    assertDiagnosticAt(
      failed,
      id,
      document.getText,
      2,
      "missing_dependency_value"
    )
  }

  private def assertDiagnosticAt(
      result: BendCheckResult,
      source: FileId,
      text: String,
      line: Int,
      token: String
  ): Unit =
    val start = text.indexOf(token)
    assertTrue(
      "Compiler diagnostic must map to the captured offending source",
      result.diagnostics.exists(_.location match
        case BendLocation.SourceLine(file, number) =>
          file == source && number == line
        case BendLocation.SourceRange(file, range) =>
          file == source && range.start <= start && range.end > start
        case _ => false)
    )

  private def check(name: String, source: String): BendCheckResult =
    myFixture.configureByText(name, source)
    checkConfigured()

  private def checkConfigured(): BendCheckResult =
    val file = myFixture.getFile.getVirtualFile
    val id = new FileId(
      Option(file.getCanonicalPath).getOrElse(file.getPath),
      file.getCanonicalPath != null
    )
    val service = getProject.getService(classOf[BendCheckService])
    val previous = service.result(id)
    myFixture.performEditorAction("Bend.CheckCurrentFile")
    val deadline = System.nanoTime() + 20_000_000_000L
    while service.result(id).forall(value => previous.contains(value)) && System
        .nanoTime() < deadline
    do Thread.sleep(50)
    service
      .result(id)
      .filterNot(previous.contains)
      .getOrElse(
        throw new AssertionError(
          s"Bend release check did not publish for ${file.getName}"
        )
      )
