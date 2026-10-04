package com.dearlordylord.bend.idea.features.documentation

import com.dearlordylord.bend.idea.toolchain.api.{
  BendToolchainChoices,
  BendToolchainSettings
}
import com.intellij.openapi.application.ApplicationManager
import com.intellij.codeInsight.documentation.DocumentationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
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
    VfsRootAccess.allowRootAccess(
      getTestRootDisposable,
      temporary.toString,
      temporary.toRealPath().toString
    )
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

  def testNativeDocumentationLinkDispatchForLocalDeclaration(): Unit =
    val html = docs("def value():\n  0\ndef main():\n  <caret>value()\n")
    val url = "href='([^']+)'".r.findFirstMatchIn(html).get.group(1)
    val context = myFixture.getFile.findElementAt(myFixture.getCaretOffset)
    val target = DocumentationManager
      .getInstance(getProject)
      .getTargetElement(context, url)
    assertNotNull(
      "Declaration link must stay in IDEA's PSI link dispatcher",
      target
    )
    assertEquals("value", target.getText)
    assertEquals(myFixture.getFile, target.getContainingFile)

  def testNativeDocumentationLinkDispatchForImportedLawAndImplementation()
      : Unit =
    val laws = myFixture.addFileToProject(
      "LAWS.bend",
      "law claim:\n  for -n: Nat\n  {n == n : Nat}\n"
    )
    val html = docs(
      "import ./LAWS.bend as Laws\ndef Laws.claim(value):\n  value\ndef main():\n  Laws.<caret>claim(1)\n"
    )
    val context = myFixture.getFile.findElementAt(myFixture.getCaretOffset)
    val links = "href='([^']+)'>([^<]+)</a>".r
      .findAllMatchIn(html)
      .map(m => m.group(2) -> m.group(1))
      .toMap
    val manager = DocumentationManager.getInstance(getProject)
    val declaration = manager.getTargetElement(context, links("Declaration"))
    val implementation =
      manager.getTargetElement(context, links("Implementation"))
    assertNotNull("Imported law link must use native PSI dispatch", declaration)
    assertNotNull(
      "Implementation link must use native PSI dispatch",
      implementation
    )
    assertEquals(
      laws.getVirtualFile,
      declaration.getContainingFile.getVirtualFile
    )
    assertEquals(myFixture.getFile, implementation.getContainingFile)

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
    val url =
      "href='psi_element://([^']+)'".r.findFirstMatchIn(html).get.group(1)
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
    com.intellij.openapi.vfs.LocalFileSystem
      .getInstance()
      .refreshAndFindFileByPath(temporary.resolve("base.bend").toString)
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

  def testForeignDefinitionKeepsItsSourceSignatureInDocumentation(): Unit =
    val html = docs(
      "def foreign(value: U32) -> U32:\n  import \"foreign.c\"\ndef main() -> U32:\n  <caret>foreign(1)\n"
    )
    assertNotNull(html)
    assertTrue(html.contains("def foreign(value: U32) -&gt; U32"))

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
    val links = "href='psi_element://([^']+)'>([^<]+)</a>".r
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
    val links = "href='psi_element://([^']+)'>([^<]+)</a>".r
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
    val url =
      "href='psi_element://([^']+)'".r.findFirstMatchIn(html).get.group(1)
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
    val links = "href='psi_element://([^']+)'>([^<]+)</a>".r
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
    val baseFile = com.intellij.openapi.vfs.LocalFileSystem
      .getInstance()
      .refreshAndFindFileByPath(temporary.resolve("base.bend").toString)
    assertNotNull(
      "Configured base source should be visible in the VFS",
      baseFile
    )
    val html = docs("import Base\ndef main():\n  <caret>word(1)\n")
    assertTrue(html.contains("Base law"))
    assertTrue(html, html.contains("Base fill"))
    assertTrue(html, html.contains("def word(value)"))
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
    val url = "href='psi_element://([^']+)'>([^<]+)</a>".r
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

  def testCanonicalAliasesPairWithTheSameLaw(): Unit =
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
      graph.loaded(myFixture.getFile, basePath, packageCache).problems.isEmpty
    )
    assertTrue(rejected.contains("Accepted law"))
    assertTrue(rejected.contains("for x: Nat"))
    assertTrue(rejected.contains("Law specification"))
    val accepted = docs(
      imports + "def Accepted.<caret>claim(value):\n  value\n"
    )
    assertTrue(accepted.contains("Accepted law"))
    assertTrue(accepted.contains("for x: Nat"))

  private def syntaxAt(
      provider: BendDocumentationProvider,
      offset: Int
  ): String =
    val file = myFixture.getFile
    val leaf = file.findElementAt(offset)
    val selected = provider.getCustomDocumentationElement(
      myFixture.getEditor,
      file,
      null,
      offset
    )
    if selected == null then null else provider.generateDoc(selected, leaf)

  def testEverySyntaxEntryAndTokenRange(): Unit =
    val spellings = List(
      "def",
      "type",
      "law",
      "import",
      "match",
      "case",
      "do",
      "return",
      "for",
      "exs",
      "where",
      "is",
      "Type",
      "Data",
      "Kind",
      "Quant",
      "&0",
      "&1",
      "&2",
      "<&>",
      "->",
      "=>",
      "==",
      "!=",
      "{==}"
    )
    val provider = new BendDocumentationProvider
    spellings.foreach { spelling =>
      myFixture.configureByText("syntax.bend", "# 😀\n  " + spelling + "  ")
      val start = myFixture.getFile.getText.indexOf(spelling)
      val expected = syntaxAt(provider, start)
      assertNotNull(spelling, expected)
      for offset <- start until start + spelling.length do
        assertEquals(spelling, expected, syntaxAt(provider, offset))
      val leaf = myFixture.getFile.findElementAt(start)
      val navigation = provider.getQuickNavigateInfo(leaf, null)
      assertTrue(navigation, navigation.startsWith("Bend syntax: " + spelling))
      assertTrue(
        expected.contains(
          navigation
            .split("\n")(1)
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("'", "&#39;")
        )
      )
      assertNull(syntaxAt(provider, start - 1))
      assertNull(syntaxAt(provider, start + spelling.length))
      assertNull(syntaxAt(provider, myFixture.getFile.getTextLength))
    }

  def testSyntaxCatalogueMeaningAndEscaping(): Unit =
    assertTrue(
      docs("<caret>&1").contains("at most one use; discarding is allowed")
    )
    assertTrue(docs("<caret>do").contains("monads other than IO"))
    assertTrue(
      docs("<caret>law").contains(
        "does not establish that it has been checked or filled"
      )
    )
    assertTrue(docs("<caret><&>").contains("Bend syntax: &lt;&amp;&gt;"))
    assertTrue(docs("<caret>{==}").contains("compiler must still check"))

  def testSyntaxSuppressionAndExactLexemes(): Unit =
    val provider = new BendDocumentationProvider
    val excluded = List(
      "# def",
      "\"def\"",
      "'def'",
      "\"def",
      "'def",
      "\"\\ndef\"",
      "lawful",
      "Kindred",
      "&20",
      "M.Kind",
      "==!=",
      "as",
      "?TODO",
      "123",
      "%",
      "!",
      "+",
      "-",
      "&",
      "@",
      "~"
    )
    excluded.foreach { text =>
      myFixture.configureByText("excluded.bend", text)
      for offset <- text.indices do
        val leaf = myFixture.getFile.findElementAt(offset)
        val documentation = provider.generateDoc(leaf, null)
        assertFalse(
          text + " at " + offset,
          documentation != null && documentation.contains("Bend syntax:")
        )
    }
    myFixture.configureByText("foreign.txt", "def")
    assertNull(provider.generateDoc(myFixture.getFile.findElementAt(0), null))
    assertNull(provider.generateDoc(null, null))
    assertNull(provider.getQuickNavigateInfo(null, null))

  def testSyntaxOfflineDispatchDoesNotReadCompilerResults(): Unit =
    ApplicationManager.getApplication
      .getService(classOf[BendToolchainSettings])
      .update(BendToolchainChoices())
    val provider = new BendDocumentationProvider:
      override protected def currentExpressionType(
          file: com.intellij.psi.PsiFile,
          offset: Int
      ): Option[com.dearlordylord.bend.idea.analysis.model.BendExpressionType] =
        fail("Syntax documentation attempted compiler work")
        None
    myFixture.configureByText("offline.bend", "def broken(:\n  &1")
    com.intellij.testFramework.DumbModeTestUtils.computeInDumbModeSynchronously(
      getProject,
      new com.intellij.openapi.util.ThrowableComputable[Unit, Throwable]:
        override def compute(): Unit =
          val offset = myFixture.getFile.getText.indexOf("&1")
          assertTrue(syntaxAt(provider, offset).contains("Bend syntax: &amp;1"))
          val leaf = myFixture.getFile.findElementAt(offset)
          assertTrue(
            provider
              .getQuickNavigateInfo(leaf, null)
              .contains("Bend syntax: &1")
          )
    )

  def testUnsavedEditSuppressesAndRestoresSyntax(): Unit =
    myFixture.configureByText("editing.bend", "def broken(:\n  &1")
    val provider = new BendDocumentationProvider
    val manager = PsiDocumentManager.getInstance(getProject)
    List("# law", "\"law", "law").foreach { text =>
      WriteCommandAction.runWriteCommandAction(
        getProject,
        new Runnable:
          override def run(): Unit =
            myFixture.getEditor.getDocument.setText(text)
      )
      manager.commitAllDocuments()
      val html = syntaxAt(provider, text.indexOf("law"))
      if text == "law" then assertTrue(html.contains("Bend syntax: law"))
      else assertNull(html)
    }

  def testNativeQuickDocumentationTargetDispatch(): Unit =
    myFixture.configureByText("native.bend", "def main():\n  <caret>{==}\n")
    val manager = com.intellij.codeInsight.documentation.DocumentationManager
      .getInstance(getProject)
    val selected =
      manager.findTargetElement(myFixture.getEditor, myFixture.getFile)
    assertNotNull(selected)
    assertEquals("{==}", selected.getText)
    val provider = com.intellij.codeInsight.documentation.DocumentationManager
      .getProviderFromElement(selected)
    assertTrue(
      provider.generateDoc(selected, null).contains("Bend syntax: {==}")
    )
    assertTrue(
      provider
        .getQuickNavigateInfo(selected, null)
        .contains("reflexivity witness")
    )

  def testImportPathAndCrLfSuppression(): Unit =
    myFixture.configureByText("path.bend", "# 😀\r\nimport ./law.bend as L\r\n")
    val provider = new BendDocumentationProvider
    val offset = myFixture.getFile.getText.indexOf("./law")
    assertNull(syntaxAt(provider, offset))
    val keyword = myFixture.getFile.getText.indexOf("import")
    assertTrue(syntaxAt(provider, keyword).contains("Bend syntax: import"))
