package com.dearlordylord.bend.idea.features.editing

import com.dearlordylord.bend.idea.adapters.cli.RealBendCompilerFixture
import com.dearlordylord.bend.idea.analysis.api.{
  BendExplicitCheckOutcome,
  BendExplicitCheckRunner
}
import com.dearlordylord.bend.idea.analysis.model.{
  BendCheckOutcome,
  BendCompleteness
}
import com.dearlordylord.bend.idea.toolchain.api.{
  BendToolchainChoices,
  BendToolchainSettings
}
import com.dearlordylord.bend.idea.features.templates.api.BendSnippets
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.{Files, Path}
import java.util.concurrent.{CountDownLatch, TimeUnit}
import org.junit.Assert.*

final class BendMatchSkeletonGeneratorTest extends BasePlatformTestCase:
  private var original: BendToolchainChoices = null
  private var directory: Path = null

  override def setUp(): Unit =
    super.setUp()
    val settings = ApplicationManager.getApplication.getService(
      classOf[BendToolchainSettings]
    )
    original = settings.choices
    directory = Files.createTempDirectory("bend-match-skeleton-")
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
            .forEach(p => {
              val _ = Files.deleteIfExists(p)
            })
        finally paths.close()
    finally super.tearDown()

  def testImportedDatatypeUsesAliasPreservesCasesAndChecksAsIncomplete(): Unit =
    val _ = myFixture.addFileToProject(
      "types/Maybe.bend",
      "import Base\ntype Maybe is Data:\n  None{}\n  Some{value: Nat}\n"
    )
    myFixture.configureByText(
      "main.bend",
      "import Base\nimport ./types/Maybe.bend as Option\ndef inspect(value: Option.Maybe) -> Nat:\n  match value:\n    case Option.None{}:\n      0n\n"
    )
    val file = myFixture.getFile
    val matchOffset = file.getText.indexOf("match value")
    val planned = BendMatchSkeletonGenerator
      .plan(file, matchOffset)
      .getOrElse(
        throw new AssertionError("Supported typed match was unavailable")
      )
    val inserted = BendMatchSkeletonGenerator
      .insert(getProject, planned)
      .fold(reason => throw new AssertionError(reason), identity)
    PsiDocumentManager.getInstance(getProject).commitAllDocuments()
    assertEquals(
      "import Base\nimport ./types/Maybe.bend as Option\ndef inspect(value: Option.Maybe) -> Nat:\n  match value:\n    case Option.None{}:\n      0n\n    case Option.Some{value2}:\n      ?TODO\n",
      file.getText
    )
    assertEquals(
      "?TODO",
      file.getText.substring(inserted.placeholderStart, inserted.placeholderEnd)
    )

    val completed = new CountDownLatch(1)
    var outcome: Option[BendExplicitCheckOutcome] = None
    getProject
      .getService(classOf[BendExplicitCheckRunner])
      .check(file.getVirtualFile.getPath, "Checking generated match") { value =>
        outcome = Some(value)
        completed.countDown()
      }
    assertTrue(
      "Pinned compiler check did not finish",
      completed.await(30, TimeUnit.SECONDS)
    )
    val result =
      outcome.getOrElse(throw new AssertionError("No compiler result")) match
        case BendExplicitCheckOutcome.Published(value) => value
        case other                                     =>
          throw new AssertionError(s"Expected a compiler result, got $other")
    assertEquals(BendCompleteness.Incomplete, result.completeness)

    myFixture.performEditorAction(IdeActions.ACTION_UNDO)
    PsiDocumentManager.getInstance(getProject).commitAllDocuments()
    assertEquals(
      "import Base\nimport ./types/Maybe.bend as Option\ndef inspect(value: Option.Maybe) -> Nat:\n  match value:\n    case Option.None{}:\n      0n\n",
      myFixture.getEditor.getDocument.getText
    )

  def testDirectlyTypedPatternFieldIsMatchable(): Unit =
    myFixture.configureByText(
      "nested.bend",
      "import Base\ntype Inner is Data:\n  Off{}\n  On{}\ntype Box is Data:\n  Box{inner: Inner}\ndef inspect(box: Box) -> U32:\n  match box:\n    case Box{inner}:\n      match inner:\n"
    )
    val file = myFixture.getFile
    val nestedMatch = file.getText.lastIndexOf("match inner")
    assertTrue(
      "A directly typed constructor field is matchable",
      BendMatchSkeletonGenerator.plan(file, nestedMatch).nonEmpty
    )
    val plan = BendMatchSkeletonGenerator.plan(file, nestedMatch).get
    assertTrue(plan.insertion.contains("case Off{}:"))
    assertTrue(plan.insertion.contains("case On{}:"))

  def testErasedConstructorFieldGeneratesValidPatternAndCompiles(): Unit =
    myFixture.configureByText(
      "erased.bend",
      "import Base\ntype Erased is Data:\n  ErasedBox{-payload: Nat, value: Nat}\ndef inspect(box: Erased) -> Nat:\n  match box:\n"
    )
    val file = myFixture.getFile
    val planned = BendMatchSkeletonGenerator
      .plan(file, file.getText.indexOf("match box"))
      .getOrElse(throw new AssertionError("Erased-field match unavailable"))
    assertTrue(
      "Erasedness belongs to the declaration, not a case-pattern prefix",
      planned.insertion.contains("case ErasedBox{payload, value}:")
    )
    assertFalse(planned.insertion.contains("-payload"))
    val _ = BendMatchSkeletonGenerator
      .insert(getProject, planned)
      .fold(reason => throw new AssertionError(reason), identity)
    WriteCommandAction.runWriteCommandAction(
      getProject,
      new Runnable:
        override def run(): Unit =
          myFixture.getEditor.getDocument.replaceString(
            file.getText.indexOf("?TODO"),
            file.getText.indexOf("?TODO") + "?TODO".length,
            "value"
          )
    )
    PsiDocumentManager.getInstance(getProject).commitAllDocuments()
    val completed = new CountDownLatch(1)
    var outcome: Option[BendExplicitCheckOutcome] = None
    getProject
      .getService(classOf[BendExplicitCheckRunner])
      .check(file.getVirtualFile.getPath, "Checking erased match") { value =>
        outcome = Some(value)
        completed.countDown()
      }
    assertTrue(
      "Pinned compiler check did not finish",
      completed.await(30, TimeUnit.SECONDS)
    )
    outcome.getOrElse(throw new AssertionError("No compiler result")) match
      case BendExplicitCheckOutcome.Published(value) =>
        assertEquals(
          s"Pinned compiler rejected generated erased-field pattern: ${value.details}",
          BendCheckOutcome.Success,
          value.outcome
        )
        assertEquals(BendCompleteness.Complete, value.completeness)
      case other =>
        throw new AssertionError(s"Expected compiler result, got $other")

  def testCatchAllArmMakesGenerationUnavailable(): Unit =
    myFixture.configureByText(
      "catch-all.bend",
      "import Base\ntype Maybe is Data:\n  None{}\n  Some{value: Nat}\ndef inspect(value: Maybe):\n  match value:\n    case rest:\n      ?TODO\n"
    )
    val file = myFixture.getFile
    assertTrue(
      "Do not append constructor arms after a catch-all binder",
      BendMatchSkeletonGenerator
        .plan(file, file.getText.indexOf("match value"))
        .isEmpty
    )

  def testComputedLetBoundUnknownAndEmptyShapesAreUnavailable(): Unit =
    myFixture.configureByText(
      "unsupported.bend",
      "import Base\ntype Maybe is Data:\n  None{}\n  Some{}\ndef inspect(value: Maybe):\n  local = value\n  match local:\n    ?TODO\n"
    )
    val source = myFixture.getFile.getText
    assertTrue(
      "Ordinary let-bound values are unsupported",
      BendMatchSkeletonGenerator
        .plan(myFixture.getFile, source.indexOf("match local"))
        .isEmpty
    )
    myFixture.configureByText(
      "computed.bend",
      "import Base\ntype Maybe is Data:\n  None{}\n  Some{}\ndef inspect(value: Maybe):\n  match identity(value):\n    ?TODO\n"
    )
    assertTrue(
      "Computed scrutinees are unsupported",
      BendMatchSkeletonGenerator
        .plan(
          myFixture.getFile,
          myFixture.getFile.getText.indexOf("match identity")
        )
        .isEmpty
    )

    myFixture.configureByText(
      "empty.bend",
      "import Base\ntype EmptyData is Data:\ndef inspect(value: EmptyData):\n  match value:\n"
    )
    val empty = myFixture.getFile
    assertTrue(
      "Empty datatypes do not produce a misleading skeleton",
      BendMatchSkeletonGenerator
        .plan(empty, empty.getText.indexOf("match value"))
        .isEmpty
    )

  def testStalePlanDoesNotEditAndRendererKeepsConstructorOrder(): Unit =
    assertEquals(
      "  case First{a, b}:\n    ?TODO\n  case Second{}:\n    ?TODO\n",
      BendSnippets.renderMatchArms(
        List(
          BendSnippets.MatchArm("First", List("a", "b")),
          BendSnippets.MatchArm("Second", Nil)
        ),
        "  "
      )
    )
    myFixture.configureByText(
      "stale.bend",
      "import Base\ntype Maybe is Data:\n  None{}\n  Some{}\ndef inspect(value: Maybe):\n  match value:\n"
    )
    val file = myFixture.getFile
    val planned = BendMatchSkeletonGenerator
      .plan(file, file.getText.indexOf("match value"))
      .getOrElse(throw new AssertionError("Match plan unavailable"))
    WriteCommandAction.runWriteCommandAction(
      getProject,
      new Runnable:
        override def run(): Unit =
          myFixture.getEditor.getDocument.insertString(
            myFixture.getEditor.getDocument.getTextLength,
            "# edit\n"
          )
    )
    PsiDocumentManager.getInstance(getProject).commitAllDocuments()
    val before = myFixture.getEditor.getDocument.getText
    assertTrue(
      "A plan based on older source must be rejected",
      BendMatchSkeletonGenerator.insert(getProject, planned).isLeft
    )
    assertEquals(before, myFixture.getEditor.getDocument.getText)
