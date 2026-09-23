package com.dearlordylord.bend.idea.features.rename

import com.dearlordylord.bend.idea.adapters.cli.BendCliCheckBackend
import com.dearlordylord.bend.idea.analysis.model.{
  BendCheckOutcome,
  BendCheckSnapshot
}
import com.dearlordylord.bend.idea.model.FileId
import com.dearlordylord.bend.idea.toolchain.api.BendToolchainSelection
import com.dearlordylord.bend.idea.toolchain.api.{
  BendToolchainChoices,
  BendToolchainSettings
}
import com.dearlordylord.bend.idea.symbols.api.{
  BendSourceFiles,
  BendSourceSymbols,
  BendSymbolCategory
}
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.refactoring.BaseRefactoringProcessor
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.openapi.ui.{TestDialog, TestDialogManager}
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.search.GlobalSearchScope
import org.junit.Assert.*
import java.nio.file.{Files, Path}

final class BendDeclarationRenameTest extends BasePlatformTestCase:
  def testCurrentFileFunctionRenameFromUsage(): Unit =
    myFixture.configureByText(
      "main.bend",
      "def value() -> U32:\n  1\ndef main() -> U32:\n  <caret>value()\n"
    )
    myFixture.renameElementAtCaret("item")
    assertEquals(
      "def item() -> U32:\n  1\ndef main() -> U32:\n  item()\n",
      myFixture.getEditor.getDocument.getText
    )

  def testImportedMemberRenameKeepsAlias(): Unit =
    val library =
      myFixture.addFileToProject("lib.bend", "def value() -> U32:\n  1\n")
    myFixture.configureByText(
      "main.bend",
      "import ./lib.bend as L\ndef main() -> U32:\n  L.<caret>value()\n"
    )
    myFixture.renameElementAtCaret("item")
    assertEquals("def item() -> U32:\n  1\n", library.getText)
    assertEquals(
      "import ./lib.bend as L\ndef main() -> U32:\n  L.item()\n",
      myFixture.getEditor.getDocument.getText
    )

  def testDatatypeAndConstructorKeepSeparateNames(): Unit =
    myFixture.configureByText(
      "types.bend",
      "type Unit is Data:\n  Unit{}\ndef main(x: <caret>Unit) -> Unit:\n  Unit{}\n"
    )
    myFixture.renameElementAtCaret("Single")
    assertEquals(
      "type Single is Data:\n  Unit{}\ndef main(x: Single) -> Single:\n  Unit{}\n",
      myFixture.getEditor.getDocument.getText
    )

  def testLiteralDottedNameRenamesAsOneSymbol(): Unit =
    myFixture.configureByText(
      "dotted.bend",
      "def U32.add() -> U32:\n  1\ndef main() -> U32:\n  <caret>U32.add()\n"
    )
    myFixture.renameElementAtCaret("U32.sum")
    assertEquals(
      "def U32.sum() -> U32:\n  1\ndef main() -> U32:\n  U32.sum()\n",
      myFixture.getEditor.getDocument.getText
    )

  def testLawRenameIncludesFillButKeepsSeparateParameters(): Unit =
    myFixture.configureByText(
      "proof.bend",
      "law <caret>claim:\n  for x: Nat\n  {x == x : Nat}\ndef claim(y):\n  y\ndef main():\n  claim(1)\n"
    )
    myFixture.renameElementAtCaret("assertion")
    assertEquals(
      "law assertion:\n  for x: Nat\n  {x == x : Nat}\ndef assertion(y):\n  y\ndef main():\n  assertion(1)\n",
      myFixture.getEditor.getDocument.getText
    )

  def testLawRenameKeepsTwoIndependentProofRoots(): Unit =
    val first = myFixture.addFileToProject(
      "proofA.bend",
      "import ./LAWS.bend as Laws\ndef Laws.claim():\n  1\ndef run():\n  Laws.claim()\n"
    )
    val second = myFixture.addFileToProject(
      "proofB.bend",
      "import ./LAWS.bend as Laws\ndef Laws.claim():\n  2\ndef run():\n  Laws.claim()\n"
    )
    myFixture.configureByText(
      "LAWS.bend",
      "law <caret>claim:\n  {1 == 1 : U32}\n"
    )
    myFixture.renameElementAtCaret("assertion")
    assertEquals(
      "law assertion:\n  {1 == 1 : U32}\n",
      myFixture.getEditor.getDocument.getText
    )
    assertEquals(
      "import ./LAWS.bend as Laws\ndef Laws.assertion():\n  1\ndef run():\n  Laws.assertion()\n",
      first.getText
    )
    assertEquals(
      "import ./LAWS.bend as Laws\ndef Laws.assertion():\n  2\ndef run():\n  Laws.assertion()\n",
      second.getText
    )
    PsiDocumentManager.getInstance(getProject).commitAllDocuments()
    val undo = UndoManager.getInstance(getProject)
    val editor = FileEditorManager
      .getInstance(getProject)
      .getSelectedEditor(myFixture.getFile.getVirtualFile)
    assertTrue(undo.isUndoAvailable(editor))
    val previousDialog = TestDialogManager.setTestDialog(TestDialog.YES)
    try undo.undo(editor)
    finally TestDialogManager.setTestDialog(previousDialog)
    assertEquals(
      "law claim:\n  {1 == 1 : U32}\n",
      myFixture.getEditor.getDocument.getText
    )
    assertEquals(
      "import ./LAWS.bend as Laws\ndef Laws.claim():\n  1\ndef run():\n  Laws.claim()\n",
      PsiDocumentManager.getInstance(getProject).getDocument(first).getText
    )
    assertEquals(
      "import ./LAWS.bend as Laws\ndef Laws.claim():\n  2\ndef run():\n  Laws.claim()\n",
      PsiDocumentManager.getInstance(getProject).getDocument(second).getText
    )

  def testDeclarationCollisionIsRejectedBeforeEdit(): Unit =
    val source = "def first():\n  1\ndef second():\n  2\n"
    myFixture.configureByText(
      "main.bend",
      source.replace("first", "<caret>first")
    )
    var rejected = false
    try myFixture.renameElementAtCaret("second")
    catch
      case _: BaseRefactoringProcessor.ConflictsInTestsException =>
        rejected = true
    assertTrue(rejected)
    assertEquals(source, myFixture.getEditor.getDocument.getText)

  def testDeclarationRenameRejectsCaptureByLocalBinder(): Unit =
    val source =
      "def source() -> U32:\n  1\ndef use(item: U32) -> U32:\n  source()\n"
    myFixture.configureByText(
      "capture.bend",
      source.replace("def source", "def <caret>source")
    )
    var rejected = false
    try myFixture.renameElementAtCaret("item")
    catch
      case _: BaseRefactoringProcessor.ConflictsInTestsException =>
        rejected = true
    assertTrue(rejected)
    assertEquals(source, myFixture.getEditor.getDocument.getText)

  def testDeclarationRenameRejectsCaptureOfUnresolvedCall(): Unit =
    val source =
      "def source() -> U32:\n  1\ndef use() -> U32:\n  item()\ndef main() -> U32:\n  source()\n"
    myFixture.configureByText(
      "unresolved.bend",
      source.replace("def source", "def <caret>source")
    )
    var rejected = false
    try myFixture.renameElementAtCaret("item")
    catch
      case _: BaseRefactoringProcessor.ConflictsInTestsException =>
        rejected = true
    assertTrue(rejected)
    assertEquals(source, myFixture.getEditor.getDocument.getText)

  def testDeclarationRenameAllowsUnrelatedLocalBinder(): Unit =
    myFixture.configureByText(
      "safe.bend",
      "def <caret>source() -> U32:\n  1\ndef use(item: U32) -> U32:\n  item\ndef main() -> U32:\n  source()\n"
    )
    myFixture.renameElementAtCaret("item")
    assertEquals(
      "def item() -> U32:\n  1\ndef use(item: U32) -> U32:\n  item\ndef main() -> U32:\n  item()\n",
      myFixture.getEditor.getDocument.getText
    )

  def testRenameFromQualifiedFillKeepsItsAlias(): Unit =
    val law =
      myFixture.addFileToProject("LAWS.bend", "law claim:\n  {1 == 1 : U32}\n")
    val other = myFixture.addFileToProject(
      "other.bend",
      "import ./LAWS.bend as Proof\ndef Proof.claim():\n  2\n"
    )
    myFixture.configureByText(
      "proof.bend",
      "import ./LAWS.bend as Laws\ndef Laws.<caret>claim():\n  1\n"
    )
    myFixture.renameElementAtCaret("assertion")
    assertEquals(
      "import ./LAWS.bend as Laws\ndef Laws.assertion():\n  1\n",
      myFixture.getEditor.getDocument.getText
    )
    assertEquals("law assertion:\n  {1 == 1 : U32}\n", law.getText)
    assertEquals(
      "import ./LAWS.bend as Proof\ndef Proof.assertion():\n  2\n",
      other.getText
    )

  def testLawRenameRejectsCollisionInAnotherProofRoot(): Unit =
    val source = "law claim:\n  {1 == 1 : U32}\n"
    val proof = myFixture.addFileToProject(
      "proof.bend",
      "import ./LAWS.bend as Laws\ndef Laws.claim():\n  1\ndef Laws.assertion() -> U32:\n  2\n"
    )
    myFixture.configureByText(
      "LAWS.bend",
      source.replace("claim", "<caret>claim")
    )
    var rejected = false
    try myFixture.renameElementAtCaret("assertion")
    catch
      case _: BaseRefactoringProcessor.ConflictsInTestsException =>
        rejected = true
    assertTrue(rejected)
    assertEquals(source, myFixture.getEditor.getDocument.getText)
    assertEquals(
      "import ./LAWS.bend as Laws\ndef Laws.claim():\n  1\ndef Laws.assertion() -> U32:\n  2\n",
      proof.getText
    )

  def testReadonlyLawBlocksFillRename(): Unit =
    val law =
      myFixture.addFileToProject("LAWS.bend", "law claim:\n  {1 == 1 : U32}\n")
    val virtual = law.getVirtualFile
    val previous = virtual.isWritable
    ApplicationManager.getApplication.runWriteAction(new Runnable:
      override def run(): Unit = virtual.setWritable(false))
    try
      myFixture.configureByText(
        "proof.bend",
        "import ./LAWS.bend as Laws\ndef Laws.<caret>claim():\n  1\n"
      )
      var rejected = false
      try myFixture.renameElementAtCaret("assertion")
      catch case _: RuntimeException => rejected = true
      assertTrue(rejected)
      assertEquals("law claim:\n  {1 == 1 : U32}\n", law.getText)
      assertEquals(
        "import ./LAWS.bend as Laws\ndef Laws.claim():\n  1\n",
        myFixture.getEditor.getDocument.getText
      )
    finally
      ApplicationManager.getApplication.runWriteAction(new Runnable:
        override def run(): Unit = virtual.setWritable(previous))

  def testReadonlyCachedLawBlocksFillRename(): Unit =
    val settings = ApplicationManager.getApplication.getService(
      classOf[BendToolchainSettings]
    )
    val previous = settings.choices
    val directory = Files.createTempDirectory("bend-rename-library-")
    val law = Files
      .createDirectories(directory.resolve("cache/0xabc"))
      .resolve("LAWS.bend")
    Files.writeString(law, "law claim:\n  {1 == 1 : U32}\n")
    val virtual =
      LocalFileSystem.getInstance().refreshAndFindFileByPath(law.toString)
    assertNotNull(virtual)
    val writable = virtual.isWritable
    settings.update(
      BendToolchainChoices(packageCache = directory.resolve("cache").toString)
    )
    ApplicationManager.getApplication.runWriteAction(new Runnable:
      override def run(): Unit = virtual.setWritable(false))
    try
      myFixture.configureByText(
        "proof.bend",
        "import 0xabc/LAWS.bend as Laws\ndef Laws.<caret>claim():\n  1\n"
      )
      val (basePath, cachePath) = getProject
        .getService(
          classOf[
            com.dearlordylord.bend.idea.workspace.api.BendLoadingConfiguration
          ]
        )
        .paths
      assertEquals(directory.resolve("cache").toString, cachePath)
      assertNotNull(LocalFileSystem.getInstance().findFileByPath(cachePath))
      val scan = BendSourceFiles.scan(
        myFixture.getFile,
        GlobalSearchScope.projectScope(getProject),
        includeConfiguredLibraries = true
      )
      assertTrue(
        scan.files.map(_.getName).mkString(","),
        scan.files.exists(_.getName == "LAWS.bend")
      )
      val selected = BendSourceSymbols
        .declarations(myFixture.getFile)
        .find(_.name == "Laws.claim")
        .get
      val related = BendSourceSymbols.relatedLawSites(
        scan.files,
        selected,
        basePath,
        cachePath
      )
      assertTrue(
        related.map(_.name).mkString(","),
        related.exists(symbol =>
          symbol.category == BendSymbolCategory.Law &&
            !symbol.declaration.isWritable
        )
      )
      var rejected = false
      try myFixture.renameElementAtCaret("assertion")
      catch case _: RuntimeException => rejected = true
      assertTrue(rejected)
      assertEquals(
        "import 0xabc/LAWS.bend as Laws\ndef Laws.claim():\n  1\n",
        myFixture.getEditor.getDocument.getText
      )
      assertEquals("law claim:\n  {1 == 1 : U32}\n", Files.readString(law))
    finally
      ApplicationManager.getApplication.runWriteAction(new Runnable:
        override def run(): Unit = virtual.setWritable(writable))
      settings.update(previous)
      val paths = Files.walk(directory)
      try
        paths
          .sorted(java.util.Comparator.reverseOrder())
          .forEach(p => Files.deleteIfExists(p))
      finally paths.close()

  def testPinnedCompilerAcceptsBeforeAndAfterDeclarationRename(): Unit =
    val pinned =
      Path.of(".references/bend/bend2/main.ts").toAbsolutePath.normalize()
    val directory = Files.createTempDirectory("bend-declaration-rename-check-")
    try
      val executable = directory.resolve("bend")
      Files.writeString(
        executable,
        "#!/bin/sh\nexec npx --yes bun '" + pinned + "' \"$@\"\n"
      )
      executable.toFile.setExecutable(true)
      val original = directory.resolve("main.bend")
      val source =
        "import Base\ndef value(x: U32) -> U32:\n  x\ndef main() -> U32:\n  value(1)\n"
      Files.writeString(original, source)
      val toolchain = BendToolchainSelection(
        executable.toString,
        pinned.resolveSibling("base.bend").toString,
        "",
        true,
        1L
      )
      val backend = new BendCliCheckBackend(directory)
      def outcome(text: String): BendCheckOutcome =
        backend
          .check(
            BendCheckSnapshot(
              new FileId(original.toString, false),
              original.toString,
              text,
              1L,
              toolchain
            )
          )
          .outcome
      assertEquals(BendCheckOutcome.Success, outcome(source))
      myFixture.configureByText(
        "main.bend",
        source.replace("def value", "def <caret>value")
      )
      myFixture.renameElementAtCaret("item")
      val renamed = myFixture.getEditor.getDocument.getText
      assertEquals(
        "import Base\ndef item(x: U32) -> U32:\n  x\ndef main() -> U32:\n  item(1)\n",
        renamed
      )
      assertEquals(BendCheckOutcome.Success, outcome(renamed))
      assertEquals(source, Files.readString(original))
    finally
      val paths = Files.walk(directory)
      try
        paths
          .sorted(java.util.Comparator.reverseOrder())
          .forEach(p => Files.deleteIfExists(p))
      finally paths.close()
