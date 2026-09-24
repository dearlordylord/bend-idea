package com.dearlordylord.bend.idea.features.documentation

import com.dearlordylord.bend.idea.toolchain.api.{
  BendToolchainChoices,
  BendToolchainSettings
}
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.{PsiDocumentManager, PsiManager}
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.*
import java.nio.file.{Files, Path}

final class BendDocumentationTest extends BasePlatformTestCase:
  private var original: BendToolchainChoices = null
  private var temporary: Path = null

  override def setUp(): Unit =
    super.setUp()
    val settings = ApplicationManager.getApplication.getService(
      classOf[BendToolchainSettings]
    )
    original = settings.choices
    temporary = Files.createTempDirectory("bend-docs")
    settings.update(
      BendToolchainChoices(baseSource = temporary.resolve("base.bend").toString)
    )

  override def tearDown(): Unit =
    try
      ApplicationManager.getApplication
        .getService(classOf[BendToolchainSettings])
        .update(original)
      val stream = Files.walk(temporary)
      try
        stream
          .sorted(java.util.Comparator.reverseOrder())
          .forEach(path => {
            val _ = Files.deleteIfExists(path)
          })
      finally stream.close()
    finally super.tearDown()

  private def docs(source: String): String =
    myFixture.configureByText("main.bend", source)
    val offset = myFixture.getEditor.getCaretModel.getOffset
    val file = myFixture.getFile
    val provider = new BendDocumentationProvider
    val element = provider.getCustomDocumentationElement(
      myFixture.getEditor,
      file,
      file.findElementAt(offset),
      offset
    )
    if element == null then null
    else provider.generateDoc(element, file.findElementAt(offset))

  def testLocalSignatureCommentsAndLink(): Unit =
    val html = docs(
      "# Explain & map\ndef map(~f: U32 -> U32, +xs: List<&2, U32>) -> List<&2, U32>:\n  xs\ndef main():\n  <caret>map(0, 0)\n"
    )
    assertNotNull(html)
    assertTrue(html.contains("~f: U32 -&gt; U32"))
    assertTrue(html.contains("+xs: List&lt;&amp;2, U32&gt;"))
    assertTrue(html.contains("Explain &amp; map"))
    assertTrue(html.contains("bend-doc:"))

  def testImportedAndUnsavedSource(): Unit =
    val lib = myFixture.addFileToProject(
      "lib.bend",
      "# Old\ndef value(x: U32) -> U32:\n  x\n"
    )
    myFixture.openFileInEditor(lib.getVirtualFile)
    WriteCommandAction.runWriteCommandAction(
      getProject,
      new Runnable:
        override def run(): Unit = myFixture.getEditor.getDocument.setText(
          "# New\ndef value(+x: U32) -> U32:\n  x\n"
        )
    )
    PsiDocumentManager.getInstance(getProject).commitAllDocuments()
    val html = docs(
      "import ./lib.bend as L\ndef main():\n  L.<caret>value(1)\n"
    )
    assertTrue(html.contains("New"))
    assertTrue(html.contains("+x: U32"))
    assertFalse(html.contains("Old"))
    val url = "href='([^']+)'".r.findFirstMatchIn(html).get.group(1)
    val target = new BendDocumentationProvider().getDocumentationElementForLink(
      PsiManager.getInstance(getProject),
      url,
      myFixture.getFile.findElementAt(
        myFixture.getEditor.getCaretModel.getOffset
      )
    )
    assertEquals(lib.getVirtualFile, target.getContainingFile.getVirtualFile)

  def testBaseAndCategoryDistinction(): Unit =
    Files.writeString(
      temporary.resolve("base.bend"),
      "# Base help\ndef fromBase():\n  0\n"
    )
    assertTrue(
      docs("import Base\ndef main():\n  <caret>fromBase()\n").contains(
        "Base help"
      )
    )
    val datatype = docs(
      "type Unit is Data:\n  Unit{}\ndef main(x: <caret>Unit) -> Unit:\n  x\n"
    )
    val constructor = docs(
      "type Unit is Data:\n  Unit{}\ndef main():\n  <caret>Unit{}\n"
    )
    assertTrue(datatype.contains("Datatype"))
    assertTrue(constructor.contains("Constructor"))

  def testLawFillShowsSpecificationAndImplementationNames(): Unit =
    val html = docs(
      "# Statement\nlaw claim:\n  for -n: Nat\n  {n == n : Nat}\n# Proof\ndef claim(value):\n  value\ndef main():\n  <caret>claim(1)\n"
    )
    assertTrue(html.contains("def claim(value)"))
    assertTrue(html.contains("Proof"))
    assertTrue(html.contains("for -n: Nat"))
    assertTrue(html.contains("Law specification"))
    assertTrue(html.contains("Declaration</a>"))
    assertTrue(html.contains("Implementation</a>"))
    val provider = new BendDocumentationProvider
    val context = myFixture.getFile.findElementAt(
      myFixture.getEditor.getCaretModel.getOffset
    )
    val links = "href='([^']+)'>([^<]+)</a>".r
      .findAllMatchIn(html)
      .map(m => m.group(2) -> m.group(1))
      .toMap
    val manager = PsiManager.getInstance(getProject)
    val declaration = provider.getDocumentationElementForLink(
      manager,
      links("Declaration"),
      context
    )
    val implementation = provider.getDocumentationElementForLink(
      manager,
      links("Implementation"),
      context
    )
    assertEquals("claim", declaration.getText)
    assertEquals("claim", implementation.getText)
    assertTrue(declaration.getTextOffset < implementation.getTextOffset)

  def testMissingCommentAndUnresolvedReference(): Unit =
    assertTrue(
      docs("def plain():\n  0\ndef main():\n  <caret>plain()\n").contains(
        "def plain()"
      )
    )
    assertNull(docs("def main():\n  <caret>missing()\n"))

  def testImportedLawAndProofFillKeepRootContextAndPhysicalLinks(): Unit =
    val laws = myFixture.addFileToProject(
      "LAWS.bend",
      "# Source law explanation\nlaw claim:\n  for -n: Nat\n  {n == n : Nat}\n"
    )
    val html = docs(
      "import ./LAWS.bend as Laws\n# Implementation note\ndef Laws.claim(value):\n  value\ndef main():\n  Laws.<caret>claim(1)\n"
    )
    assertNotNull(html)
    assertTrue(html.contains("for -n: Nat"))
    assertTrue(html.contains("def Laws.claim(value)"))
    assertTrue(html.contains("Source law explanation"))
    assertTrue(html.contains("Implementation note"))
    assertTrue(html.contains("LAWS.bend"))
    assertTrue(html.contains("main.bend"))
    val links = "href='([^']+)'>([^<]+)</a>".r
      .findAllMatchIn(html)
      .map(m => m.group(2) -> m.group(1))
      .toMap
    val context = myFixture.getFile.findElementAt(
      myFixture.getEditor.getCaretModel.getOffset
    )
    val provider = new BendDocumentationProvider
    val manager = PsiManager.getInstance(getProject)
    assertEquals(
      laws.getVirtualFile,
      provider
        .getDocumentationElementForLink(manager, links("Declaration"), context)
        .getContainingFile
        .getVirtualFile
    )
    assertEquals(
      myFixture.getFile.getVirtualFile,
      provider
        .getDocumentationElementForLink(
          manager,
          links("Implementation"),
          context
        )
        .getContainingFile
        .getVirtualFile
    )
    val fillDoc = docs(
      "import ./LAWS.bend as Laws\ndef Laws.<caret>claim(value):\n  value\n"
    )
    assertTrue(fillDoc.contains("Source law explanation"))
    assertTrue(fillDoc.contains("for -n: Nat"))
    assertTrue(fillDoc.contains("def Laws.claim(value)"))

  def testStaleDocumentationLinkDoesNotNavigateSameOffsetRename(): Unit =
    val html = docs("def foo():\n  0\ndef main():\n  <caret>foo()\n")
    val url = "href='([^']+)'".r.findFirstMatchIn(html).get.group(1)
    val provider = new BendDocumentationProvider
    WriteCommandAction.runWriteCommandAction(
      getProject,
      new Runnable:
        override def run(): Unit =
          val document = myFixture.getEditor.getDocument
          document.replaceString(
            document.getText.indexOf("def foo") + 4,
            document.getText.indexOf("def foo") + 7,
            "bar"
          )
    )
    PsiDocumentManager.getInstance(getProject).commitAllDocuments()
    val context = myFixture.getFile.findElementAt(
      myFixture.getEditor.getCaretModel.getOffset
    )
    assertNull(
      provider.getDocumentationElementForLink(
        PsiManager.getInstance(getProject),
        url,
        context
      )
    )

  def testImportedSourceLawAndFillStayPaired(): Unit =
    val lib = myFixture.addFileToProject(
      "paired.bend",
      "# Imported law\nlaw claim:\n  for x: Nat\n  {x == x : Nat}\n# Imported fill\ndef claim(value):\n  value\n"
    )
    val html = docs(
      "import ./paired.bend as P\ndef main():\n  P.<caret>claim(1)\n"
    )
    assertTrue(html.contains("Imported law"))
    assertTrue(html.contains("Imported fill"))
    assertTrue(html.contains("def claim(value)"))
    assertTrue(html.contains("for x: Nat"))
    val links = "href='([^']+)'>([^<]+)</a>".r
      .findAllMatchIn(html)
      .map(m => m.group(2) -> m.group(1))
      .toMap
    val context = myFixture.getFile.findElementAt(
      myFixture.getEditor.getCaretModel.getOffset
    )
    val provider = new BendDocumentationProvider
    val manager = PsiManager.getInstance(getProject)
    assertEquals(
      lib.getVirtualFile,
      provider
        .getDocumentationElementForLink(manager, links("Declaration"), context)
        .getContainingFile
        .getVirtualFile
    )
    assertEquals(
      lib.getVirtualFile,
      provider
        .getDocumentationElementForLink(
          manager,
          links("Implementation"),
          context
        )
        .getContainingFile
        .getVirtualFile
    )
    myFixture.openFileInEditor(lib.getVirtualFile)
    val fill = myFixture.getFile.getText.indexOf("def claim") + 4
    myFixture.getEditor.getCaretModel.moveToOffset(fill)
    val target = provider.getCustomDocumentationElement(
      myFixture.getEditor,
      myFixture.getFile,
      myFixture.getFile.findElementAt(fill),
      fill
    )
    assertTrue(
      provider
        .generateDoc(target, myFixture.getFile.findElementAt(fill))
        .contains("for x: Nat")
    )

  def testBaseSourceLawAndFillStayPaired(): Unit =
    Files.writeString(
      temporary.resolve("base.bend"),
      "# Base law\nlaw word:\n  for x: Nat\n  {x == x : Nat}\n# Base fill\ndef word(value):\n  value\n"
    )
    val html = docs("import Base\ndef main():\n  <caret>word(1)\n")
    assertTrue(html.contains("Base law"))
    assertTrue(html.contains("Base fill"))
    assertTrue(html.contains("def word(value)"))
    assertTrue(html.contains("for x: Nat"))
    assertTrue(html.contains("Implementation</a>"))

  def testShadowedDirectAliasUsesLastImportForLawPair(): Unit =
    myFixture.addFileToProject(
      "first.bend",
      "# First law\nlaw claim:\n  Type\n"
    )
    val second = myFixture.addFileToProject(
      "second.bend",
      "# Second law\nlaw claim:\n  for x: Nat\n  Type\n"
    )
    val html = docs(
      "import ./first.bend as L\nimport ./second.bend as L\ndef L.claim(value):\n  value\ndef main():\n  L.<caret>claim(1)\n"
    )
    assertTrue(html.contains("Second law"))
    assertFalse(html.contains("First law"))
    assertTrue(html.contains("def L.claim(value)"))
    val url = "href='([^']+)'>([^<]+)</a>".r
      .findAllMatchIn(html)
      .find(_.group(2) == "Declaration")
      .get
      .group(1)
    val target = new BendDocumentationProvider().getDocumentationElementForLink(
      PsiManager.getInstance(getProject),
      url,
      myFixture.getFile.findElementAt(
        myFixture.getEditor.getCaretModel.getOffset
      )
    )
    assertEquals(second.getVirtualFile, target.getContainingFile.getVirtualFile)

  def testNamespaceConflictCannotPairRejectedAliasWithLaw(): Unit =
    val target = temporary.resolve("shared.bend")
    val alias = temporary.resolve("alias.bend")
    Files.writeString(
      target,
      "# Accepted law\nlaw claim:\n  for x: Nat\n  Type\n"
    )
    Files.createSymbolicLink(alias, target.getFileName)
    val imports = s"import $target as Accepted\nimport $alias as Rejected\n"
    val rejected = docs(
      imports + "def Rejected.<caret>claim(value):\n  value\n"
    )
    val graph = getProject.getService(
      classOf[com.dearlordylord.bend.idea.symbols.api.BendImportedSymbolCatalog]
    )
    val (basePath, packageCache) = getProject
      .getService(
        classOf[
          com.dearlordylord.bend.idea.workspace.api.BendLoadingConfiguration
        ]
      )
      .paths
    assertTrue(
      graph
        .loaded(myFixture.getFile, basePath, packageCache)
        .problems
        .exists(
          _.isInstanceOf[
            com.dearlordylord.bend.idea.workspace.model.BendGraphProblem.NamespaceConflict
          ]
        )
    )
    assertFalse(rejected.contains("Accepted law"))
    assertFalse(rejected.contains("for x: Nat"))
    assertFalse(rejected.contains("Law specification"))
    val accepted = docs(
      imports + "def Accepted.<caret>claim(value):\n  value\n"
    )
    assertTrue(accepted.contains("Accepted law"))
    assertTrue(accepted.contains("for x: Nat"))
