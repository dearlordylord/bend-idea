package com.dearlordylord.bend.idea.features.completion

import com.dearlordylord.bend.idea.symbols.references.BendForeignPathReference
import com.dearlordylord.bend.idea.syntax.psi.BendForeignPaths
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.*

final class BendForeignPathCompletionTest extends BasePlatformTestCase:
  def testForeignPathsAreDistinctFromModuleImportsAndOtherStrings(): Unit =
    val _ = myFixture.addFileToProject("ffi/one.c", "int one(void);\n")
    val _ = myFixture.addFileToProject("ffi/two.js", "export {};\n")
    val file = myFixture.addFileToProject(
      "pkg/main.bend",
      "import ../shared/module.bend as Shared\n" +
        "def external(value: U32) -> U32:\n" +
        "  import \"../ffi/one.c\"\n" +
        "  import \"../ffi/two.js\"\n" +
        "  import \"../ffi/missing.js\"\n" +
        "def textValue():\n  \"not-a-foreign.c\"\n"
    )
    val paths = BendForeignPaths.in(file)
    assertEquals(
      List("../ffi/one.c", "../ffi/two.js", "../ffi/missing.js"),
      paths.map(_.spelling)
    )
    paths.foreach { path =>
      assertEquals(
        path.spelling,
        file.getText
          .substring(path.range.getStartOffset, path.range.getEndOffset)
      )
    }
    val foreignReferences = file.getReferences.collect {
      case reference: BendForeignPathReference => reference
    }
    assertEquals(3, foreignReferences.length)
    assertNotNull(foreignReferences.head.resolve())
    assertNull(
      "A missing foreign asset remains unresolved",
      foreignReferences(2).resolve()
    )

  def testForeignPathCompletionUsesLocalCAndJavaScriptFiles(): Unit =
    val _ = myFixture.addFileToProject("ffi/one.c", "int one(void);\n")
    val _ = myFixture.addFileToProject("ffi/two.js", "export {};\n")
    myFixture.configureByText(
      "main.bend",
      "def external():\n  import \"ffi/<caret>\"\n"
    )
    myFixture.completeBasic()
    assertTrue(myFixture.getLookupElementStrings.contains("ffi/one.c"))
    assertTrue(myFixture.getLookupElementStrings.contains("ffi/two.js"))
    myFixture.finishLookup('\n')
    assertTrue(
      myFixture.getFile.getText,
      myFixture.getFile.getText.contains("import \"ffi/one.c\"")
    )

  def testLiteralTextOutsideForeignDefinitionDoesNotSuggestPaths(): Unit =
    val _ = myFixture.addFileToProject("ffi/one.c", "int one(void);\n")
    myFixture.configureByText(
      "main.bend",
      "def main():\n  \"ffi/<caret>\"\n"
    )
    myFixture.completeBasic()
    assertFalse(
      Option(myFixture.getLookupElementStrings)
        .exists(_.contains("ffi/one.c"))
    )

  def testReferencesTrackTheCurrentUnsavedForeignPath(): Unit =
    val _ = myFixture.addFileToProject("ffi/one.c", "int one(void);\n")
    val file = myFixture.addFileToProject(
      "pkg/main.bend",
      "def external():\n  import \"missing.c\"\n"
    )
    myFixture.openFileInEditor(file.getVirtualFile)
    WriteCommandAction.runWriteCommandAction(
      getProject,
      new Runnable:
        override def run(): Unit =
          myFixture.getEditor.getDocument.setText(
            "def external():\n  import \"../ffi/one.c\"\n"
          )
    )
    PsiDocumentManager.getInstance(getProject).commitAllDocuments()
    val updated = BendForeignPaths.in(file)
    assertEquals(List("../ffi/one.c"), updated.map(_.spelling))
    val reference = file.getReferences.collectFirst {
      case path: BendForeignPathReference => path
    }.get
    assertNotNull(reference.resolve())
