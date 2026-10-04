package com.dearlordylord.bend.idea.features.navigation

import com.intellij.codeInsight.navigation.actions.GotoTypeDeclarationAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.*

final class BendTypeNavigationTest extends BasePlatformTestCase:
  def testParameterUseNavigatesToImportedOuterAnnotationType(): Unit =
    val models = myFixture.addFileToProject(
      "models.bend",
      "type Item:\n  item{}\ntype Box<A>:\n  box{value: A}\n"
    )
    myFixture.configureByText(
      "main.bend",
      "import ./models.bend as M\ndef use(value: M.Box<M.Item>) -> M.Box<M.Item>:\n  va<caret>lue\n"
    )
    val targets = GotoTypeDeclarationAction.findSymbolTypes(
      myFixture.getEditor,
      myFixture.getCaretOffset
    )
    assertNotNull("Native Go to Type Declaration targets", targets)
    assertEquals(1, targets.length)
    assertEquals("Box", targets.head.getText)
    assertEquals(
      models.getVirtualFile,
      targets.head.getContainingFile.getVirtualFile
    )

  def testFunctionUseNavigatesPastNestedParameterTypesToResultHead(): Unit =
    myFixture.configureByText(
      "main.bend",
      "type Item:\n  item{}\ntype Box<A>:\n  box{value: A}\ndef make(value: Box<Box<Item>>) -> Box<Item>:\n  ?TODO\ndef main():\n  ma<caret>ke()\n"
    )
    val targets = GotoTypeDeclarationAction.findSymbolTypes(
      myFixture.getEditor,
      myFixture.getCaretOffset
    )
    assertNotNull(targets)
    assertEquals(List("Box"), targets.toList.map(_.getText))

  def testOwnAnnotationAndShadowedTypeParameterStayDistinct(): Unit =
    myFixture.configureByText(
      "main.bend",
      "type Item:\n  item{}\ndef use(Item: Item) -> Item:\n  It<caret>em\n"
    )
    val targets = GotoTypeDeclarationAction.findSymbolTypes(
      myFixture.getEditor,
      myFixture.getCaretOffset
    )
    assertEquals(List("Item"), targets.toList.map(_.getText))
    assertEquals("type Item", targets.head.getParent.getParent.getText.take(9))
    myFixture.configureByText(
      "main.bend",
      "type Item:\n  item{}\ndef use(Item: Type, value: Item):\n  va<caret>lue\n"
    )
    val shadowed = GotoTypeDeclarationAction.findSymbolTypes(
      myFixture.getEditor,
      myFixture.getCaretOffset
    )
    assertTrue(shadowed == null || shadowed.isEmpty)

  def testImportedTypeNavigationUsesCurrentUnsavedDeclaration(): Unit =
    val models =
      myFixture.addFileToProject("models.bend", "type Before:\n  before{}\n")
    myFixture.configureByText(
      "main.bend",
      "import ./models.bend as M\ndef use(value: M.After):\n  va<caret>lue\n"
    )
    val manager = PsiDocumentManager.getInstance(getProject)
    val document = manager.getDocument(models)
    WriteCommandAction.runWriteCommandAction(
      getProject,
      new Runnable:
        override def run(): Unit = document.setText("type After:\n  after{}\n")
    )
    manager.commitDocument(document)
    val targets = GotoTypeDeclarationAction.findSymbolTypes(
      myFixture.getEditor,
      myFixture.getCaretOffset
    )
    assertNotNull(targets)
    assertEquals(List("After"), targets.toList.map(_.getText))
    assertEquals(
      models.getVirtualFile,
      targets.head.getContainingFile.getVirtualFile
    )

  def testConstructorTypeAndUnavailableSourceAnnotations(): Unit =
    myFixture.configureByText(
      "main.bend",
      "type Item:\n  item{}\ndef main():\n  it<caret>em{}\n"
    )
    val constructor = GotoTypeDeclarationAction.findSymbolTypes(
      myFixture.getEditor,
      myFixture.getCaretOffset
    )
    assertNotNull(constructor)
    assertEquals(List("Item"), constructor.toList.map(_.getText))
    myFixture.configureByText(
      "main.bend",
      "def make():\n  Type\ndef main():\n  ma<caret>ke()\n"
    )
    val untyped = GotoTypeDeclarationAction.findSymbolTypes(
      myFixture.getEditor,
      myFixture.getCaretOffset
    )
    assertTrue(untyped == null || untyped.isEmpty)
    myFixture.configureByText(
      "main.bend",
      "import ./models.bend as M\ndef use(M: Type, value: M.Item):\n  va<caret>lue\n"
    )
    val shadowed = GotoTypeDeclarationAction.findSymbolTypes(
      myFixture.getEditor,
      myFixture.getCaretOffset
    )
    assertTrue(shadowed == null || shadowed.isEmpty)
