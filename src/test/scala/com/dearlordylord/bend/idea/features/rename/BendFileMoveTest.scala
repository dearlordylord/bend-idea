package com.dearlordylord.bend.idea.features.rename

import com.dearlordylord.bend.idea.symbols.references.{
  BendForeignPathReference,
  BendModulePathReference
}
import com.dearlordylord.bend.idea.adapters.cli.RealBendCompilerFixture
import com.dearlordylord.bend.idea.workspace.api.BendImportPaths
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.ui.{TestDialog, TestDialogManager}
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.psi.{PsiDocumentManager, PsiManager}
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.*
import scala.jdk.CollectionConverters.*
import java.nio.file.{Files, Path}
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

final class BendFileMoveTest extends BasePlatformTestCase:
  def testRenameUpdatesModuleAndForeignReferencesAndCanUndo(): Unit =
    val dependency =
      myFixture.addFileToProject("lib/dep.bend", "def value() -> U32:\n  1\n")
    val foreign =
      myFixture.addFileToProject("lib/native.c", "int native_value(void);\n")
    val consumer = myFixture.addFileToProject(
      "app/main.bend",
      "import ../lib/dep.bend as Dep\n" +
        "def external(text: String) -> IO(Unit):\n" +
        "  import \"../lib/native.c\"\n" +
        "def main() -> U32:\n  Dep.value()\n"
    )
    myFixture.openFileInEditor(consumer.getVirtualFile)
    PsiDocumentManager.getInstance(getProject).commitAllDocuments()
    myFixture.renameElement(dependency, "dep_next.bend")
    myFixture.renameElement(foreign, "native_next.c")
    assertTrue(consumer.getText.contains("import ../lib/dep_next.bend as Dep"))
    assertTrue(
      consumer.getText,
      consumer.getText.contains("\"../lib/native_next.c\"")
    )
    val moduleReference =
      consumer.getReferences.collectFirst {
        case reference: BendModulePathReference => reference
      }.get
    val foreignReference =
      consumer.getReferences.collectFirst {
        case reference: BendForeignPathReference => reference
      }.get
    assertEquals(dependency, moduleReference.resolve())
    assertEquals(foreign, foreignReference.resolve())

    val editor = FileEditorManager
      .getInstance(getProject)
      .getSelectedEditor(consumer.getVirtualFile)
    val undo = UndoManager.getInstance(getProject)
    assertTrue(undo.isUndoAvailable(editor))
    val consumerDocument =
      PsiDocumentManager.getInstance(getProject).getDocument(consumer)
    assertNotNull(consumerDocument)
    val previousDialog = TestDialogManager.setTestDialog(TestDialog.YES)
    try
      var undoCount = 0
      while undo.isUndoAvailable(editor) && undoCount < 8 &&
        consumerDocument.getText.contains("dep_next")
      do
        undo.undo(editor)
        PsiDocumentManager
          .getInstance(getProject)
          .commitDocument(consumerDocument)
        undoCount += 1
    finally
      val _ = TestDialogManager.setTestDialog(previousDialog)
    PsiDocumentManager.getInstance(getProject).commitAllDocuments()
    assertEquals(
      "import ../lib/dep.bend as Dep\n" +
        "def external(text: String) -> IO(Unit):\n" +
        "  import \"../lib/native.c\"\n" +
        "def main() -> U32:\n  Dep.value()\n",
      consumer.getText
    )

  def testMovePreviewCoversIncomingAndOutgoingRelativePaths(): Unit =
    val dependency =
      myFixture.addFileToProject("lib/dep.bend", "def value() -> U32:\n  1\n")
    val foreign =
      myFixture.addFileToProject("ffi/native.c", "int native_value(void);\n")
    val consumer = myFixture.addFileToProject(
      "deep/app/main.bend",
      "import ../../lib/dep.bend as Dep\n" +
        "def external(text: String) -> IO(Unit):\n" +
        "  import \"../../ffi/native.c\"\n" +
        "  import \"../../ffi/native.js\"\n" +
        "def main() -> U32:\n  Dep.value()\n"
    )
    val destinationFile =
      myFixture.addFileToProject("vendor/.keep", "keep\n")
    myFixture.openFileInEditor(consumer.getVirtualFile)
    PsiDocumentManager.getInstance(getProject).commitAllDocuments()
    val destination = destinationFile.getContainingDirectory
    val handler = new BendMoveFileHandler
    val incoming = handler
      .findUsages(
        PsiManager.getInstance(getProject).findFile(dependency.getVirtualFile),
        destination,
        false,
        true
      )
      .asScala
      .toList
    assertTrue(
      incoming
        .map(usage => Option(usage.getReference).map(_.getClass.getSimpleName))
        .toString,
      incoming.exists(_.getReference.isInstanceOf[BendModulePathReference])
    )
    assertEquals(
      List("../../vendor/dep.bend"),
      incoming.collect { case usage: BendMoveReferenceUsage =>
        usage.spelling
      }
    )

    val movingRoot = myFixture.addFileToProject(
      "deep/app/second.bend",
      "def external(text: String) -> IO(Unit):\n" +
        "  import \"../../ffi/native.c\"\n"
    )
    val outgoing = handler
      .findUsages(movingRoot, destination, false, true)
      .asScala
      .toList
    assertEquals(
      List("../ffi/native.c"),
      outgoing.collect { case usage: BendMoveReferenceUsage =>
        usage.spelling
      }
    )
    assertNotNull(foreign)

  def testLoaderNamespacePolicyIsSharedWithRefactoring(): Unit =
    assertEquals(
      "../lib/dep",
      BendImportPaths.namespace("", "../lib/dep.bend")
    )
    assertEquals(
      "app/lib/dep",
      BendImportPaths.namespace("app/main", "./lib/dep.bend")
    )

  def testOpenExternalLibrarySourceIsExcludedFromProjectMoveInventory(): Unit =
    val dependency =
      myFixture.addFileToProject("lib/dep.bend", "def value() -> U32:\n  1\n")
    val externalDirectory = Files.createTempDirectory("bend-external-library-")
    val externalPath = externalDirectory.resolve("library.bend")
    val relativeTarget = externalDirectory
      .relativize(Path.of(dependency.getVirtualFile.getPath))
      .toString
      .replace('\\', '/')
    Files.writeString(
      externalPath,
      s"import $relativeTarget as Dep\ndef caller() -> U32:\n  Dep.value()\n"
    )
    VfsRootAccess.allowRootAccess(
      getTestRootDisposable,
      externalDirectory.toString,
      externalDirectory.toRealPath().toString
    )
    val virtual = LocalFileSystem
      .getInstance()
      .refreshAndFindFileByPath(externalPath.toString)
    assertNotNull(virtual)
    val editors = FileEditorManager.getInstance(getProject)
    try
      editors.openFile(virtual, false)
      PsiDocumentManager.getInstance(getProject).commitAllDocuments()
      val externalPsi = PsiManager.getInstance(getProject).findFile(virtual)
      assertNotNull(externalPsi)

      val inventory = getProject
        .getService(
          classOf[com.dearlordylord.bend.idea.workspace.api.BendWorkspacePaths]
        )
        .filesWithExtension("bend", 4096)
        .toOption
        .get
      assertFalse(inventory.contains(externalPath.toString))

      val destination =
        myFixture
          .addFileToProject("vendor/.keep", "keep\n")
          .getContainingDirectory
      val handler = new BendMoveFileHandler
      val usages = handler
        .findUsages(
          PsiManager
            .getInstance(getProject)
            .findFile(dependency.getVirtualFile),
          destination,
          false,
          true
        )
        .asScala
        .toList
      assertFalse(
        usages.exists(usage =>
          Option(usage.getReference)
            .flatMap(reference =>
              Option(reference.getElement.getContainingFile)
            )
            .contains(externalPsi)
        )
      )
      assertTrue(externalPsi.getText.contains(s"import $relativeTarget as Dep"))
    finally
      Option(virtual).foreach(editors.closeFile)
      val files = Files.walk(externalDirectory)
      try
        files
          .sorted(java.util.Comparator.reverseOrder())
          .forEach(path => {
            val _ = Files.deleteIfExists(path)
          })
      finally files.close()

  def testMovedRelativeModuleAndForeignPathsStillPassPinnedCompiler(): Unit =
    val compiler = RealBendCompilerFixture.inputs
    val temporary = Files.createTempDirectory("bend-file-move-")
    try
      val app = Files.createDirectories(temporary.resolve("app"))
      val library = Files.createDirectories(temporary.resolve("lib"))
      val ffi = Files.createDirectories(temporary.resolve("ffi"))
      val vendor = Files.createDirectories(temporary.resolve("vendor"))
      Files.writeString(
        library.resolve("dep.bend"),
        "import Base\ndef value() -> U32:\n  1\n"
      )
      Files.writeString(ffi.resolve("native.c"), "void native_call(void) {}\n")
      Files.writeString(
        ffi.resolve("native.js"),
        "export const nativeCall = () => {};\n"
      )
      val root = app.resolve("main.bend")
      Files.writeString(
        root,
        "import Base\nimport ../lib/dep.bend as Dep\n" +
          "def external(text: String) -> IO(Unit):\n" +
          "  import \"../ffi/native.c\"\n" +
          "  import \"../ffi/native.js\"\n" +
          "def main() -> U32:\n  Dep.value()\n"
      )
      assertEquals(
        0,
        check(
          compiler.writeLauncher(temporary.resolve("bend")),
          root,
          temporary
        )
      )

      Files.move(library.resolve("dep.bend"), vendor.resolve("dep.bend"))
      Files.move(ffi.resolve("native.c"), vendor.resolve("native.c"))
      Files.move(ffi.resolve("native.js"), vendor.resolve("native.js"))
      Files.writeString(
        root,
        "import Base\nimport ../vendor/dep.bend as Dep\n" +
          "def external(text: String) -> IO(Unit):\n" +
          "  import \"../vendor/native.c\"\n" +
          "  import \"../vendor/native.js\"\n" +
          "def main() -> U32:\n  Dep.value()\n"
      )
      assertEquals(0, check(temporary.resolve("bend"), root, temporary))
    finally
      val files = Files.walk(temporary)
      try
        files
          .sorted(java.util.Comparator.reverseOrder())
          .forEach(path =>
            val _ = Files.deleteIfExists(path)
          )
      finally files.close()

  private def check(executable: Path, root: Path, workingDirectory: Path): Int =
    val offline =
      Files.createDirectories(workingDirectory.resolve("offline-lib"))
    val builder = new ProcessBuilder(
      executable.toString,
      root.toString,
      "--check-only"
    )
      .directory(workingDirectory.toFile)
      .redirectErrorStream(true)
    val environment = builder.environment()
    val _ = environment.put("BEND_LIB", offline.toString)
    val _ = environment.put("BEND_HUB", "file:///__bend_editor_offline__")
    val process = builder.start()
    assertTrue(
      "Pinned compiler timed out",
      process.waitFor(20, TimeUnit.SECONDS)
    )
    val output =
      new String(process.getInputStream.readAllBytes(), StandardCharsets.UTF_8)
    assertTrue("Pinned compiler failed: " + output, process.exitValue() == 0)
    process.exitValue()
