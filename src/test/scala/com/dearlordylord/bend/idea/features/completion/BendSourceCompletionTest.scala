package com.dearlordylord.bend.idea.features.completion

import com.dearlordylord.bend.idea.symbols.api.{
  BendSourceSymbols,
  BendSymbolCategory,
  BendSourceResolution
}
import com.intellij.codeInsight.lookup.{
  LookupElement,
  LookupElementPresentation
}
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.*

final class BendSourceCompletionTest extends BasePlatformTestCase:
  private def items(source: String): Array[LookupElement] =
    myFixture.configureByText("current.bend", source)
    Option(myFixture.completeBasic()).getOrElse(Array.empty[LookupElement])

  private def contains(source: String, name: String): Boolean =
    val found = items(source).exists(_.getLookupString == name)
    val editor = myFixture.getEditor
    found || editor.getDocument.getText
      .substring(0, editor.getCaretModel.getOffset)
      .endsWith(name)

  def testDeclarationOrderAndUnsavedEdits(): Unit =
    assertFalse(
      contains("def later():\n  0\ndef main():\n  lat<caret>", "future")
    )
    assertTrue(
      contains(
        "def earlier():\n  0\ndef main():\n  ear<caret>\ndef later():\n  0",
        "earlier"
      )
    )
    assertFalse(
      contains("def main():\n  lat<caret>\ndef later():\n  0", "later")
    )
    myFixture.configureByText("edited.bend", "def main():\n  cal<caret>")
    assertFalse(
      Option(myFixture.completeBasic())
        .getOrElse(Array.empty[LookupElement])
        .exists(_.getLookupString == "calculate")
    )
    val document = myFixture.getEditor.getDocument
    com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(
      getProject,
      new Runnable:
        override def run(): Unit =
          document.insertString(0, "def calculate(x: Nat) -> Nat:\n  x\n")
    )
    PsiDocumentManager.getInstance(getProject).commitDocument(document)
    val matches = Option(myFixture.completeBasic())
      .getOrElse(Array.empty[LookupElement])
      .exists(_.getLookupString == "calculate")
    assertTrue(
      matches || document.getText
        .substring(0, myFixture.getEditor.getCaretModel.getOffset)
        .endsWith("calculate")
    )

  def testMultilineDottedSignatureAndComments(): Unit =
    val source =
      "def U32.march():\n  0\n# Maps each value\n# without loading imports\ndef U32.map(\n  ~f: U32 -> U32,\n  +xs: List<&2, U32>\n) -> List<&2, U32>:\n  U32.ma<caret>\n"
    val matches = items(source).filter(_.getLookupString == "U32.map")
    assertEquals(1, matches.length)
    val presentation = new LookupElementPresentation()
    matches(0).renderElement(presentation)
    assertTrue(presentation.getTailText.contains("~f: U32 -> U32"))
    assertTrue(presentation.getTailText.contains("+xs: List<&2, U32>"))
    assertTrue(
      presentation.getTailText.contains(
        "Maps each value without loading imports"
      )
    )
    val symbol = BendSourceSymbols
      .declarations(myFixture.getFile)
      .find(_.name == "U32.map")
      .get
    assertEquals("Maps each value\nwithout loading imports", symbol.comments)
    assertEquals(List("f", "xs"), symbol.signature.parameters.map(_.name))
    assertTrue(symbol.signature.source.contains("~f"))

  def testSameSpellingTypeAndConstructorHaveDistinctIdentity(): Unit =
    val suggestions = items(
      "type Unit is Data:\n  Unit{}\ndef main():\n  Un<caret>"
    ).filter(_.getLookupString == "Unit")
    assertEquals(2, suggestions.length)
    assertEquals(
      Set("datatype", "constructor"),
      suggestions.map { item =>
        val p = new LookupElementPresentation()
        item.renderElement(p)
        p.getTypeText
      }.toSet
    )
    val symbols =
      BendSourceSymbols.declarations(myFixture.getFile).filter(_.name == "Unit")
    assertEquals(2, symbols.size)
    assertEquals(
      Set(BendSymbolCategory.Datatype, BendSymbolCategory.Constructor),
      symbols.map(_.category).toSet
    )
    assertNotEquals(symbols.head.handle, symbols.last.handle)
    assertEquals("Unit{}", symbols.last.signature.source)
    val offset = myFixture.getEditor.getCaretModel.getOffset
    assertTrue(
      BendSourceSymbols
        .resolveCurrentFile(myFixture.getFile, offset, "Unit")
        .isInstanceOf[BendSourceResolution.Ambiguous]
    )
    assertTrue(
      BendSourceSymbols
        .resolveCurrentFile(
          myFixture.getFile,
          offset,
          "Unit",
          Some(BendSymbolCategory.Datatype)
        )
        .isInstanceOf[BendSourceResolution.Resolved]
    )

  def testMalformedNeighborsRecoverAtNextDeclaration(): Unit =
    val source =
      "def broken(x: List<U32>\n  ignored\ndef good() -> U32:\n  1\ntype Bad is Data:\n  Broken{field: U32\ndef later():\n  go<caret>"
    assertTrue(contains(source, "good"))
    val names = BendSourceSymbols.declarations(myFixture.getFile).map(_.name)
    assertTrue(names.contains("good"))
    assertTrue(names.contains("later"))
    assertEquals(names.size, names.distinct.size)

  def testPlainNameInsertionDoesNotForceCall(): Unit =
    val matches = items(
      "def value(x: U32) -> U32:\n  x\ndef valuable():\n  0\ndef main():\n  val<caret>"
    )
      .filter(_.getLookupString == "value")
    assertEquals(1, matches.length)
    myFixture.getLookup.setCurrentItem(matches.head)
    myFixture.finishLookup('\t')
    assertTrue(myFixture.getEditor.getDocument.getText.endsWith("  value"))

  def testLiteralDottedNameAfterDotAndNativeRename(): Unit =
    val found = items(
      "def U32.add():\n  0\ndef U32.and():\n  0\ndef main():\n  U32.<caret>"
    )
    assertTrue(found.exists(_.getLookupString == "U32.add"))
    assertTrue(found.exists(_.getLookupString == "U32.and"))
    assertFalse(found.exists(_.getLookupString == "match"))
    myFixture.getLookup.setCurrentItem(
      found.find(_.getLookupString == "U32.add").get
    )
    myFixture.finishLookup('\t')
    assertTrue(myFixture.getEditor.getDocument.getText.endsWith("  U32.add"))
    val declaration = BendSourceSymbols
      .declarations(myFixture.getFile)
      .find(_.name == "U32.add")
      .get
      .declaration
    assertEquals("U32.add", declaration.getNameIdentifier.getText)
    com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(
      getProject,
      new Runnable:
        override def run(): Unit = declaration.setName("U32.plus")
    )
    assertTrue(myFixture.getFile.getText.startsWith("def U32.plus():"))
    try
      declaration.setName("def")
      fail("Reserved declaration name should be rejected")
    catch case _: com.intellij.util.IncorrectOperationException => ()

  def testLawSourceSignatureAndConstructorFields(): Unit =
    items(
      "law same:\n  for -A: Type\n  {A == A : Type}\ntype Pair is Data:\n  Pair{left: Nat, +right: Nat}\ndef main():\n  Pa<caret>"
    )
    val symbols = BendSourceSymbols.declarations(myFixture.getFile)
    val law = symbols.find(_.name == "same").get
    assertTrue(law.signature.source.contains("for -A: Type"))
    val ctor = symbols
      .find(s =>
        s.name == "Pair" && s.category == BendSymbolCategory.Constructor
      )
      .get
    assertEquals(List("left", "right"), ctor.signature.parameters.map(_.name))
    assertEquals(
      List("left: Nat", "+right: Nat"),
      ctor.signature.parameters.map(_.source)
    )

  def testCommentsRequireSameIndentation(): Unit =
    items(
      "def old():\n  # Body comment\ndef next():\n  0\n# Real comment\ndef final():\n  0\ndef main():\n  fi<caret>"
    )
    val declarations = BendSourceSymbols.declarations(myFixture.getFile)
    assertEquals("", declarations.find(_.name == "next").get.comments)
    assertEquals(
      "Real comment",
      declarations.find(_.name == "final").get.comments
    )

  def testSourceFileIdentityAvoidsBasenameCollisions(): Unit =
    val first = myFixture.addFileToProject("one/Same.bend", "def a():\n  0")
    val second = myFixture.addFileToProject("two/Same.bend", "def b():\n  0")
    assertNotEquals(
      BendSourceSymbols.fileId(first),
      BendSourceSymbols.fileId(second)
    )
    val factory = com.intellij.psi.PsiFileFactory.getInstance(getProject)
    val a = factory.createFileFromText(
      "buffer.bend",
      com.dearlordylord.bend.idea.syntax.BendLanguage.instance,
      "def a():\n  0"
    )
    val b = factory.createFileFromText(
      "buffer.bend",
      com.dearlordylord.bend.idea.syntax.BendLanguage.instance,
      "def b():\n  0"
    )
    assertNotEquals(BendSourceSymbols.fileId(a), BendSourceSymbols.fileId(b))
    assertEquals(BendSourceSymbols.fileId(a), BendSourceSymbols.fileId(a))

  def testSpacedComparisonReturnTypeKeepsHeaderBoundary(): Unit =
    items(
      "def less(a: Nat, b: Nat) -> {a < b : Nat}:\n  ?TODO\ndef next():\n  0\ndef main():\n  le<caret>"
    )
    val less = BendSourceSymbols
      .declarations(myFixture.getFile)
      .find(_.name == "less")
      .get
    assertEquals(
      "def less(a: Nat, b: Nat) -> {a < b : Nat}:",
      less.signature.source
    )
    assertTrue(
      BendSourceSymbols.declarations(myFixture.getFile).exists(_.name == "next")
    )

  def testAdjacentComparisonDoesNotConsumeHeader(): Unit =
    items(
      "def less(a: Nat, b: Nat) -> {a<b : Nat}:\n  ?TODO\ndef next():\n  0\ndef main():\n  le<caret>"
    )
    val less = BendSourceSymbols
      .declarations(myFixture.getFile)
      .find(_.name == "less")
      .get
    assertEquals(
      "def less(a: Nat, b: Nat) -> {a<b : Nat}:",
      less.signature.source
    )
    assertTrue(
      BendSourceSymbols.declarations(myFixture.getFile).exists(_.name == "next")
    )
