package com.dearlordylord.bend.idea.features.completion

import com.dearlordylord.bend.idea.adapters.cli.RealBendCompilerFixture
import com.dearlordylord.bend.idea.symbols.api.BendSymbolCategory
import com.dearlordylord.bend.idea.symbols.api.{
  BendWorkspaceSymbolCandidate,
  BendWorkspaceSymbolSearch
}
import com.dearlordylord.bend.idea.syntax.psi.BendReferenceElement
import com.dearlordylord.bend.idea.toolchain.api.{
  BendToolchainChoices,
  BendToolchainSettings
}
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.PsiDocumentManager
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import scala.jdk.CollectionConverters.*
import org.junit.Assert.*

final class BendExplicitImportTest extends BasePlatformTestCase:
  private var originalChoices: BendToolchainChoices = null

  override def setUp(): Unit =
    super.setUp()
    val settings =
      ApplicationManager.getApplication.getService(
        classOf[BendToolchainSettings]
      )
    originalChoices = settings.choices

  override def tearDown(): Unit =
    try
      ApplicationManager.getApplication
        .getService(classOf[BendToolchainSettings])
        .update(originalChoices)
    finally super.tearDown()

  def testUnresolvedCallOffersContextImportFix(): Unit =
    val _ = myFixture.addFileToProject(
      "library/target.bend",
      "def bend_idea_target() -> U32:\n  0\n"
    )
    val root = myFixture.configureByText(
      "main.bend",
      "def main() -> U32:\n  <error descr=\"Unresolved Bend name: bend_idea_target\"><caret>bend_idea_target</error>()\n"
    )

    myFixture.testHighlighting(true, false, true)
    val intention = myFixture.getAvailableIntentions.asScala
      .find(_.getText == "Import Bend symbol")
      .getOrElse(throw new AssertionError("Import context action was missing"))
    myFixture.launchAction(intention)

    assertTrue(
      root.getText,
      root.getText.contains("import library/target.bend as Target")
    )
    assertTrue(root.getText, root.getText.contains("Target.bend_idea_target()"))

  def testDuplicateNamesGetSourceContextAndAliasCollisionIsResolved(): Unit =
    val first =
      myFixture.addFileToProject(
        "one/module.bend",
        "def choose() -> U32:\n  1\n"
      )
    myFixture.addFileToProject("two/module.bend", "def choose() -> U32:\n  2\n")
    val root = myFixture.configureByText(
      "main.bend",
      "import two/module.bend as Module\ndef main() -> U32:\n  <caret>choose()\n"
    )
    val candidates =
      BendWorkspaceSymbolSearch.named(getProject, "choose").toOption.get
    assertTrue(
      "Both same-named declarations need source context",
      candidates.size >= 2
    )
    val chosen = candidates
      .find(candidate =>
        candidate.path.exists(_ == first.getVirtualFile.getPath)
      )
      .get
    assertFalse(chosen.isBase)
    val reference = referenceAtCaret(root)
    val plan =
      BendExplicitImportPlanner.plan(root, reference, chosen).toOption.get
    val inserted =
      BendExplicitImportPlanner.insert(getProject, root, plan).toOption.get
    assertEquals(
      "import two/module.bend as Module\n" +
        "import one/module.bend as Module2\n" +
        "def main() -> U32:\n  Module2.choose()\n",
      root.getText
    )
    assertTrue(inserted.referenceEnd > inserted.referenceStart)
    PsiDocumentManager.getInstance(getProject).commitAllDocuments()
    val editor = FileEditorManager
      .getInstance(getProject)
      .getSelectedEditor(root.getVirtualFile)
    assertNotNull(editor)
    assertEquals(
      "Undo Import Bend symbol",
      UndoManager
        .getInstance(getProject)
        .getUndoActionNameAndDescription(editor)
        .first
        .stripPrefix("_")
    )
    UndoManager.getInstance(getProject).undo(editor)
    PsiDocumentManager.getInstance(getProject).commitAllDocuments()
    assertEquals(
      "import two/module.bend as Module\ndef main() -> U32:\n  choose()\n",
      root.getText
    )

  def testAlreadyImportedDeclarationReusesAliasWithoutAddingAnotherImport()
      : Unit =
    val target =
      myFixture.addFileToProject(
        "lib/module.bend",
        "def choose() -> U32:\n  1\n"
      )
    val root = myFixture.configureByText(
      "main.bend",
      "import lib/module.bend as Lib\ndef main() -> U32:\n  <caret>choose()\n"
    )
    val candidate = candidateAt("choose", target.getVirtualFile.getPath)
    val plan =
      BendExplicitImportPlanner
        .plan(root, referenceAtCaret(root), candidate)
        .toOption
        .get
    val _ = BendExplicitImportPlanner.insert(getProject, root, plan)
    assertEquals(
      "import lib/module.bend as Lib\ndef main() -> U32:\n  Lib.choose()\n",
      root.getText
    )

  def testWorkspaceSearchIncludesConstructorsAndConfiguredBase(): Unit =
    val _ = myFixture.addFileToProject(
      "types.bend",
      "type Choice is Data:\n  Selected{}\n"
    )
    val constructor =
      BendWorkspaceSymbolSearch.named(getProject, "Selected").toOption.get.head
    assertEquals(BendSymbolCategory.Constructor, constructor.symbol.category)

    val base = RealBendCompilerFixture.inputs.base
    VfsRootAccess.allowRootAccess(
      getTestRootDisposable,
      base.getParent.toString
    )
    ApplicationManager.getApplication
      .getService(classOf[BendToolchainSettings])
      .update(BendToolchainChoices(baseSource = base.toString))
    val root = myFixture.configureByText(
      "base-use.bend",
      "def main() -> <caret>U32:\n  0\n"
    )
    val candidate = BendWorkspaceSymbolSearch
      .named(getProject, "U32")
      .toOption
      .get
      .find(_.isBase)
      .getOrElse(
        throw new AssertionError("Configured Base symbols were not found")
      )
    val plan = BendExplicitImportPlanner
      .plan(root, referenceAtCaret(root), candidate)
      .toOption
      .get
    val _ = BendExplicitImportPlanner.insert(getProject, root, plan)
    assertTrue(root.getText.startsWith("import Base\n"))
    assertTrue(root.getText.contains("-> U32"))

  def testBaseIoTypeResolvesWithoutAnotherImport(): Unit =
    configureBase()
    val root = myFixture.configureByText(
      "main.bend",
      "import Base\n\ndef main() -> <caret>IO(Unit):\n  IO.print(\"hello\")\n"
    )
    assertNotNull(
      "Base IO should resolve in a return type",
      referenceAtCaret(root).getReferences.head.resolve()
    )
    myFixture.testHighlighting(true, false, true)
    assertFalse(
      myFixture.getAvailableIntentions.asScala.exists(
        _.getText == "Import Bend symbol"
      )
    )

  def testMissingBaseIoTypeOffersWorkingImportFix(): Unit =
    configureBase()
    val root = myFixture.configureByText(
      "main.bend",
      "def main() -> <error descr=\"Unresolved Bend name: IO\"><caret>IO</error>(Unit):\n  ?TODO\n"
    )
    myFixture.testHighlighting(true, false, true)
    val ioCandidates =
      BendWorkspaceSymbolSearch.named(getProject, "IO").toOption.get
    assertEquals(
      "Base has a law and its fill for IO; one import should cover both",
      2,
      ioCandidates.count(_.isBase)
    )
    val intention = myFixture.getAvailableIntentions.asScala
      .find(_.getText == "Import Bend symbol")
      .getOrElse(
        throw new AssertionError("Import context action was missing for IO")
      )
    myFixture.launchAction(intention)
    assertTrue(root.getText, root.getText.startsWith("import Base\n"))
    myFixture.getEditor.getCaretModel.moveToOffset(
      root.getText.indexOf("IO(Unit)")
    )
    assertNotNull(
      "Imported IO should resolve",
      referenceAtCaret(root).getReferences.head.resolve()
    )

  def testGeneratedImportPassesThePinnedCompiler(): Unit =
    val target = myFixture.addFileToProject(
      "library/functions.bend",
      "def imported() -> U32:\n  1\n"
    )
    val root = myFixture.configureByText(
      "main.bend",
      "import Base\ndef main() -> U32:\n  <caret>imported()\n"
    )
    val candidate = candidateAt("imported", target.getVirtualFile.getPath)
    val plan = BendExplicitImportPlanner
      .plan(root, referenceAtCaret(root), candidate)
      .toOption
      .get
    val _ = BendExplicitImportPlanner.insert(getProject, root, plan)
    PsiDocumentManager.getInstance(getProject).commitAllDocuments()
    FileDocumentManager.getInstance().saveAllDocuments()
    val working = Files.createTempDirectory("bend-auto-import-")
    try
      val executable =
        RealBendCompilerFixture.writeLauncher(working.resolve("bend"))
      val offline = Files.createDirectories(working.resolve("offline"))
      val source = working.resolve("main.bend")
      val imported = Files
        .createDirectories(working.resolve("library"))
        .resolve("functions.bend")
      Files.writeString(source, root.getText)
      Files.writeString(imported, target.getText)
      val builder = new ProcessBuilder(
        executable.toString,
        source.toString,
        "--check-only"
      ).directory(working.toFile).redirectErrorStream(true)
      val env = builder.environment()
      val _ = env.put("BEND_LIB", offline.toString)
      val _ = env.put("BEND_HUB", "file:///__bend_editor_offline__")
      val process = builder.start()
      assertTrue(
        "Pinned compiler timed out",
        process.waitFor(20, TimeUnit.SECONDS)
      )
      val output =
        new String(
          process.getInputStream.readAllBytes(),
          StandardCharsets.UTF_8
        )
      assertEquals(output, 0, process.exitValue())
      assertTrue(output, output.contains("All terms check"))
    finally
      val paths = Files.walk(working)
      try
        paths
          .sorted(java.util.Comparator.reverseOrder())
          .forEach(path =>
            val _ = Files.deleteIfExists(path)
          )
      finally paths.close()

  private def candidateAt(
      name: String,
      path: String
  ): BendWorkspaceSymbolCandidate =
    BendWorkspaceSymbolSearch
      .named(getProject, name)
      .toOption
      .get
      .find(_.path.exists(_ == path))
      .getOrElse(throw new AssertionError("Workspace candidate was not found"))

  private def configureBase(): Unit =
    val base = RealBendCompilerFixture.inputs.base
    VfsRootAccess.allowRootAccess(
      getTestRootDisposable,
      base.getParent.toString
    )
    ApplicationManager.getApplication
      .getService(classOf[BendToolchainSettings])
      .update(BendToolchainChoices(baseSource = base.toString))

  private def referenceAtCaret(
      file: com.intellij.psi.PsiFile
  ): BendReferenceElement =
    val leaf = file.findElementAt(myFixture.getEditor.getCaretModel.getOffset)
    val reference =
      if leaf == null then null
      else PsiTreeUtil.getParentOfType(leaf, classOf[BendReferenceElement])
    assertNotNull("Expected a Bend reference at the caret", reference)
    reference
