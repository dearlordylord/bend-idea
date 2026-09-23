package com.dearlordylord.bend.idea.features.completion

import com.dearlordylord.bend.idea.toolchain.api.*
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.*
import java.nio.file.{Files, Path}

final class BendImportPathCompletionTest extends BasePlatformTestCase:
  private var original: BendToolchainChoices = null
  private var temporary: Path = null

  override def setUp(): Unit =
    super.setUp()
    val settings = ApplicationManager.getApplication.getService(
      classOf[BendToolchainSettings]
    )
    original = settings.choices
    temporary = Files.createTempDirectory("bend-path-completion")
    settings.update(
      BendToolchainChoices(packageCache = temporary.resolve("cache").toString)
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

  private def items(source: String): Array[LookupElement] =
    myFixture.configureByText("main.bend", source)
    Option(myFixture.completeBasic()).getOrElse(Array.empty[LookupElement])

  private def choose(found: Array[LookupElement], name: String): Unit =
    val item = found.find(_.getLookupString == name).getOrElse {
      fail(s"Missing $name: ${found.map(_.getLookupString).mkString(", ")}")
      throw new AssertionError()
    }
    myFixture.getLookup.setCurrentItem(item)
    myFixture.finishLookup('\t')

  def testBaseRelativeDirectoriesAndNewProjectFiles(): Unit =
    myFixture.addFileToProject("fresh.bend", "def fresh():\n  0\n")
    myFixture.addFileToProject("sub/child.bend", "def child():\n  0\n")
    val found = items("import <caret>")
    assertTrue(found.exists(_.getLookupString == "Base"))
    assertTrue(found.exists(_.getLookupString == "./"))
    assertTrue(found.exists(_.getLookupString == "../"))
    assertTrue(found.exists(_.getLookupString == "fresh.bend"))
    assertTrue(found.exists(_.getLookupString == "sub/"))

  def testFilenameReplacesWholePathIncludingAfterCaret(): Unit =
    myFixture.addFileToProject("sub/part.bend", "def part():\n  0\n")
    choose(items("import ./sub/pa<caret>rt.bend as P"), "./sub/part.bend")
    assertEquals(
      "import ./sub/part.bend as P",
      myFixture.getEditor.getDocument.getText
    )

  def testDirectoryAcceptanceKeepsPathOpenForNextSegment(): Unit =
    myFixture.addFileToProject("sub/deep/child.bend", "def child():\n  0\n")
    choose(items("import ./sub/de<caret>ep/child.bend as C"), "./sub/deep/")
    assertEquals(
      "import ./sub/deep/ as C",
      myFixture.getEditor.getDocument.getText
    )
    assertEquals(
      "import ./sub/deep/".length,
      myFixture.getEditor.getCaretModel.getOffset
    )
    val next = items("import ./sub/deep/<caret> as C")
    assertTrue(
      next.exists(_.getLookupString == "./sub/deep/child.bend") ||
        myFixture.getEditor.getDocument.getText == "import ./sub/deep/child.bend as C"
    )

  def testCachedHashAndAbsolutePath(): Unit =
    myFixture.addFileToProject("0xabc.bend", "def local():\n  0\n")
    myFixture.addFileToProject("0xabc/child.bend", "def child():\n  0\n")
    val cached = temporary.resolve("cache/0xabc")
    Files.createDirectories(cached)
    Files.writeString(cached.resolve("pkg.bend"), "def cached():\n  0\n")
    val hashes = items("import 0xab<caret> as P")
    assertTrue(hashes.exists(_.getLookupString == "0xabc/"))
    assertTrue(hashes.exists(_.getLookupString == "0xabc.bend"))
    val hashStart = items("import 0x<caret> as P")
    assertTrue(hashStart.exists(_.getLookupString == "0xabc/"))
    val cachedFile = items("import 0xabc/<caret> as P")
    assertTrue(
      cachedFile.exists(_.getLookupString == "0xabc/pkg.bend") ||
        myFixture.getEditor.getDocument.getText == "import 0xabc/pkg.bend as P"
    )
    val absolute = temporary.resolve("absolute")
    Files.createDirectories(absolute)
    Files.writeString(absolute.resolve("mod.bend"), "def absolute():\n  0\n")
    val absoluteItems = items(s"import ${absolute.toString}/mo<caret> as M")
    assertTrue(
      absoluteItems.exists(
        _.getLookupString == absolute.toString + "/mod.bend"
      ) ||
        myFixture.getEditor.getDocument.getText == s"import ${absolute.toString}/mod.bend as M"
    )

  def testMissingPartialAndUnsupportedWhitespace(): Unit =
    myFixture.addFileToProject("sub/valid.bend", "def valid():\n  0\n")
    myFixture.addFileToProject("my file.bend", "def invalid():\n  0\n")
    val partial = items("import ./su<caret> as S")
    assertTrue(
      partial.exists(_.getLookupString == "./sub/") ||
        myFixture.getEditor.getDocument.getText == "import ./sub/ as S"
    )
    assertTrue(items("import ./missing/no<caret> as M").isEmpty)
    assertFalse(
      items("import ./my<caret> file.bend as M")
        .exists(_.getLookupString.contains("file.bend"))
    )
    assertFalse(
      items("import ./<caret> as M")
        .exists(_.getLookupString.contains("my file.bend"))
    )
    val valid = items("import ./sub/<caret> as S")
    assertTrue(
      valid.exists(_.getLookupString == "./sub/valid.bend") ||
        myFixture.getEditor.getDocument.getText == "import ./sub/valid.bend as S"
    )

  def testNormalizedHashParentReturnsToLocalDirectory(): Unit =
    myFixture.addFileToProject("foo.bend", "def local():\n  0\n")
    val found = items("import 0xabc/../fo<caret> as F")
    assertTrue(
      found.exists(_.getLookupString == "0xabc/../foo.bend") ||
        myFixture.getEditor.getDocument.getText == "import 0xabc/../foo.bend as F"
    )

  def testLocalHashFolderAloneIsNotOfferedAsCachePackage(): Unit =
    myFixture.addFileToProject("0xabc/child.bend", "def child():\n  0\n")
    myFixture.addFileToProject("0xabc.bend", "def local():\n  0\n")
    myFixture.addFileToProject("0xabcd.bend", "def other():\n  0\n")
    val found = items("import 0xab<caret> as L")
    assertFalse(found.exists(_.getLookupString == "0xabc/"))
    assertTrue(found.exists(_.getLookupString == "0xabc.bend"))
    assertTrue(found.exists(_.getLookupString == "0xabcd.bend"))

  def testOpenUnsavedModuleSurvivesSaturatedDirectory(): Unit =
    val crowded = temporary.resolve("crowded")
    Files.createDirectories(crowded)
    (0 until 260).foreach(i =>
      Files.writeString(
        crowded.resolve(f"disk$i%03d.bend"),
        "def disk():\n  0\n"
      )
    )
    val unsaved = crowded.resolve("unpersisted.bend")
    Files.writeString(unsaved, "def fresh():\n  0\n")
    val virtual =
      LocalFileSystem.getInstance().refreshAndFindFileByNioFile(unsaved)
    assertNotNull(virtual)
    FileEditorManager.getInstance(getProject).openFile(virtual, false)
    Files.delete(unsaved)
    val found = items(s"import ${crowded.toString}/unp<caret> as U")
    assertTrue(
      found.exists(
        _.getLookupString == crowded.toString + "/unpersisted.bend"
      ) ||
        myFixture.getEditor.getDocument.getText == s"import ${crowded.toString}/unpersisted.bend as U"
    )
