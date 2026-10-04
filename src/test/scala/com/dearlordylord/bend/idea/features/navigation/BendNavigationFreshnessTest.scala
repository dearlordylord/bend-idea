package com.dearlordylord.bend.idea.features.navigation

import com.dearlordylord.bend.idea.symbols.api.BendImportedSymbolCatalog
import com.dearlordylord.bend.idea.workspace.api.BendWorkspaceGraph
import com.dearlordylord.bend.idea.workspace.model.BendSourceRecord
import com.intellij.codeInsight.navigation.actions.GotoDeclarationAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.ServiceContainerUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*

final class BendNavigationFreshnessTest extends BasePlatformTestCase:
  def testPendingDependencyCaptureDoesNotHideUnsavedEditOrBlockUnrelatedNavigation()
      : Unit =
    val dependency =
      myFixture.addFileToProject("lib.bend", "def old_answer():\n  Type\n")
    val importer = myFixture.addFileToProject(
      "main.bend",
      "import ./lib.bend as M\ndef main():\n  M.new_answer()\n"
    )
    val unrelated = myFixture.addFileToProject(
      "other.bend",
      "def local():\n  Type\ndef use():\n  local()\n"
    )
    val delegate = getProject.getService(classOf[BendWorkspaceGraph])
    val entered = new CountDownLatch(1)
    val release = new CountDownLatch(1)
    val first = new AtomicBoolean(true)
    val gated = new BendWorkspaceGraph:
      override def load(
          root: BendSourceRecord,
          base: String,
          cache: String,
          canceled: () => Boolean
      ) =
        if root.path.endsWith("/main.bend") && first.compareAndSet(true, false)
        then
          entered.countDown()
          assertTrue(
            "Release the pending capture",
            release.await(10, TimeUnit.SECONDS)
          )
        delegate.load(root, base, cache, canceled)
      override def siblingLaws(path: String) = delegate.siblingLaws(path)
    ServiceContainerUtil.replaceService(
      getProject,
      classOf[BendWorkspaceGraph],
      gated,
      getTestRootDisposable
    )
    val pending = ApplicationManager.getApplication.executeOnPooledThread(
      new java.util.concurrent.Callable[Boolean]:
        override def call(): Boolean =
          getProject
            .getService(classOf[BendImportedSymbolCatalog])
            .navigationSnapshot(importer, "", "")
            .direct
            .exists(_._1 == "M.new_answer")
    )
    try
      assertTrue(
        "Dependency loading started",
        entered.await(5, TimeUnit.SECONDS)
      )
      val manager = PsiDocumentManager.getInstance(getProject)
      val document = manager.getDocument(dependency)
      WriteCommandAction.runWriteCommandAction(
        getProject,
        new Runnable:
          override def run(): Unit =
            document.setText("def new_answer():\n  Type\n")
      )
      manager.commitDocument(document)
      myFixture.configureFromExistingVirtualFile(importer.getVirtualFile)
      myFixture.getEditor.getCaretModel.moveToOffset(
        importer.getText.indexOf("new_answer") + 2
      )
      val importedTarget = GotoDeclarationAction.findTargetElement(
        getProject,
        myFixture.getEditor,
        myFixture.getCaretOffset
      )
      assertNotNull(
        "Immediate native navigation reads the edited dependency",
        importedTarget
      )
      assertEquals("new_answer", importedTarget.getText)
      assertEquals(
        dependency.getVirtualFile,
        importedTarget.getContainingFile.getVirtualFile
      )
      myFixture.configureFromExistingVirtualFile(unrelated.getVirtualFile)
      myFixture.getEditor.getCaretModel.moveToOffset(
        unrelated.getText.lastIndexOf("local") + 2
      )
      val localTarget = GotoDeclarationAction.findTargetElement(
        getProject,
        myFixture.getEditor,
        myFixture.getCaretOffset
      )
      assertNotNull(
        "Unrelated native navigation proceeds before pending load is released",
        localTarget
      )
      assertEquals("local", localTarget.getText)
      assertFalse(pending.isDone)
    finally release.countDown()
    assertTrue(
      "Pending capture also reads current unsaved source",
      pending.get(5, TimeUnit.SECONDS)
    )
