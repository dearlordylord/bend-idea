package com.dearlordylord.bend.idea.features.navigation

import com.dearlordylord.bend.idea.adapters.cli.RealBendCompilerFixture
import com.dearlordylord.bend.idea.symbols.api.BendSymbolCategory
import com.dearlordylord.bend.idea.symbols.api.BendSourceSymbols
import com.dearlordylord.bend.idea.syntax.psi.BendDefinition
import com.dearlordylord.bend.idea.toolchain.api.{
  BendToolchainChoices,
  BendToolchainSettings
}
import com.dearlordylord.bend.idea.workspace.api.BendLoadingConfiguration
import com.intellij.openapi.application.{ApplicationManager, ReadAction}
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.psi.{PsiDocumentManager, PsiElement, SmartPsiElementPointer}
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
        (0 until 12)
          .map(index => s"def caller$index() -> U32:\n  Dep.target()\n")
          .mkString +
        "def unknown_caller() -> U32:\n  dynamic_target()\n"
    )
    myFixture.openFileInEditor(root.getVirtualFile)
    val document = myFixture.getEditor.getDocument
    val otherRoot = myFixture.addFileToProject(
      "second/main.bend",
      "import ../lib/dep.bend as D\ndef other_root() -> U32:\n  D.target()\n"
    )
    assertTrue(otherRoot.getText.contains("other_root"))
    PsiDocumentManager.getInstance(getProject).commitAllDocuments()

    val otherRootReport = BendStaticDependencies.inspect(otherRoot).toOption.get
    assertTrue(
      otherRootReport.dependencies.toString,
      otherRootReport.dependencies.exists(dependency =>
        dependency.kind == BendDependencyKind.Call &&
          dependency.label.contains("target") &&
          pointerText(dependency.navigation).contains("target")
      )
    )

    val target = PsiTreeUtil
      .findChildrenOfType(dependency, classOf[BendDefinition])
      .stream()
      .filter(_.getName == "target")
      .findFirst()
      .orElse(null)
    assertNotNull(target)
    val targetCall = PsiTreeUtil
      .findChildrenOfType(
        root,
        classOf[com.dearlordylord.bend.idea.syntax.psi.BendReferenceElement]
      )
      .stream()
      .filter(_.getText == "Dep.target")
      .findFirst()
      .orElse(null)
    assertNotNull(targetCall)
    assertTrue(
      targetCall.getReferences.exists(
        _.resolve() == target.getNameIdentifier
      )
    )

    val savedIncoming = BendStaticDependencies.inspect(target).toOption.get
    val savedCalledBy = savedIncoming.dependencies.filter(
      _.kind == BendDependencyKind.CalledBy
    )
    val savedOutgoing = BendStaticDependencies.inspect(root).toOption.get
    val savedUnknownCalls = savedOutgoing.unresolvedNamedCalls

    val callOffset = document.getText.indexOf("Dep.target()")
    assertTrue("expected a saved call edge", callOffset >= 0)
    com.intellij.openapi.command.WriteCommandAction
      .writeCommandAction(getProject, root)
      .run(
        new com.intellij.util.ThrowableRunnable[RuntimeException]:
          override def run(): Unit =
            document.replaceString(
              callOffset,
              callOffset + "Dep.target()".length,
              "Dep.unresolved()"
            )
      )
    PsiDocumentManager.getInstance(getProject).commitAllDocuments()

    val incoming = BendStaticDependencies.inspect(target).toOption.get
    val calledBy = incoming.dependencies.filter(
      _.kind == BendDependencyKind.CalledBy
    )
    assertEquals(savedCalledBy.size - 1, calledBy.size)
    assertTrue(
      calledBy.headOption.exists(dependency =>
        pointerText(dependency.navigation).contains("target")
      )
    )
    assertTrue(incoming.coverage.contains("not a complete runtime call graph"))

    val outgoing = BendStaticDependencies.inspect(root).toOption.get
    assertEquals(savedUnknownCalls + 1, outgoing.unresolvedNamedCalls)
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

  def testReportIsInvalidatedWhenLoadingSettingsChange(): Unit =
    val root = myFixture.addFileToProject(
      "settings/main.bend",
      "def main() -> U32:\n  0\n"
    )
    val action = new BendInspectDependenciesAction
    val report = BendStaticDependencies.inspect(root).toOption.get
    assertEquals(
      getProject
        .getService(classOf[BendLoadingConfiguration])
        .configurationRevision,
      report.loadingConfigurationRevision
    )
    assertTrue(action.reportIsCurrent(getProject, report))

    val settings = ApplicationManager.getApplication.getService(
      classOf[BendToolchainSettings]
    )
    val current = settings.choices
    try
      settings.update(
        current.copy(packageCache = current.packageCache + "/next")
      )
      assertFalse(
        "A report from the previous loading configuration must be rejected",
        action.reportIsCurrent(getProject, report)
      )
    finally settings.update(current)

  def testActionBoundaryCommitsDirtyNonSelectedCallerBeforeInspection(): Unit =
    val dependency = myFixture.addFileToProject(
      "lib/selected.bend",
      "def target() -> U32:\n  1\n"
    )
    val caller = myFixture.addFileToProject(
      "app/caller.bend",
      "import ../lib/selected.bend as Dep\n" +
        "def caller() -> U32:\n  Dep.target()\n"
    )
    myFixture.openFileInEditor(caller.getVirtualFile)
    val callerDocument = myFixture.getEditor.getDocument
    myFixture.openFileInEditor(dependency.getVirtualFile)

    val originalCall = "Dep.target()"
    val callOffset = callerDocument.getText.indexOf(originalCall)
    assertTrue(callOffset >= 0)
    WriteCommandAction
      .writeCommandAction(getProject, caller)
      .run(
        new com.intellij.util.ThrowableRunnable[RuntimeException]:
          override def run(): Unit =
            callerDocument.replaceString(
              callOffset,
              callOffset + originalCall.length,
              "Dep.missing()"
            )
      )
    assertTrue(
      "Caller edit should still be uncommitted before the action boundary",
      PsiDocumentManager.getInstance(getProject).isUncommited(callerDocument)
    )

    new BendInspectDependenciesAction().commitOpenDocuments(getProject)

    assertFalse(
      PsiDocumentManager.getInstance(getProject).isUncommited(callerDocument)
    )
    val target = PsiTreeUtil
      .findChildrenOfType(dependency, classOf[BendDefinition])
      .stream()
      .filter(_.getName == "target")
      .findFirst()
      .orElse(null)
    assertNotNull(target)
    val incoming = BendStaticDependencies.inspect(target).toOption.get
    assertFalse(
      "Dependency report must not retain an edge from the edited caller",
      incoming.dependencies.exists(dependency =>
        dependency.kind == BendDependencyKind.CalledBy &&
          pointerText(dependency.navigation).contains("caller")
      )
    )
    val outgoing = BendStaticDependencies.inspect(caller).toOption.get
    assertTrue(outgoing.unresolvedNamedCalls > 0)

    val changedCallOffset = callerDocument.getText.indexOf("Dep.missing()")
    assertTrue(changedCallOffset >= 0)
    WriteCommandAction
      .writeCommandAction(getProject, caller)
      .run(
        new com.intellij.util.ThrowableRunnable[RuntimeException]:
          override def run(): Unit =
            callerDocument.replaceString(
              changedCallOffset,
              changedCallOffset + "Dep.missing()".length,
              "Dep.changed()"
            )
      )
    assertFalse(
      "A report must become stale while an open document is uncommitted",
      new BendInspectDependenciesAction().reportIsCurrent(getProject, outgoing)
    )

  private def pointerText(
      pointer: SmartPsiElementPointer[PsiElement]
  ): String =
    ReadAction.compute(() =>
      Option(pointer.getElement).map(_.getText).getOrElse("")
    )
