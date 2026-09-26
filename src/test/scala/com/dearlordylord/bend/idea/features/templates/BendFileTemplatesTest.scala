package com.dearlordylord.bend.idea.features.templates

import com.dearlordylord.bend.idea.syntax.psi.BendProofForm
import com.dearlordylord.bend.idea.syntax.psi.BendProofSurface
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.*

final class BendFileTemplatesTest extends BasePlatformTestCase:
  def testModuleTemplateUsesSelectedPathAndNavigatesToItsName(): Unit =
    val context = myFixture.addFileToProject("nested/context.bend", "")
    val directory = context.getContainingDirectory
    val module =
      BendFileTemplates.createModule(directory, "Arithmetic").toOption.get
    assertEquals("Arithmetic.bend", module.getName)
    assertEquals(
      "def Arithmetic() -> U32:\n  0\n",
      module.getText
    )
    BendFileTemplates.openAt(module, module.getText.indexOf("Arithmetic"))
    val editor = FileEditorManager.getInstance(getProject).getSelectedTextEditor
    assertNotNull(editor)
    assertEquals(
      module.getText.indexOf("Arithmetic"),
      editor.getCaretModel.getOffset
    )
    assertTrue(BendFileTemplates.createModule(directory, "Arithmetic").isLeft)
    assertEquals("def Arithmetic() -> U32:\n  0\n", module.getText)

  def testLawProofPairLinksImportsAndLeavesNavigableHole(): Unit =
    val context = myFixture.addFileToProject("proofs/context.bend", "")
    val (laws, proof) =
      BendFileTemplates
        .createLawProofPair(context.getContainingDirectory)
        .toOption
        .get
    PsiDocumentManager.getInstance(getProject).commitAllDocuments()
    assertEquals("LAWS.bend", laws.getName)
    assertEquals(
      "law claim:\n  {1 == 1 : U32}\n",
      laws.getText
    )
    assertEquals(
      "import ./LAWS.bend as Laws\ndef Laws.claim():\n  ?TODO\n",
      proof.getText
    )
    assertTrue(
      proof.getReferences.exists(reference => reference.resolve() == laws)
    )
    assertTrue(
      BendProofSurface
        .scan(proof.getText)
        .exists {
          case BendProofForm.Hole("TODO", _, _) => true
          case _                                => false
        }
    )
    val hole = proof.getText.indexOf("?TODO") + 1
    BendFileTemplates.openAt(proof, hole)
    val editor = FileEditorManager.getInstance(getProject).getSelectedTextEditor
    assertNotNull(editor)
    assertEquals(hole, editor.getCaretModel.getOffset)

  def testPairCollisionRefusesPartialCreation(): Unit =
    val blocker = myFixture.addFileToProject(
      "collision/PROOF.bend",
      "def sentinel() -> U32:\n  1\n"
    )
    val result = BendFileTemplates.createLawProofPair(
      blocker.getContainingDirectory
    )
    assertTrue(result.isLeft)
    assertNull(
      blocker.getContainingDirectory.findFile("LAWS.bend")
    )
    assertEquals("def sentinel() -> U32:\n  1\n", blocker.getText)
