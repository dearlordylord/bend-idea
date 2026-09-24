package com.dearlordylord.bend.idea.features.proofs

import com.dearlordylord.bend.idea.adapters.cli.RealBendCompilerFixture
import com.dearlordylord.bend.idea.analysis.api.BendCheckService
import com.dearlordylord.bend.idea.analysis.api.BendExplicitCheckRunner
import com.dearlordylord.bend.idea.analysis.model.BendCompleteness
import com.dearlordylord.bend.idea.features.templates.api.BendSnippets
import com.dearlordylord.bend.idea.model.FileId
import com.dearlordylord.bend.idea.symbols.api.{
  BendSourceDocumentation,
  BendSourceSymbols,
  BendSymbolCategory
}
import com.dearlordylord.bend.idea.syntax.psi.{BendLaw, BendSourceParameter}
import com.dearlordylord.bend.idea.toolchain.api.{
  BendToolchainChoices,
  BendToolchainSettings
}
import com.intellij.openapi.application.ApplicationManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.openapi.actionSystem.IdeActions
import java.nio.file.{Files, Path}
import java.util.concurrent.TimeUnit
import org.junit.Assert.*

final class BendProofFillGeneratorTest extends BasePlatformTestCase:
  private var original: BendToolchainChoices = null
  private var directory: Path = null

  override def setUp(): Unit =
    super.setUp()
    val settings = ApplicationManager.getApplication.getService(
      classOf[BendToolchainSettings]
    )
    original = settings.choices
    directory = Files.createTempDirectory("bend-proof-fill-")
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

  def testGeneratedFillUsesVisibleRootTelescopeAndRemainsIncomplete(): Unit =
    val lawFile = myFixture.addFileToProject(
      "LAWS.bend",
      "import Base\nlaw claim:\n  for +n: Nat\n  exs m: Nat\n  for -later: Nat\n  {n == m : Nat}\n"
    )
    myFixture.configureByText(
      "PROOF.bend",
      "import Base\nimport ./LAWS.bend as Laws\ndef main() -> U32:\n  0\n"
    )
    val root = myFixture.getFile
    val law = PsiTreeUtil.findChildOfType(lawFile, classOf[BendLaw])
    assertNotNull(law)
    val target = BendProofFillGenerator
      .candidates(law, List(root.getVirtualFile.getPath))
      .find(_.name == "Laws.claim")
      .getOrElse(throw new AssertionError("Imported law was not a fill target"))

    val inserted = BendProofFillGenerator
      .insert(getProject, target)
      .fold(
        reason => throw new AssertionError(reason),
        identity
      )
    PsiDocumentManager.getInstance(getProject).commitAllDocuments()
    assertEquals(
      "import Base\nimport ./LAWS.bend as Laws\ndef main() -> U32:\n  0\n\n" +
        "def Laws.claim(n):\n  ?TODO\n",
      root.getText
    )
    val fill = BendSourceSymbols
      .declarations(root)
      .find(symbol =>
        symbol.category == BendSymbolCategory.Definition &&
          symbol.name == "Laws.claim"
      )
      .get
    assertEquals(
      "The generated bare binders must remain linked to their source law",
      Some("claim"),
      BendSourceDocumentation.site(root, fill).law.map(_.name)
    )
    assertEquals(
      "?TODO",
      root.getText.substring(inserted.placeholderStart, inserted.placeholderEnd)
    )

    val rootId = new FileId(
      Option(root.getVirtualFile.getCanonicalPath)
        .getOrElse(root.getVirtualFile.getPath),
      root.getVirtualFile.getCanonicalPath != null
    )
    getProject
      .getService(classOf[BendExplicitCheckRunner])
      .check(root.getVirtualFile.getPath, "Checking generated proof root")(_ =>
        ()
      )
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
    var result = getProject.getService(classOf[BendCheckService]).result(rootId)
    while result.isEmpty && System.nanoTime() < deadline do
      Thread.sleep(50)
      result = getProject.getService(classOf[BendCheckService]).result(rootId)
    assertTrue("Generated root was not checked", result.nonEmpty)
    assertEquals(BendCompleteness.Incomplete, result.get.completeness)
    assertEquals("Check incomplete", result.get.status)

    myFixture.performEditorAction(IdeActions.ACTION_UNDO)
    assertEquals(
      "import Base\nimport ./LAWS.bend as Laws\ndef main() -> U32:\n  0\n",
      myFixture.getEditor.getDocument.getText
    )

  def testFillRendererUsesBareNamesForQuantityAndTemplateBinders(): Unit =
    assertEquals(
      "def Laws.claim(A, n):\n  ?TODO\n",
      BendSnippets.renderLawFill(
        "Laws.claim",
        List(
          BendSourceParameter("A", "~A: Type", template = true),
          BendSourceParameter("n", "-n: Nat", quantity = Some('-'))
        )
      )
    )

  def testExistingVisibleFillIsExcludedAsCollision(): Unit =
    val lawFile = myFixture.addFileToProject(
      "LAWS.bend",
      "law claim:\n  for n: Nat\n  {n == n : Nat}\n"
    )
    val root = myFixture.addFileToProject(
      "PROOF.bend",
      "import ./LAWS.bend as Laws\ndef Laws.claim(n):\n  n\n"
    )
    val law = PsiTreeUtil.findChildOfType(lawFile, classOf[BendLaw])
    assertNotNull(law)
    assertTrue(
      "An existing root definition must never be overwritten",
      BendProofFillGenerator
        .candidates(
          law,
          List(root.getVirtualFile.getPath)
        )
        .isEmpty
    )

  def testChangedImportAliasRejectsPreviouslyPlannedFill(): Unit =
    val lawFile = myFixture.addFileToProject(
      "LAWS.bend",
      "law claim:\n  for n: Nat\n  {n == n : Nat}\n"
    )
    myFixture.configureByText(
      "PROOF.bend",
      "import ./LAWS.bend as Laws\ndef main():\n  0\n"
    )
    val root = myFixture.getFile
    val law = PsiTreeUtil.findChildOfType(lawFile, classOf[BendLaw])
    val target = BendProofFillGenerator
      .candidates(law, List(root.getVirtualFile.getPath))
      .find(_.name == "Laws.claim")
      .get
    com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(
      getProject,
      new Runnable:
        override def run(): Unit =
          myFixture.getEditor.getDocument.setText(
            "import ./LAWS.bend as Renamed\ndef main():\n  0\n"
          )
    )
    PsiDocumentManager.getInstance(getProject).commitAllDocuments()
    val before = myFixture.getEditor.getDocument.getText
    val result = BendProofFillGenerator.insert(getProject, target)
    assertTrue("A stale alias must reject insertion", result.isLeft)
    assertEquals(before, myFixture.getEditor.getDocument.getText)
