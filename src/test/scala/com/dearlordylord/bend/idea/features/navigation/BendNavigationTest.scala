package com.dearlordylord.bend.idea.features.navigation

import com.dearlordylord.bend.idea.symbols.references.BendNameReference
import com.dearlordylord.bend.idea.symbols.references.BendPhysicalTargets
import com.dearlordylord.bend.idea.symbols.api.BendSourceSymbols
import com.dearlordylord.bend.idea.model.FileId
import com.dearlordylord.bend.idea.syntax.BendLanguage
import com.dearlordylord.bend.idea.toolchain.api.{
  BendToolchainChoices,
  BendToolchainSettings
}
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.codeInsight.navigation.actions.GotoDeclarationAction
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.testFramework.LightVirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.*
import java.nio.file.{Files, Path}

final class BendNavigationTest extends BasePlatformTestCase:
  private var original: BendToolchainChoices = null
  private var temporary: Path = null

  override def setUp(): Unit =
    super.setUp()
    val settings = ApplicationManager.getApplication.getService(
      classOf[BendToolchainSettings]
    )
    original = settings.choices
    temporary = Files.createTempDirectory("bend-navigation")
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

  private def reference(source: String): BendNameReference =
    myFixture.configureByText("main.bend", source)
    val ref = myFixture.getFile.findReferenceAt(
      myFixture.getEditor.getCaretModel.getOffset
    )
    assertNotNull("Native reference at caret", ref)
    ref.asInstanceOf[BendNameReference]

  private def target(ref: BendNameReference): PsiElement =
    val result = ref.resolve()
    assertNotNull("Resolved source target", result)
    result

  private def editorTarget(): PsiElement =
    val result = GotoDeclarationAction.findTargetElement(
      getProject,
      myFixture.getEditor,
      myFixture.getEditor.getCaretModel.getOffset
    )
    assertNotNull("Go to Declaration target", result)
    result

  def testModulePathNavigatesToPhysicalFile(): Unit =
    val lib = myFixture.addFileToProject("lib.bend", "def value():\n  0\n")
    myFixture.configureByText(
      "main.bend",
      "import ./li<caret>b.bend as M\ndef main():\n  M.value()\n"
    )
    assertEquals(lib, editorTarget())
    val ref = myFixture.getFile.findReferenceAt(myFixture.getCaretOffset)
    assertEquals("./lib.bend", ref.getCanonicalText)
    assertEquals(
      new com.intellij.openapi.util.TextRange(7, 17),
      ref.getRangeInElement.shiftRight(ref.getElement.getTextOffset)
    )

  def testAbsoluteBaseAndCachedModulePathsUseCurrentSettings(): Unit =
    val absolute = Files.writeString(
      temporary.resolve("absolute.bend"),
      "def value():\n  0\n"
    )
    myFixture.configureByText(
      "main.bend",
      s"import ${absolute.toString.dropRight(5)}<caret>.bend as M\n"
    )
    assertEquals(
      absolute.toString,
      editorTarget().getContainingFile.getVirtualFile.getCanonicalPath
    )
    Files.writeString(temporary.resolve("base.bend"), "def baseValue():\n  0\n")
    myFixture.configureByText("main.bend", "import Ba<caret>se\n")
    assertEquals("base.bend", editorTarget().getContainingFile.getName)
    val otherBase = Files.writeString(
      temporary.resolve("other-base.bend"),
      "def other():\n  0\n"
    )
    val settings = ApplicationManager.getApplication.getService(
      classOf[BendToolchainSettings]
    )
    val cache = Files.createDirectories(temporary.resolve("cache/0xabc"))
    Files.writeString(cache.resolve("cached.bend"), "def cached():\n  0\n")
    settings.update(
      BendToolchainChoices(
        baseSource = otherBase.toString,
        packageCache = cache.getParent.toString
      )
    )
    assertEquals("other-base.bend", editorTarget().getContainingFile.getName)
    myFixture.configureByText(
      "main.bend",
      "import 0xabc/ca<caret>ched.bend as C\n"
    )
    assertEquals(
      cache.resolve("cached.bend").toString,
      editorTarget().getContainingFile.getVirtualFile.getCanonicalPath
    )

  def testUnsavedModulePathAndTargetUseCurrentDocuments(): Unit =
    val first = myFixture.addFileToProject("first.bend", "def old():\n  0\n")
    val second = myFixture.addFileToProject("second.bend", "def old():\n  0\n")
    val main =
      myFixture.addFileToProject("main.bend", "import ./first.bend as M\n")
    myFixture.openFileInEditor(main.getVirtualFile)
    myFixture.getEditor.getCaretModel.moveToOffset(12)
    assertEquals(first, editorTarget())
    val targetDocument = com.intellij.openapi.fileEditor.FileDocumentManager
      .getInstance()
      .getDocument(second.getVirtualFile)
    WriteCommandAction.runWriteCommandAction(
      getProject,
      new Runnable:
        override def run(): Unit =
          myFixture.getEditor.getDocument.setText("import ./second.bend as M\n")
          targetDocument.setText("def current():\n  42\n")
    )
    com.intellij.psi.PsiDocumentManager
      .getInstance(getProject)
      .commitAllDocuments()
    myFixture.getEditor.getCaretModel.moveToOffset(12)
    assertEquals(second, editorTarget())
    assertEquals("def current():\n  42\n", editorTarget().getText)

  def testInvalidAndMissingModulePathsHaveNoTarget(): Unit =
    myFixture.addFileToProject("lib.bend", "def value():\n  0\n")
    List(
      "import ./mis<caret>sing.bend as M\n",
      "import ./li<caret>b.bend\n",
      "import ./li<caret>b.bend as\n",
      "import ./li<caret>b.bend as 123\n",
      "import ./li<caret>b as M\n",
      "import 0xabc/mis<caret>sing.bend as M\n",
      "import <caret>\n"
    ).foreach { source =>
      myFixture.configureByText("main.bend", source)
      assertNull(
        source,
        GotoDeclarationAction.findTargetElement(
          getProject,
          myFixture.getEditor,
          myFixture.getCaretOffset
        )
      )
    }
    val directory = Files.createDirectory(temporary.resolve("directory.bend"))
    myFixture.configureByText("main.bend", s"import $directory<caret> as M\n")
    assertNull(
      GotoDeclarationAction.findTargetElement(
        getProject,
        myFixture.getEditor,
        myFixture.getCaretOffset - 1
      )
    )

  def testModulePathRangeExcludesAliasCommentsAndForeignBodyPaths(): Unit =
    val lib = myFixture.addFileToProject("lib.bend", "def value():\n  0\n")
    myFixture.configureByText(
      "main.bend",
      "# 😀 heading\r\n\timport\t./lib.bend as M # comment\r\ndef main():\n  M.value()\n"
    )
    val source = myFixture.getFile.getText
    val start = source.indexOf("./lib.bend")
    for offset <- start until start + "./lib.bend".length do
      myFixture.getEditor.getCaretModel.moveToOffset(offset)
      assertEquals(lib, editorTarget())
    for offset <- List(
        source.indexOf("import"),
        source.indexOf("as M") + 3,
        source.indexOf("comment")
      )
    do assertNull(myFixture.getFile.findReferenceAt(offset))
    List(
      "def main():\n  import ./li<caret>b.bend as M\n",
      "def foreign():\n  import \"./li<caret>b.js\"\n",
      "def foreign():\n  import \"./li<caret>b.c\"\n"
    ).foreach { text =>
      myFixture.configureByText("main.bend", text)
      val ref = myFixture.getFile.findReferenceAt(myFixture.getCaretOffset)
      assertFalse(
        Option(ref).exists(_.resolve().isInstanceOf[com.intellij.psi.PsiFile])
      )
    }

  def testRepeatedImportsKeepTheirWrittenPathTargets(): Unit =
    val first = myFixture.addFileToProject("first.bend", "def first():\n  0\n")
    val second =
      myFixture.addFileToProject("second.bend", "def second():\n  0\n")
    myFixture.configureByText(
      "main.bend",
      "import ./first.bend as M\nimport ./second.bend as M\nimport ./first.bend as F\n"
    )
    for (offset, expected) <- List((12, first), (36, second), (62, first)) do
      myFixture.getEditor.getCaretModel.moveToOffset(offset)
      assertEquals(expected, editorTarget())

  def testSymlinkModulePathsRetainCanonicalIdentityAndRejectNamespaceConflicts()
      : Unit =
    val real =
      Files.writeString(temporary.resolve("real.bend"), "def value():\n  0\n")
    val link = Files.createSymbolicLink(temporary.resolve("link.bend"), real)
    myFixture.configureByText("main.bend", s"import $link as M\n")
    myFixture.getEditor.getCaretModel.moveToOffset(8)
    assertEquals(
      real.toString,
      editorTarget().getContainingFile.getVirtualFile.getPath
    )
    myFixture.configureByText(
      "main.bend",
      s"import $real as R\nimport $link as M\n"
    )
    myFixture.getEditor.getCaretModel.moveToOffset(
      myFixture.getFile.getText.indexOf(link.toString) + 1
    )
    assertNull(
      GotoDeclarationAction.findTargetElement(
        getProject,
        myFixture.getEditor,
        myFixture.getCaretOffset
      )
    )

  def testUnreadableModuleHasNoTarget(): Unit =
    val unreadable = Files.writeString(
      temporary.resolve("unreadable.bend"),
      "def value():\n  0\n"
    )
    val permissions = Files.getPosixFilePermissions(unreadable)
    try
      Files.setPosixFilePermissions(
        unreadable,
        java.util.Collections.emptySet()
      )
      assertFalse(
        "The test file must actually be unreadable",
        Files.isReadable(unreadable)
      )
      myFixture.configureByText("main.bend", s"import $unreadable as M\n")
      myFixture.getEditor.getCaretModel.moveToOffset(8)
      assertNull(
        GotoDeclarationAction.findTargetElement(
          getProject,
          myFixture.getEditor,
          myFixture.getCaretOffset
        )
      )
    finally Files.setPosixFilePermissions(unreadable, permissions)

  def testLocalShadowingAndForwardEligibility(): Unit =
    val local = reference(
      "def outer(x: U32) -> U32:\n  let x = 1\n  <caret>x\n"
    )
    assertEquals(32, target(local).getTextOffset)
    assertTrue(local.eligible)
    val forward = reference(
      "def main():\n  <caret>later()\ndef later():\n  0\n"
    )
    assertEquals("later", target(forward).getText)
    assertFalse(forward.eligible)

  def testDeclarationAndNestedBinderNavigation(): Unit =
    val declaration = reference("def <caret>value():\n  0\n")
    assertEquals("value", target(declaration).getText)
    val nested = reference("def main(x: U32) -> U32:\n  use(x => <caret>x)\n")
    assertEquals("x", target(nested).getText)
    assertTrue(target(nested).getTextOffset > 25)

  def testAliasMemberAndUnsavedImportedBuffer(): Unit =
    val lib = myFixture.addFileToProject("lib.bend", "def old():\n  0\n")
    myFixture.openFileInEditor(lib.getVirtualFile)
    WriteCommandAction.runWriteCommandAction(
      getProject,
      new Runnable {
        override def run(): Unit =
          myFixture.getEditor.getDocument.setText("def new():\n  1\n")
      }
    )
    com.intellij.psi.PsiDocumentManager
      .getInstance(getProject)
      .commitAllDocuments()
    val member = reference(
      "import ./lib.bend as L\ndef main():\n  L.<caret>new()\n"
    )
    assertEquals(
      new com.intellij.openapi.util.TextRange(2, 5),
      member.getRangeInElement
    )
    assertEquals("new", target(member).getText)
    assertEquals(
      lib.getVirtualFile,
      target(member).getContainingFile.getVirtualFile
    )
    assertEquals(
      lib.getVirtualFile,
      editorTarget().getContainingFile.getVirtualFile
    )
    val memberOffset = target(member).getTextOffset
    assertTrue(member.eligible)
    val alias = reference(
      "import ./lib.bend as L\ndef main():\n  <caret>L.new()\n"
    )
    assertEquals(
      new com.intellij.openapi.util.TextRange(0, 1),
      alias.getRangeInElement
    )
    assertEquals("L", target(alias).getText)
    val second = reference(
      "import ./lib.bend as R\ndef main():\n  R.<caret>new()\n"
    )
    assertEquals(
      lib.getVirtualFile,
      target(second).getContainingFile.getVirtualFile
    )
    assertEquals(memberOffset, target(second).getTextOffset)
    assertEquals(
      BendSourceSymbols.fileId(lib),
      BendSourceSymbols.fileId(target(second).getContainingFile)
    )

  def testDottedDeclarationKeepsItsOwnIdentityDespiteAliasCollision(): Unit =
    myFixture.addFileToProject("lib.bend", "def own():\n  1\n")
    val declaration = reference(
      "import ./lib.bend as L\ndef <caret>L.own():\n  0\ndef main():\n  L.own()\n"
    )
    assertEquals(
      new com.intellij.openapi.util.TextRange(0, 5),
      declaration.getRangeInElement
    )
    assertEquals("L.own", target(declaration).getText)
    assertEquals(
      myFixture.getFile.getVirtualFile,
      editorTarget().getContainingFile.getVirtualFile
    )
    val usage = reference(
      "import ./lib.bend as L\ndef L.own():\n  0\ndef main():\n  L.<caret>own()\n"
    )
    assertEquals("lib.bend", target(usage).getContainingFile.getName)

  def testUnresolvedAndIncompleteAliasPrefixes(): Unit =
    myFixture.addFileToProject("lib.bend", "def known():\n  0\n")
    val missingMember = reference(
      "import ./lib.bend as L\ndef main():\n  <caret>L.unknown()\n"
    )
    assertEquals(
      new com.intellij.openapi.util.TextRange(0, 1),
      missingMember.getRangeInElement
    )
    assertEquals("L", target(missingMember).getText)
    val member = reference(
      "import ./lib.bend as L\ndef main():\n  L.<caret>unknown()\n"
    )
    assertNull(member.resolve())
    val incomplete = reference(
      "import ./lib.bend as L\ndef main():\n  <caret>L.\n"
    )
    assertEquals(
      new com.intellij.openapi.util.TextRange(0, 1),
      incomplete.getRangeInElement
    )
    assertEquals("L", target(incomplete).getText)
    myFixture.addFileToProject(
      "categories.bend",
      "type Unit is Data:\n  Unit{}\n"
    )
    val ambiguousMember = reference(
      "import ./categories.bend as C\ndef main():\n  <caret>C.Unit\n"
    )
    assertEquals(
      new com.intellij.openapi.util.TextRange(0, 1),
      ambiguousMember.getRangeInElement
    )
    assertEquals("C", target(ambiguousMember).getText)

  def testLiteralDottedAndCategories(): Unit =
    val dotted = reference("def A.add():\n  0\ndef main():\n  <caret>A.add()\n")
    assertEquals("A.add", target(dotted).getText)
    val datatype = reference(
      "type Unit is Data:\n  Unit{}\ndef main(x: <caret>Unit) -> Unit:\n  Unit{}\n"
    )
    assertEquals(5, target(datatype).getTextOffset)
    val constructor = reference(
      "type Unit is Data:\n  Unit{}\ndef main():\n  <caret>Unit{}\n"
    )
    assertEquals(21, target(constructor).getTextOffset)
    val nestedType = reference(
      "type Unit is Data:\n  Unit{}\ndef main(x: List<<caret>Unit>) -> Unit:\n  x\n"
    )
    assertEquals(5, target(nestedType).getTextOffset)

  def testBaseAndUnresolvedIncompleteInput(): Unit =
    Files.writeString(
      temporary.resolve("base.bend"),
      "def baseValue():\n  0\ndef L.literal():\n  1\n"
    )
    val base = reference("import Base\ndef main():\n  <caret>baseValue()\n")
    assertEquals("baseValue", target(base).getText)
    assertEquals("base.bend", target(base).getContainingFile.getName)
    myFixture.addFileToProject("lib.bend", "def other():\n  0\n")
    val literal = reference(
      "import Base\nimport ./lib.bend as L\ndef main():\n  <caret>L.literal()\n"
    )
    assertEquals(
      new com.intellij.openapi.util.TextRange(0, 9),
      literal.getRangeInElement
    )
    assertEquals("L.literal", target(literal).getText)
    val unresolved = reference(
      "import ./missing.bend as M\ndef main():\n  <caret>unknown(\n"
    )
    assertNull(unresolved.resolve())

  def testNonlocalVfsGraphIdentityReacquiresPhysicalDeclaration(): Unit =
    val virtual = new LightVirtualFile(
      "nonlocal.bend",
      BendLanguage.instance,
      "def physical():\n  0\n"
    )
    val file = PsiManager.getInstance(getProject).findFile(virtual)
    assertNotNull(file)
    assertFalse(virtual.isInLocalFileSystem)
    val parsed = BendSourceSymbols.declarations(file).head
    val canonical = Option(virtual.getCanonicalPath)
    val graphId =
      new FileId(canonical.getOrElse(virtual.getPath), canonical.isDefined)
    assertNotEquals(
      "The nonlocal PSI ID is buffer-local",
      parsed.handle.file,
      graphId
    )
    val loaded = parsed.copy(handle = parsed.handle.copy(file = graphId))
    assertEquals(
      "physical",
      BendPhysicalTargets.declaration(file, loaded).get.getText
    )
    assertEquals(
      file,
      BendPhysicalTargets.declaration(file, loaded).get.getContainingFile
    )
    val unrelated = loaded.copy(handle =
      loaded.handle.copy(file = new FileId("/other/nonlocal.bend", false))
    )
    assertTrue(BendPhysicalTargets.declaration(file, unrelated).isEmpty)
