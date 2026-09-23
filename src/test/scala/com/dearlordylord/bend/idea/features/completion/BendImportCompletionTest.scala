package com.dearlordylord.bend.idea.features.completion

import com.dearlordylord.bend.idea.toolchain.api.*
import com.dearlordylord.bend.idea.symbols.api.{BendImportedSymbolCatalog, BendSourceResolution, BendSourceSymbols, BendSymbolCategory}
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.*
import java.nio.file.{Files, Path}

final class BendImportCompletionTest extends BasePlatformTestCase:
  private var original: BendToolchainChoices = null
  private var temporary: Path = null

  override def setUp(): Unit =
    super.setUp()
    val settings = ApplicationManager.getApplication.getService(classOf[BendToolchainSettings])
    original = settings.choices
    temporary = Files.createTempDirectory("bend-import-test")
    settings.update(BendToolchainChoices(baseSource = temporary.resolve("base.bend").toString,
      packageCache = temporary.resolve("cache").toString))

  override def tearDown(): Unit =
    try
      ApplicationManager.getApplication.getService(classOf[BendToolchainSettings]).update(original)
      val files = Files.walk(temporary)
      try files.sorted(java.util.Comparator.reverseOrder()).forEach(path => Files.deleteIfExists(path))
      finally files.close()
    finally super.tearDown()

  private def items(source: String): Array[LookupElement] =
    myFixture.addFileToProject("lib.bend", "# Imported comment\ndef Nat.add(x: Nat) -> Nat:\n  x\ndef plain():\n  0\n")
    myFixture.configureByText("main.bend", source)
    Option(myFixture.completeBasic()).getOrElse(Array.empty[LookupElement])

  def testDirectAliasAndDottedSuffix(): Unit =
    val all = items("import ./lib.bend as L\ndef main():\n  L.Nat.ad<caret>d.extra")
    val add = all.find(_.getLookupString == "add").get
    myFixture.getLookup.setCurrentItem(add)
    myFixture.finishLookup('\t')
    assertTrue(myFixture.getEditor.getDocument.getText, myFixture.getEditor.getDocument.getText.endsWith("L.Nat.add.extra"))

  def testAliasIsNotUnqualifiedOrReExported(): Unit =
    myFixture.addFileToProject("child.bend", "def child():\n  0\n")
    myFixture.addFileToProject("lib.bend", "import ./child.bend as C\ndef own():\n  0\n")
    myFixture.configureByText("main.bend", "import ./lib.bend as L\ndef main():\n  L.<caret>")
    val all = Option(myFixture.completeBasic()).getOrElse(Array.empty[LookupElement])
    assertTrue(all.exists(_.getLookupString == "own"))
    assertFalse(all.exists(_.getLookupString == "child"))
    assertFalse(all.exists(_.getLookupString == "C"))

  def testImportedConstructorIsAtFileScope(): Unit =
    myFixture.addFileToProject("lib.bend", "type Choice is Data:\n  Yes{}\n  No{}\n")
    myFixture.configureByText("main.bend", "import ./lib.bend as L\ndef main():\n  L.Ye<caret>")
    assertTrue(Option(myFixture.completeBasic()).getOrElse(Array.empty[LookupElement])
      .exists(_.getLookupString == "Yes") ||
      myFixture.getEditor.getDocument.getText.endsWith("L.Yes"))

  def testDirectAliasCompletesAsModuleName(): Unit =
    myFixture.addFileToProject("lib.bend", "def own():\n  0\n")
    myFixture.configureByText("main.bend", "import ./lib.bend as Library\ndef main():\n  Lib<caret>")
    assertTrue(Option(myFixture.completeBasic()).getOrElse(Array.empty[LookupElement])
      .exists(_.getLookupString == "Library") ||
      myFixture.getEditor.getDocument.getText.endsWith("Library"))

  def testCurrentFileDeclarationWinsOverImportedQualifiedName(): Unit =
    val lib = myFixture.addFileToProject("lib.bend", "def own(x: U32) -> U32:\n  x\n")
    myFixture.configureByText("main.bend",
      "import ./lib.bend as L\ndef L.own():\n  0\ndef main():\n  L.ow<caret>")
    val catalog = getProject.getService(classOf[BendImportedSymbolCatalog])
    val selection = ApplicationManager.getApplication.getService(classOf[BendToolchainSettings]).selection
    catalog.resolve(myFixture.getFile, myFixture.getEditor.getCaretModel.getOffset,
      "L.own", selection.baseSource, selection.packageCache) match
      case BendSourceResolution.Resolved(symbol) =>
        assertEquals(BendSourceSymbols.fileId(lib), symbol.handle.file)
        assertEquals(BendSymbolCategory.Definition, symbol.category)
        assertTrue(symbol.signature.source.contains("x: U32"))
      case other => fail(s"Expected imported target for alias collision, got $other")
    val matches = Option(myFixture.completeBasic()).getOrElse(Array.empty[LookupElement])
      .filter(_.getLookupString == "own")
    assertTrue(matches.length == 1 ||
      myFixture.getEditor.getDocument.getText.endsWith("L.own"))

  def testAliasFallsBackToLiteralDottedDeclarationWhenTargetAbsent(): Unit =
    myFixture.addFileToProject("lib.bend", "def other():\n  0\n")
    myFixture.configureByText("main.bend",
      "import ./lib.bend as L\ndef L.own():\n  0\ndef main():\n  L.ow<caret>")
    val selection = ApplicationManager.getApplication.getService(classOf[BendToolchainSettings]).selection
    val resolution = getProject.getService(classOf[BendImportedSymbolCatalog])
      .resolve(myFixture.getFile, myFixture.getEditor.getCaretModel.getOffset,
        "L.own", selection.baseSource, selection.packageCache)
    resolution match
      case BendSourceResolution.Resolved(symbol) =>
        assertEquals(BendSourceSymbols.fileId(myFixture.getFile), symbol.handle.file)
      case other => fail(s"Expected literal dotted fallback, got $other")

  def testDirectAliasTargetWinsOverLiteralBaseName(): Unit =
    Files.writeString(temporary.resolve("base.bend"), "def L.own():\n  0\n")
    val lib = myFixture.addFileToProject("lib.bend",
      "def own(x: U32) -> U32:\n  x\ndef older():\n  0\n")
    myFixture.configureByText("main.bend",
      "import Base\nimport ./lib.bend as L\ndef main():\n  L.o<caret>")
    val selection = ApplicationManager.getApplication.getService(classOf[BendToolchainSettings]).selection
    val catalog = getProject.getService(classOf[BendImportedSymbolCatalog])
    catalog.resolve(myFixture.getFile, myFixture.getEditor.getCaretModel.getOffset,
      "L.own", selection.baseSource, selection.packageCache) match
      case BendSourceResolution.Resolved(symbol) =>
        assertEquals(BendSourceSymbols.fileId(lib), symbol.handle.file)
      case other => fail(s"Expected direct alias target over Base, got $other")
    val matches = Option(myFixture.completeBasic()).getOrElse(Array.empty[LookupElement])
      .filter(_.getLookupString == "own")
    assertEquals(1, matches.length)

  def testLastDirectImportOwnsDuplicateAlias(): Unit =
    myFixture.addFileToProject("first.bend", "def first():\n  0\n")
    myFixture.addFileToProject("second.bend", "def second():\n  0\ndef secondMore():\n  0\n")
    myFixture.configureByText("main.bend",
      "import ./first.bend as M\nimport ./second.bend as M\ndef main():\n  M.<caret>")
    val names = Option(myFixture.completeBasic()).getOrElse(Array.empty[LookupElement])
      .map(_.getLookupString).toSet
    assertFalse(names.contains("first"))
    assertTrue(names.contains("second"))
    assertTrue(names.contains("secondMore"))

  def testUnsavedDependencyWinsAndInvalidatesParsedSymbols(): Unit =
    val lib = myFixture.addFileToProject("lib.bend", "def old():\n  0\n")
    myFixture.configureByText("main.bend", "import ./lib.bend as L\ndef main():\n  L.ol<caret>")
    assertTrue(Option(myFixture.completeBasic()).getOrElse(Array.empty[LookupElement])
      .exists(_.getLookupString == "old") ||
      myFixture.getEditor.getDocument.getText.endsWith("L.old"))
    val document = FileDocumentManager.getInstance().getDocument(lib.getVirtualFile)
    WriteCommandAction.runWriteCommandAction(getProject, new Runnable:
      override def run(): Unit = document.setText("def newName(x: U32) -> U32:\n  x\n"))
    myFixture.configureByText("main.bend", "import ./lib.bend as L\ndef main():\n  L.new<caret>")
    assertTrue(Option(myFixture.completeBasic()).getOrElse(Array.empty[LookupElement])
      .exists(_.getLookupString == "newName") ||
      myFixture.getEditor.getDocument.getText.endsWith("L.newName"))

  def testTransitiveBaseContributesEmptyNamespace(): Unit =
    Files.writeString(temporary.resolve("base.bend"), "def Nat.base():\n  0\n")
    myFixture.addFileToProject("lib.bend", "import Base\ndef own():\n  0\n")
    myFixture.configureByText("main.bend", "import ./lib.bend as L\ndef main():\n  Nat.ba<caret>")
    assertTrue(Option(myFixture.completeBasic()).getOrElse(Array.empty[LookupElement])
      .exists(_.getLookupString == "base") ||
      myFixture.getEditor.getDocument.getText.endsWith("Nat.base"))

  def testDottedCompletionOnlyOffersDeclaredStaticNames(): Unit =
    Files.writeString(temporary.resolve("base.bend"), "def Nat.add():\n  0\n")
    myFixture.configureByText("main.bend", "import Base\ndef main(value):\n  value.ad<caret>")
    assertFalse(Option(myFixture.completeBasic()).getOrElse(Array.empty[LookupElement])
      .exists(_.getLookupString == "add"))
