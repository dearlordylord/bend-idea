package com.dearlordylord.bend.idea.features.completion

import com.dearlordylord.bend.idea.toolchain.api.*
import com.dearlordylord.bend.idea.workspace.api.{
  BendBaseState,
  BendLibrarySource
}
import com.dearlordylord.bend.idea.symbols.api.BendBaseSymbolCatalog
import com.intellij.codeInsight.lookup.{
  LookupElement,
  LookupElementPresentation
}
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.*
import java.nio.file.{Files, Path}

final class BendBaseCompletionTest extends BasePlatformTestCase:
  private var original: BendToolchainChoices = null
  private var temporary: Path = null

  private def settings: BendToolchainSettings =
    ApplicationManager.getApplication.getService(classOf[BendToolchainSettings])

  override def setUp(): Unit =
    super.setUp()
    original = settings.choices
    temporary = Files.createTempDirectory("bend-base-test")

  override def tearDown(): Unit =
    try
      settings.update(original)
      if temporary != null then
        val files = Files.walk(temporary)
        try
          files
            .sorted(java.util.Comparator.reverseOrder())
            .forEach(path => Files.deleteIfExists(path))
        finally files.close()
    finally super.tearDown()

  private def base(name: String, text: String): Path =
    val path = temporary.resolve(name)
    Files.writeString(path, text)
    path

  private def select(path: Path): Unit =
    settings.update(BendToolchainChoices(baseSource = path.toString))

  private def items(source: String): Array[LookupElement] =
    myFixture.configureByText("current.bend", source)
    Option(myFixture.completeBasic()).getOrElse(Array.empty[LookupElement])

  private def rendered(item: LookupElement): LookupElementPresentation =
    val presentation = new LookupElementPresentation()
    item.renderElement(presentation)
    presentation

  def testAlternateBaseSourceAndSignatureComments(): Unit =
    val first = base(
      "first.bend",
      "# First implementation\ndef Nat.plus(x: Nat) -> Nat:\n  x\ndef Nat.please():\n  0\n"
    )
    val second = base(
      "second.bend",
      "# Alternate source\ndef Nat.plus(y: U32) -> U32:\n  y\ndef Nat.please():\n  0\n"
    )
    select(first)
    val one = items("import Base\ndef main():\n  Nat.pl<caret>")
      .find(_.getLookupString == "plus")
      .get
    assertTrue(rendered(one).getTailText.contains("x: Nat"))
    assertTrue(rendered(one).getTailText.contains("First implementation"))
    select(second)
    val two = items("import Base\ndef main():\n  Nat.pl<caret>")
      .find(_.getLookupString == "plus")
      .get
    assertTrue(rendered(two).getTailText.contains("y: U32"))
    assertTrue(rendered(two).getTailText.contains("Alternate source"))
    assertFalse(rendered(two).getTailText.contains("First implementation"))

  def testSourceUpdateInvalidatesCandidatesAndSignature(): Unit =
    val path = base(
      "updated.bend",
      "def Nat.old(x: Nat) -> Nat:\n  x\ndef Nat.older():\n  0\n"
    )
    select(path)
    assertTrue(
      items("import Base\ndef main():\n  Nat.ol<caret>").exists(
        _.getLookupString == "old"
      )
    )
    Files.writeString(
      path,
      "def Nat.new(y: U32) -> U32:\n  y\ndef Nat.newer():\n  0\n"
    )
    val changed = items("import Base\ndef main():\n  Nat.ne<caret>")
    val found = changed.find(_.getLookupString == "new").get
    assertTrue(rendered(found).getTailText.contains("y: U32"))
    assertFalse(changed.exists(_.getLookupString == "old"))

  def testCatalogIsBoundedAndUnsavedBaseDocumentWins(): Unit =
    val path = base("buffer.bend", "def Nat.first():\n  0\n")
    select(path)
    val sourceService = getProject.getService(classOf[BendLibrarySource])
    val catalog = getProject.getService(classOf[BendBaseSymbolCatalog])
    def snapshot = sourceService
      .base(path.toString, settings.selection.configurationRevision)
      .asInstanceOf[BendBaseState.Available]
      .source
    val before = catalog.declarations(snapshot)
    assertTrue(before eq catalog.declarations(snapshot))
    val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)
    val document = FileDocumentManager.getInstance().getDocument(file)
    WriteCommandAction.runWriteCommandAction(
      getProject,
      new Runnable:
        override def run(): Unit =
          document.setText("def Nat.second(x: U32) -> U32:\n  x\n")
    )
    assertTrue(
      items("import Base\ndef main():\n  Nat.se<caret>").exists(
        _.getLookupString == "second"
      ) ||
        myFixture.getEditor.getDocument.getText.endsWith("Nat.second")
    )
    val changed = catalog.declarations(snapshot)
    assertNotSame(before, changed)
    assertEquals("Nat.second", changed.head.name)
    WriteCommandAction.runWriteCommandAction(
      getProject,
      new Runnable:
        override def run(): Unit = document.setText("def Nat.first():\n  0\n")
    )

  def testMissingBaseKeepsCurrentFileNamesAndShowsSetup(): Unit =
    val missing = temporary.resolve("absent.bend")
    select(missing)
    val results = items("def local():\n  0\ndef main():\n  lo<caret>")
    assertTrue(results.exists(_.getLookupString == "local"))
    assertTrue(results.exists(_.getLookupString == "Configure Bend Base…"))
    assertEquals(
      BendBaseState.Missing(missing.toString),
      getProject
        .getService(classOf[BendLibrarySource])
        .base(
          settings.selection.baseSource,
          settings.selection.configurationRevision
        )
    )

  def testDottedCompletionReplacesExistingSuffixOnly(): Unit =
    select(base("dot.bend", "def Nat.add(x: Nat) -> Nat:\n  x\n"))
    val found = items("import Base\ndef main():\n  Nat.ad<caret>d.extra")
      .find(_.getLookupString == "add")
      .get
    myFixture.getLookup.setCurrentItem(found)
    myFixture.finishLookup('\t')
    assertTrue(
      myFixture.getEditor.getDocument.getText.endsWith("  Nat.add.extra")
    )

  def testDeclarationHeaderDoesNotOfferBaseMembers(): Unit =
    select(base("header.bend", "def Nat.plus():\n  0\n"))
    assertFalse(
      items("import Base\ndef Nat.pl<caret>").exists(
        _.getLookupString == "plus"
      )
    )

  def testCurrentFileWinsByNameAndCategoryWithoutMergingKinds(): Unit =
    select(
      base("shadow.bend", "type Unit is Data:\n  Unit{}\ndef local():\n  0\n")
    )
    val matches = items(
      "import Base\ntype Unit is Data:\n  Unit{}\ndef main():\n  Un<caret>"
    )
      .filter(_.getLookupString == "Unit")
    assertEquals(2, matches.length)
    assertEquals(
      Set("datatype", "constructor"),
      matches.map(rendered(_).getTypeText).toSet
    )

  def testBaseIsOnlyOfferedWhenImported(): Unit =
    select(
      base("visibility.bend", "def Nat.plus():\n  0\ndef Nat.please():\n  0\n")
    )
    assertFalse(
      items("def main():\n  Nat.pl<caret>").exists(_.getLookupString == "plus")
    )
    assertFalse(
      items("# import Base\ndef main():\n  Nat.pl<caret>").exists(
        _.getLookupString == "plus"
      )
    )
    assertTrue(
      items("import Base # available\ndef main():\n  Nat.pl<caret>").exists(
        _.getLookupString == "plus"
      )
    )
    assertFalse(
      items("def earlier():\n  0\nimport Base\ndef main():\n  Nat.pl<caret>")
        .exists(_.getLookupString == "plus")
    )

  def testBaseLawAndFillShareOneCompletionCandidate(): Unit =
    select(
      base(
        "laws.bend",
        "law Equal.cong:\n  for n: Nat\n  {n == n : Nat}\n" +
          "def Equal.cong(n):\n  n\ndef Equal.compare():\n  0\n"
      )
    )
    val found = items("import Base\ndef main():\n  Equal.co<caret>")
      .filter(_.getLookupString == "cong")
    assertEquals(1, found.length)
    assertTrue(rendered(found.head).getTypeText.contains("law"))

  def testDefaultsHomeExpansionAndUnverifiedCompatibility(): Unit =
    val defaults = BendToolchainPaths.resolve(
      BendToolchainChoices(),
      "/home/tester",
      None,
      4
    )
    assertEquals("/home/tester/.bend/bin/bend", defaults.executable)
    assertEquals("/home/tester/.bend/bend2/base.bend", defaults.baseSource)
    assertEquals("/home/tester/.bend/lib", defaults.packageCache)
    assertTrue(defaults.diagnosticsEnabled)
    assertEquals(BendBaseCompatibility.Unverified, defaults.baseCompatibility)
    val overridePaths = BendToolchainPaths.resolve(
      BendToolchainChoices(
        executable = "~/bin/bend",
        baseSource = "~/lib/base.bend"
      ),
      "/home/tester",
      Some("~/cache"),
      5
    )
    assertEquals("/home/tester/bin/bend", overridePaths.executable)
    assertEquals("/home/tester/lib/base.bend", overridePaths.baseSource)
    assertEquals("/home/tester/cache", overridePaths.packageCache)
