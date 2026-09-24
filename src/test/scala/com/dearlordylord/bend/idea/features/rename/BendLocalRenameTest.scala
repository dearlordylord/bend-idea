package com.dearlordylord.bend.idea.features.rename

import com.dearlordylord.bend.idea.adapters.cli.RealBendCompilerFixture
import com.dearlordylord.bend.idea.adapters.cli.BendCliCheckBackend
import com.dearlordylord.bend.idea.analysis.model.{BendCheckOutcome, BendCheckSnapshot}
import com.dearlordylord.bend.idea.model.FileId
import com.dearlordylord.bend.idea.toolchain.api.BendToolchainSelection
import com.dearlordylord.bend.idea.syntax.psi.{BendAlias, BendReferenceElement}
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.refactoring.BaseRefactoringProcessor
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.containers.MultiMap
import org.junit.Assert.*
import scala.jdk.CollectionConverters.*
import java.nio.file.{Files, Path}

final class BendLocalRenameTest extends BasePlatformTestCase:
  def testParameterRenameUsesNativeReferences(): Unit =
    val file = myFixture.configureByText("rename.bend",
      "def main(value: U32) -> U32:\n  U32.add(value, value)\n")
    val binder = PsiTreeUtil.findChildrenOfType(file, classOf[BendReferenceElement]).asScala
      .find(_.getText == "value").get
    assertTrue(new BendLocalRenameProcessor().canProcessElement(binder))
    myFixture.renameElement(binder, "item")
    assertEquals("def main(item: U32) -> U32:\n  U32.add(item, item)\n", file.getText)

  def testRenameFromUsageKeepsShadowedBinderSeparate(): Unit =
    myFixture.configureByText("rename.bend",
      "def main(value: U32) -> U32:\n  let other = value\n  value => <caret>value\n  other\n")
    myFixture.renameElementAtCaret("renamed")
    assertEquals("def main(value: U32) -> U32:\n  let other = value\n  renamed => renamed\n  other\n",
      myFixture.getEditor.getDocument.getText)

  def testCollisionIsReportedBeforeEdit(): Unit =
    val file = myFixture.configureByText("rename.bend",
      "def main(x: U32, y: U32) -> U32:\n  U32.add(x, y)\n")
    val binder = PsiTreeUtil.findChildrenOfType(file, classOf[BendReferenceElement]).asScala
      .find(_.getText == "x").get
    val conflicts = new MultiMap[com.intellij.psi.PsiElement, String]()
    new BendLocalRenameProcessor().findExistingNameConflicts(binder, "y", conflicts)
    assertFalse(conflicts.isEmpty)
    assertThrows(classOf[BaseRefactoringProcessor.ConflictsInTestsException], () =>
      myFixture.renameElement(binder, "y"))
    assertEquals("def main(x: U32, y: U32) -> U32:\n  U32.add(x, y)\n", file.getText)

  def testCaptureIsRejectedBeforeEdit(): Unit =
    val source = "def main(x: U32) -> U32:\n  U32.add(x, other)\n"
    val file = myFixture.configureByText("capture.bend", source)
    val binder = PsiTreeUtil.findChildrenOfType(file, classOf[BendReferenceElement]).asScala
      .find(_.getText == "x").get
    assertThrows(classOf[BaseRefactoringProcessor.ConflictsInTestsException], () =>
      myFixture.renameElement(binder, "other"))
    assertEquals(source, file.getText)

  def testAliasRenamePreservesMemberSpelling(): Unit =
    myFixture.addFileToProject("lib.bend", "def value() -> U32:\n  1\n")
    val file = myFixture.configureByText("main.bend",
      "import ./lib.bend as <caret>L\ndef main() -> U32:\n  L.value()\n")
    val alias = PsiTreeUtil.findChildOfType(file, classOf[BendAlias])
    assertNotNull(alias)
    myFixture.renameElementAtCaret("Library")
    assertEquals("import ./lib.bend as Library\ndef main() -> U32:\n  Library.value()\n", file.getText)
    PsiDocumentManager.getInstance(getProject).commitAllDocuments()
    val undo = UndoManager.getInstance(getProject)
    val editor = FileEditorManager.getInstance(getProject).getSelectedEditor(file.getVirtualFile)
    assertTrue(undo.isUndoAvailable(editor))
    undo.undo(editor)
    assertEquals("import ./lib.bend as L\ndef main() -> U32:\n  L.value()\n",
      myFixture.getEditor.getDocument.getText)

  def testAliasRenameFromQualifiedUsage(): Unit =
    myFixture.addFileToProject("lib.bend", "def value() -> U32:\n  1\n")
    myFixture.configureByText("main.bend",
      "import ./lib.bend as L\ndef main() -> U32:\n  <caret>L.value()\n")
    myFixture.renameElementAtCaret("Library")
    assertEquals("import ./lib.bend as Library\ndef main() -> U32:\n  Library.value()\n",
      myFixture.getEditor.getDocument.getText)

  def testAliasCollisionAndInvalidNameAreRejected(): Unit =
    myFixture.addFileToProject("first.bend", "def value() -> U32:\n  1\n")
    myFixture.addFileToProject("second.bend", "def value() -> U32:\n  2\n")
    val source = "import ./first.bend as First\nimport ./second.bend as Second\ndef main() -> U32:\n  First.value()\n"
    val file = myFixture.configureByText("main.bend", source)
    val first = PsiTreeUtil.findChildrenOfType(file, classOf[BendAlias]).asScala
      .find(_.getName == "First").get
    assertThrows(classOf[BaseRefactoringProcessor.ConflictsInTestsException], () =>
      myFixture.renameElement(first, "Second"))
    assertThrows(classOf[BaseRefactoringProcessor.ConflictsInTestsException], () =>
      myFixture.renameElement(first, "bad.name"))
    assertEquals(source, file.getText)

  def testCaseBinderRenameStaysInItsArm(): Unit =
    myFixture.configureByText("case.bend",
      "def f(xs: List<U32>) -> U32:\n  match xs:\n    case Cons{head, tail}:\n      <caret>head\n    case Nil{}:\n      0\n")
    myFixture.renameElementAtCaret("first")
    assertEquals("def f(xs: List<U32>) -> U32:\n  match xs:\n    case Cons{first, tail}:\n      first\n    case Nil{}:\n      0\n",
      myFixture.getEditor.getDocument.getText)

  def testDoBinderRenameKeepsAssignmentAndUseTogether(): Unit =
    myFixture.configureByText("do.bend",
      "def f() -> IO<Unit>:\n  do IO<Unit>:\n    value : Nat <- IO.pure(Nat, 1)\n    return <caret>value\n")
    myFixture.renameElementAtCaret("result")
    assertEquals("def f() -> IO<Unit>:\n  do IO<Unit>:\n    result : Nat <- IO.pure(Nat, 1)\n    return result\n",
      myFixture.getEditor.getDocument.getText)

  def testLetBinderRenameKeepsRhsUnchanged(): Unit =
    myFixture.configureByText("let.bend",
      "def f(y: U32) -> U32:\n  value = y; <caret>value\n")
    myFixture.renameElementAtCaret("result")
    assertEquals("def f(y: U32) -> U32:\n  result = y; result\n",
      myFixture.getEditor.getDocument.getText)

  def testTelescopeRenameUpdatesLaterParameterType(): Unit =
    myFixture.configureByText("telescope.bend",
      "def f(n: Nat, value: Vec<n>) -> Nat:\n  <caret>n\n")
    myFixture.renameElementAtCaret("size")
    assertEquals("def f(size: Nat, value: Vec<size>) -> Nat:\n  size\n",
      myFixture.getEditor.getDocument.getText)

  def testRewriteBinderRenameStaysInsideMotive(): Unit =
    val file = myFixture.configureByText("rewrite.bend",
      "def proof(n: Nat):\n  %e@n : {<caret>e == n : Nat}; {==}\n")
    val names = PsiTreeUtil.findChildrenOfType(file, classOf[BendReferenceElement]).asScala
      .filter(_.getText == "e").toList.sortBy(_.getTextOffset)
    assertEquals(2, names.size)
    assertTrue(com.dearlordylord.bend.idea.symbols.api.BendSourceSymbols
      .bindingDeclaredAt(file, names.head.getTextOffset).nonEmpty)
    assertEquals(names.head, names.last.getReferences.head.resolve())
    myFixture.renameElementAtCaret("witness")
    assertEquals("def proof(n: Nat):\n  %witness@n : {witness == n : Nat}; {==}\n",
      myFixture.getEditor.getDocument.getText)

  def testPinnedCompilerAcceptsBeforeAndAfterLocalRename(): Unit =
    val compiler = RealBendCompilerFixture.inputs
    val directory = Files.createTempDirectory("bend-rename-check-")
    try
      val executable = directory.resolve("bend")
      compiler.writeLauncher(executable)
      val original = directory.resolve("main.bend")
      val source = "import Base\ndef main(value: U32) -> U32:\n  value\n"
      Files.writeString(original, source)
      val toolchain = BendToolchainSelection(executable.toString,
        compiler.base.toString, "", true, 1L)
      val backend = new BendCliCheckBackend(directory)
      def outcome(text: String): BendCheckOutcome =
        backend.check(BendCheckSnapshot(new FileId(original.toString, false),
          original.toString, text, 1L, toolchain)).outcome
      assertEquals(BendCheckOutcome.Success, outcome(source))
      myFixture.configureByText("main.bend", source.replace("  value\n", "  <caret>value\n"))
      myFixture.renameElementAtCaret("item")
      val renamed = myFixture.getEditor.getDocument.getText
      assertEquals("import Base\ndef main(item: U32) -> U32:\n  item\n", renamed)
      assertEquals(BendCheckOutcome.Success, outcome(renamed))
      assertEquals(source, Files.readString(original))
    finally
      val paths = Files.walk(directory)
      try paths.sorted(java.util.Comparator.reverseOrder()).forEach(p => Files.deleteIfExists(p))
      finally paths.close()
