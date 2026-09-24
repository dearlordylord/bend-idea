package com.dearlordylord.bend.idea.features.navigation

import com.dearlordylord.bend.idea.adapters.cli.RealBendCompilerFixture
import com.dearlordylord.bend.idea.symbols.api.BendSymbolCategory
import com.dearlordylord.bend.idea.symbols.api.BendSourceSymbols
import com.dearlordylord.bend.idea.syntax.psi.BendDefinition
import com.dearlordylord.bend.idea.toolchain.api.{
  BendToolchainChoices,
  BendToolchainSettings
}
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.*

final class BendStaticDependenciesTest extends BasePlatformTestCase:
  private var original: BendToolchainChoices = null
  private var basePath = ""

  override def setUp(): Unit =
    super.setUp()
    val settings = ApplicationManager.getApplication.getService(
      classOf[BendToolchainSettings]
    )
    original = settings.choices
    val base = RealBendCompilerFixture.inputs.base
    basePath = base.toString
    VfsRootAccess.allowRootAccess(
      getTestRootDisposable,
      base.getParent.toString
    )
    settings.update(BendToolchainChoices(baseSource = basePath))

  override def tearDown(): Unit =
    try
      ApplicationManager.getApplication
        .getService(classOf[BendToolchainSettings])
        .update(original)
    finally super.tearDown()

  def testUnsavedCallEdgesAliasImportsAndUnknownCalls(): Unit =
    val dependency = myFixture.addFileToProject(
      "lib/dep.bend",
      "def target() -> U32:\n  1\n"
    )
    val root = myFixture.addFileToProject(
      "app/main.bend",
      "import ../lib/dep.bend as Dep\n" +
        "import ../lib/dep.bend as Other\n" +
        "def caller() -> U32:\n  Dep.target()\n" +
        "def unknown_caller() -> U32:\n  dynamic_target()\n"
    )
    myFixture.openFileInEditor(root.getVirtualFile)
    val document = myFixture.getEditor.getDocument
    com.intellij.openapi.command.WriteCommandAction
      .writeCommandAction(getProject, root)
      .run(
        new com.intellij.util.ThrowableRunnable[RuntimeException]:
          override def run(): Unit =
            document.insertString(
              document.getTextLength,
              "# unsaved source remains searchable\n"
            )
      )
    val otherRoot = myFixture.addFileToProject(
      "second/main.bend",
      "import ../lib/dep.bend as D\ndef other_root() -> U32:\n  D.target()\n"
    )
    assertTrue(otherRoot.getText.contains("other_root"))
    PsiDocumentManager.getInstance(getProject).commitAllDocuments()

    val target = PsiTreeUtil
      .findChildrenOfType(dependency, classOf[BendDefinition])
      .stream()
      .filter(_.getName == "target")
      .findFirst()
      .orElse(null)
    assertNotNull(target)
    val incoming = BendStaticDependencies.inspect(target).toOption.get
    val calledBy = incoming.dependencies.filter(
      _.kind == BendDependencyKind.CalledBy
    )
    assertTrue(incoming.dependencies.toString, calledBy.size >= 2)
    assertTrue(calledBy.head.navigation.getText.contains("target"))
    assertTrue(incoming.coverage.contains("not a complete runtime call graph"))

    val outgoing = BendStaticDependencies.inspect(root).toOption.get
    assertEquals(
      1,
      outgoing.dependencies.count(_.kind == BendDependencyKind.Import)
    )
    assertTrue(
      outgoing.dependencies.exists(dependency =>
        dependency.kind == BendDependencyKind.Import &&
          dependency.label.contains("Dep, Other")
      )
    )
    assertTrue(
      outgoing.dependencies.exists(_.kind == BendDependencyKind.Call)
    )
    assertTrue(
      outgoing.dependencies.exists(_.kind == BendDependencyKind.UnknownCall)
    )
    assertTrue(outgoing.unresolvedNamedCalls >= 1)
    assertEquals(
      BendSymbolCategory.Definition,
      BendSourceSymbols
        .declarations(dependency)
        .find(_.name == "target")
        .get
        .category
    )

  def testCycleIsBoundedAndConfiguredBaseIsAResolvedLibraryEdge(): Unit =
    val cycleRoot = myFixture.addFileToProject(
      "cycle/a.bend",
      "import ./b.bend as B\ndef a() -> U32:\n  1\n"
    )
    myFixture.addFileToProject(
      "cycle/b.bend",
      "import ./a.bend as A\ndef b() -> U32:\n  2\n"
    )
    val cycleReport = BendStaticDependencies.inspect(cycleRoot).toOption.get
    assertTrue(
      cycleReport.dependencies.exists(dependency =>
        dependency.kind == BendDependencyKind.Import &&
          dependency.label.contains("b.bend")
      )
    )
    assertTrue(cycleReport.dependencies.size < 20)
    val root = myFixture.addFileToProject(
      "app/base-use.bend",
      "import Base\ndef main() -> IO(Unit):\n  IO.print(\"hello\")\n"
    )
    PsiDocumentManager.getInstance(getProject).commitAllDocuments()
    val report = BendStaticDependencies.inspect(root).toOption.get
    assertTrue(
      report.dependencies.exists(dependency =>
        dependency.kind == BendDependencyKind.Import &&
          dependency.label.contains("base.bend")
      )
    )
    assertTrue(
      report.dependencies.exists(dependency =>
        dependency.kind == BendDependencyKind.Call &&
          dependency.label.contains("IO.print")
      )
    )
    assertTrue(report.dependencies.toString, report.dependencies.size < 20)
