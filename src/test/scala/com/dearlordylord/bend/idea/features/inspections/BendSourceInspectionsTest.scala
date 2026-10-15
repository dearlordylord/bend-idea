package com.dearlordylord.bend.idea.features.inspections

import com.dearlordylord.bend.idea.symbols.api.{
  BendCanonicalImportPaths,
  BendUnusedImportAliases
}
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.fileEditor.FileEditorManager
import org.junit.Assert.*
import java.nio.file.{Files, Path}
import com.intellij.openapi.vfs.LocalFileSystem
import com.dearlordylord.bend.idea.adapters.cli.RealBendCompilerFixture
import java.util.concurrent.TimeUnit
import scala.jdk.CollectionConverters.*

final class BendSourceInspectionsTest extends BasePlatformTestCase:
  def testNamingUsesNativeInspectionWithoutCompiler(): Unit =
    myFixture.enableInspections(new BendNamingInspection())
    myFixture.configureByText(
      "names.bend",
      "import ./missing.bend as lower\ntype lower is Data:\n  small{}\ndef U32.add():\n  0\n"
    )
    val findings = myFixture
      .doHighlighting()
      .asScala
      .filter(info =>
        Option(info.getDescription)
          .exists(_.contains("conventionally use PascalCase"))
      )
    assertEquals(3, findings.size)

  def testUnusedAliasKeepsPossibleUsesAndLawFillNames(): Unit =
    val file = myFixture.configureByText(
      "aliases.bend",
      "import ./a.bend as A\nimport ./b.bend as B\nimport ./c.bend as C\ndef B.goal():\n  C.missing\n# A.comment\ndef main():\n  \"A.string\"\n"
    )
    assertEquals(List("A"), BendUnusedImportAliases.in(file).map(_.getText))

  def testDuplicateAliasDoesNotClaimUnused(): Unit =
    val file = myFixture.configureByText(
      "aliases.bend",
      "import ./a.bend as A\nimport ./b.bend as A\ndef main():\n  0\n"
    )
    assertTrue(BendUnusedImportAliases.in(file).isEmpty)

  def testCanonicalPathPreservesAliasAndComment(): Unit =
    myFixture.addFileToProject("library.bend", "def value():\n  7\n")
    val physical = myFixture.addFileToProject(
      "root.bend",
      "import ././library.bend as L # keep\ndef main():\n  L.value()\n"
    )
    myFixture.configureFromExistingVirtualFile(physical.getVirtualFile)
    myFixture.enableInspections(new BendCanonicalImportPathInspection())
    assertEquals(
      List("./library.bend"),
      BendCanonicalImportPaths.in(physical).map(_.replacement)
    )
    myFixture.getEditor.getCaretModel.moveToOffset("import ".length + 2)
    val fix = myFixture.findSingleIntention("Use canonical Bend import path")
    myFixture.launchAction(fix)
    assertEquals(
      "import ./library.bend as L # keep\ndef main():\n  L.value()\n",
      myFixture.getEditor.getDocument.getText
    )
    val undo = UndoManager.getInstance(getProject)
    val editor = FileEditorManager
      .getInstance(getProject)
      .getSelectedEditor(physical.getVirtualFile)
    assertTrue(undo.isUndoAvailable(editor))
    undo.undo(editor)
    assertEquals(
      "import ././library.bend as L # keep\ndef main():\n  L.value()\n",
      myFixture.getEditor.getDocument.getText
    )

  def testPackagesBaseAndMissingPathsAreNotCanonicalized(): Unit =
    val file = myFixture.configureByText(
      "main.bend",
      "import Base\nimport ./missing.bend as M\ndef main():\n  0\n"
    )
    assertTrue(BendCanonicalImportPaths.in(file).isEmpty)

  def testCanonicalPathResolvesSymlinkToSameSource(): Unit =
    com.dearlordylord.bend.idea.test.VfsTestRoots
      .allowSystemTemporaryDirectory(getTestRootDisposable)
    val directory = Files.createTempDirectory("bend-import-symlink-")
    try
      Files.writeString(
        directory.resolve("library.bend"),
        "def value():\n  7\n"
      )
      Files.createSymbolicLink(
        directory.resolve("linked.bend"),
        Path.of("library.bend")
      )
      val root = directory.resolve("root.bend")
      Files.writeString(
        root,
        "import ./linked.bend as L\ndef main():\n  L.value()\n"
      )
      val virtual =
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root)
      val file =
        com.intellij.psi.PsiManager.getInstance(getProject).findFile(virtual)
      assertEquals(
        List("./library.bend"),
        BendCanonicalImportPaths.in(file).map(_.replacement)
      )
    finally removeDirectory(directory)

  def testIncompleteSourceDoesNotClaimAliasUnused(): Unit =
    val file = myFixture.configureByText(
      "partial.bend",
      "import ./library.bend as L\ndef main(\n"
    )
    assertTrue(BendUnusedImportAliases.in(file).isEmpty)

  def testPinnedCompilerAcceptsEquivalentCanonicalImport(): Unit =
    val compiler = RealBendCompilerFixture.inputs
    com.dearlordylord.bend.idea.test.VfsTestRoots
      .allowSystemTemporaryDirectory(getTestRootDisposable)
    val directory = Files.createTempDirectory("bend-canonical-path-")
    val source =
      "import Base\r\nimport ./linked.bend as L # preserved\r\ndef main() -> U32:\r\n  L.value()\r\n"
    val settings =
      com.intellij.openapi.application.ApplicationManager.getApplication
        .getService(
          classOf[
            com.dearlordylord.bend.idea.toolchain.api.BendToolchainSettings
          ]
        )
    val originalSettings = settings.choices
    settings.update(
      originalSettings.copy(
        baseSource = compiler.base.toString,
        diagnosticsEnabled = false
      )
    )
    try
      Files.writeString(
        directory.resolve("library.bend"),
        "import Base\ndef value() -> U32:\n  7\n"
      )
      Files.createSymbolicLink(
        directory.resolve("linked.bend"),
        Path.of("library.bend")
      )
      val root = directory.resolve("root.bend")
      def check(text: String): Unit =
        Files.writeString(root, text)
        val output = directory.resolve("compiler.log")
        val builder = new ProcessBuilder(
          compiler.bunExecutable.toString,
          compiler.main.toString,
          root.toString,
          "--check-only"
        )
          .redirectErrorStream(true)
          .redirectOutput(output.toFile)
        builder.environment().put("BEND_NO_TELEMETRY", "1")
        val process = builder.start()
        try
          if !process.waitFor(30, TimeUnit.SECONDS) then
            fail("Pinned compiler timed out")
          assertEquals(Files.readString(output), 0, process.exitValue())
        finally
          if process.isAlive then
            process.destroyForcibly()
            val _ = process.waitFor(5, TimeUnit.SECONDS)
      check(source)
      val virtual =
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root)
      val fixture = myFixture.asInstanceOf[
        com.intellij.testFramework.fixtures.impl.CodeInsightTestFixtureImpl
      ]
      fixture.setSelectionAndCaretMarkupApplyPolicy(
        com.intellij.testFramework.fixtures.impl.CodeInsightTestFixtureImpl.SelectionAndCaretMarkupApplyPolicy.UPDATE_DOCUMENT_AND_LEAVE_IT_DIRTY
      )
      myFixture.configureFromExistingVirtualFile(virtual)
      myFixture.enableInspections(new BendCanonicalImportPathInspection())
      assertEquals(
        List("./library.bend"),
        BendCanonicalImportPaths.in(myFixture.getFile).map(_.replacement)
      )
      myFixture.getEditor.getCaretModel.moveToOffset(
        "import Base\nimport ".length + 2
      )
      myFixture.launchAction(
        myFixture.findSingleIntention("Use canonical Bend import path")
      )
      val changed = myFixture.getEditor.getDocument.getText
      assertTrue(changed.contains("import ./library.bend as L # preserved"))
      com.intellij.openapi.fileEditor.FileDocumentManager
        .getInstance()
        .saveDocument(myFixture.getEditor.getDocument)
      val saved = Files.readString(root)
      assertTrue(saved.contains("import ./library.bend as L # preserved\r\n"))
      check(saved)
    finally
      myFixture
        .asInstanceOf[
          com.intellij.testFramework.fixtures.impl.CodeInsightTestFixtureImpl
        ]
        .setSelectionAndCaretMarkupApplyPolicy(
          com.intellij.testFramework.fixtures.impl.CodeInsightTestFixtureImpl.SelectionAndCaretMarkupApplyPolicy.UPDATE_FILE_AND_KEEP_DOCUMENT_CLEAN
        )
      settings.update(originalSettings)
      removeDirectory(directory)

  def testLiteralDottedNameConservativelyVetoesAliasHint(): Unit =
    val file = myFixture.configureByText(
      "literal.bend",
      "import ./library.bend as L\ndef L.local():\n  0\n"
    )
    assertTrue(BendUnusedImportAliases.in(file).isEmpty)

  def testAbsenceGuardPropagatesCancellation(): Unit =
    var visits = 0
    try
      val _ =
        com.dearlordylord.bend.idea.syntax.parser.BendSourceDelimiters.balanced(
          "def main(value):\n  value\n",
          () => {
            visits += 1;
            throw new com.intellij.openapi.progress.ProcessCanceledException()
          }
        )
      fail("Expected cancellation")
    catch
      case _: com.intellij.openapi.progress.ProcessCanceledException =>
        assertEquals(1, visits)

  def testLiteralDelimiterContentsRemainOpaque(): Unit =
    val scanner = com.dearlordylord.bend.idea.syntax.parser.BendSourceDelimiters
    assertTrue(scanner.balanced("def main():\n  \"(\"\n"))
    assertFalse(scanner.balanced("def main():\n  {\n  \"}\"\n"))

  private def removeDirectory(directory: Path): Unit =
    val paths = Files.walk(directory)
    try
      paths
        .sorted(java.util.Comparator.reverseOrder())
        .forEach(path => { val _ = Files.deleteIfExists(path) })
    finally paths.close()
