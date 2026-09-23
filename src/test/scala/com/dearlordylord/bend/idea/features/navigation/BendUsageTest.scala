package com.dearlordylord.bend.idea.features.navigation

import com.dearlordylord.bend.idea.toolchain.api.{
  BendToolchainChoices,
  BendToolchainSettings
}
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.{PsiDocumentManager, PsiElement, PsiReference}
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.dearlordylord.bend.idea.syntax.psi.BendDefinition
import com.dearlordylord.bend.idea.symbols.api.BendSourceSymbols
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.*
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

final class BendUsageTest extends BasePlatformTestCase:
  private var original: BendToolchainChoices = null
  private var temporary: Path = null

  override def setUp(): Unit =
    super.setUp()
    val settings = ApplicationManager.getApplication.getService(
      classOf[BendToolchainSettings]
    )
    original = settings.choices
    temporary = Files.createTempDirectory("bend-usages")
    settings.update(
      BendToolchainChoices(baseSource = temporary.resolve("base.bend").toString)
    )

  override def tearDown(): Unit =
    try
      ApplicationManager.getApplication
        .getService(classOf[BendToolchainSettings])
        .update(original)
      val walk = Files.walk(temporary)
      try
        walk
          .sorted(java.util.Comparator.reverseOrder())
          .forEach(path => Files.deleteIfExists(path))
      finally walk.close()
    finally super.tearDown()

  private def target(source: String): PsiElement =
    myFixture.configureByText("main.bend", source)
    val at = myFixture.getEditor.getCaretModel.getOffset
    val reference = myFixture.getFile.findReferenceAt(at)
    assertNotNull("Reference at caret", reference)
    val resolved = reference.resolve()
    assertNotNull("Resolved binding", resolved)
    resolved

  private def usages(element: PsiElement): List[PsiReference] =
    ReferencesSearch
      .search(element)
      .findAll()
      .asScala
      .toList
      .filter(r =>
        r.getElement.getContainingFile.getLanguage ==
          com.dearlordylord.bend.idea.syntax.BendLanguage.instance
      )

  def testShadowedBinderAndAbsentUsage(): Unit =
    val outer = target(
      "def f(x: U32) -> U32:\n  use(x)\n  let x = 1\n  use(<caret>x)\n"
    )
    val found = usages(outer)
    assertEquals(1, found.count(_.getElement.getText == "x"))
    val unused = target("def <caret>unused():\n  0\n")
    assertTrue(usages(unused).isEmpty)

  def testAliasesMembersAndSameNamesAcrossModules(): Unit =
    myFixture.addFileToProject("a.bend", "def value():\n  1\n")
    myFixture.addFileToProject("b.bend", "def value():\n  2\n")
    myFixture.addFileToProject(
      "other.bend",
      "import ./a.bend as X\ndef other():\n  X.value()\n"
    )
    val member = target(
      "import ./a.bend as A\nimport ./b.bend as B\ndef main():\n  A.<caret>value()\n  B.value()\n"
    )
    val memberUsages = usages(member)
    assertEquals(2, memberUsages.size)
    assertEquals(
      Set("A.value", "X.value"),
      memberUsages.map(_.getElement.getText).toSet
    )
    assertTrue(memberUsages.forall(_.getRangeInElement.getStartOffset == 2))
    val alias = target(
      "import ./a.bend as A\ndef main():\n  <caret>A.value()\n  A.value()\n"
    )
    val aliasUsages = usages(alias)
    assertEquals(2, aliasUsages.size)
    assertTrue(aliasUsages.forall(_.getRangeInElement.getLength == 1))

  def testLiteralDottedLawFillAndTypeConstructor(): Unit =
    val dotted = target(
      "def U32.add():\n  0\ndef main():\n  <caret>U32.add()\n"
    )
    assertEquals(1, usages(dotted).size)
    val law = target(
      "law claim:\n  {1 == 1 : U32}\ndef claim():\n  0\ndef main():\n  <caret>claim()\n"
    )
    assertEquals(2, usages(law).count(_.getElement.getText == "claim"))
    val datatype = target(
      "type Unit is Data:\n  Unit{}\ndef main(x: <caret>Unit) -> Unit:\n  Unit{}\n"
    )
    assertEquals(2, usages(datatype).size)
    val constructor = target(
      "type Unit is Data:\n  Unit{}\ndef main(x: Unit) -> Unit:\n  <caret>Unit{}\n"
    )
    assertEquals(1, usages(constructor).size)

  def testUnsavedImportedDocumentTakesPrecedence(): Unit =
    val lib = myFixture.addFileToProject("lib.bend", "def old():\n  0\n")
    myFixture.openFileInEditor(lib.getVirtualFile)
    WriteCommandAction.runWriteCommandAction(
      getProject,
      new Runnable:
        override def run(): Unit =
          myFixture.getEditor.getDocument.setText("def fresh():\n  0\n")
    )
    PsiDocumentManager.getInstance(getProject).commitAllDocuments()
    val imported = target(
      "import ./lib.bend as L\ndef main():\n  L.<caret>fresh()\n"
    )
    assertEquals(1, usages(imported).size)
    assertEquals("lib.bend", imported.getContainingFile.getName)

  def testLargeSavedFileUsesShortUnsavedDocument(): Unit =
    val saved = "def old():\n  0\n#" + ("x" * (1024 * 1024)) + "\n"
    val lib = myFixture.addFileToProject("large.bend", saved)
    myFixture.openFileInEditor(lib.getVirtualFile)
    WriteCommandAction.runWriteCommandAction(
      getProject,
      new Runnable:
        override def run(): Unit =
          myFixture.getEditor.getDocument.setText("def fresh():\n  0\n")
    )
    PsiDocumentManager.getInstance(getProject).commitAllDocuments()
    assertTrue(
      "Saved VFS size must exceed the search limit",
      lib.getVirtualFile.getLength > 1024 * 1024
    )
    val imported = target(
      "import ./large.bend as L\ndef main():\n  L.<caret>fresh()\n"
    )
    assertEquals(1, usages(imported).size)

  def testStandardHighlightUsagesAction(): Unit =
    target("def value():\n  0\ndef main():\n  <caret>value()\n  value()\n")
    myFixture.performEditorAction("HighlightUsagesInFile")
    val highlights =
      myFixture.getEditor.getMarkupModel.getAllHighlighters.toList
        .filter(_.getTextAttributesKey != null)
    val source = myFixture.getFile.getText
    val first = source.indexOf("value()", source.indexOf("def main"))
    val second = source.lastIndexOf("value()")
    assertTrue(
      "First resolved usage should be highlighted",
      highlights.exists(_.getStartOffset == first)
    )
    assertTrue(
      "Second resolved usage should be highlighted",
      highlights.exists(_.getStartOffset == second)
    )

  def testDeclarationOwnerAndForwardReference(): Unit =
    val forward = target("def main():\n  <caret>later()\ndef later():\n  0\n")
    assertEquals(1, usages(forward).size)
    myFixture.configureByText(
      "owner.bend",
      "def value():\n  0\ndef main():\n  value()\n"
    )
    val owner =
      PsiTreeUtil.findChildOfType(myFixture.getFile, classOf[BendDefinition])
    val provider = new BendFindUsagesProvider
    assertTrue(provider.canFindUsagesFor(owner))
    assertEquals("value", provider.getDescriptiveName(owner))
    assertEquals(1, usages(owner).size)

  def testLocalScopeRestrictsToSelectedSubtree(): Unit =
    val declaration = target(
      "def value():\n  0\ndef first():\n  <caret>value()\ndef second():\n  value()\n"
    )
    val file = myFixture.getFile
    val first = PsiTreeUtil
      .findChildrenOfType(file, classOf[BendDefinition])
      .asScala
      .find(_.getName == "first")
      .get
    val scoped = ReferencesSearch
      .search(declaration, new LocalSearchScope(Array[PsiElement](first)))
      .findAll()
      .asScala
      .toList
    assertEquals(1, scoped.size)
    assertEquals(
      first,
      PsiTreeUtil.getParentOfType(
        scoped.head.getElement,
        classOf[BendDefinition]
      )
    )

  def testUnusedLambdaBinderStillHasSourceIdentity(): Unit =
    myFixture.configureByText(
      "lambda.bend",
      "def main():\n  use(<caret>x => 1)\n"
    )
    val offset = myFixture.getEditor.getCaretModel.getOffset
    assertTrue(
      BendSourceSymbols.bindingDeclaredAt(myFixture.getFile, offset).nonEmpty
    )
    val binder = myFixture.getFile.findElementAt(offset)
    assertTrue(new BendFindUsagesProvider().canFindUsagesFor(binder))
    assertTrue(usages(binder).isEmpty)
