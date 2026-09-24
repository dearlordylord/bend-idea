package com.dearlordylord.bend.idea.features.proofs

import com.dearlordylord.bend.idea.symbols.api.{
  BendSourceSymbols,
  BendSymbolCategory
}
import com.dearlordylord.bend.idea.analysis.model.BendCheckingStatus
import com.dearlordylord.bend.idea.syntax.psi.{
  BendLaw,
  BendProofForm,
  BendProofSurface
}
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.*

final class BendProofNavigationTest extends BasePlatformTestCase:
  def testLawAndFillLinksStayInsideEachSelectedRoot(): Unit =
    val lawFile = myFixture.addFileToProject(
      "shared/LAWS.bend",
      "law claim:\n  Type\n"
    )
    val firstRoot = myFixture.addFileToProject(
      "proof-one/PROOF.bend",
      "import ../shared/LAWS.bend as Laws\ndef Laws.claim():\n  ?TODO\n"
    )
    val secondRoot = myFixture.addFileToProject(
      "proof-two/PROOF.bend",
      "import ../shared/LAWS.bend as Claim\ndef Claim.claim():\n  ?TODO\n"
    )
    val law = BendSourceSymbols
      .declarations(lawFile)
      .find(_.category == BendSymbolCategory.Law)
      .get
    val fills = BendProofNavigation.destinations(
      lawFile,
      law,
      List(
        firstRoot.getVirtualFile.getPath,
        secondRoot.getVirtualFile.getPath
      )
    )
    assertEquals(2, fills.size)
    assertEquals(
      Set(
        firstRoot.getVirtualFile.getPath,
        secondRoot.getVirtualFile.getPath
      ),
      fills.map(_.rootPath).toSet
    )
    assertTrue(fills.forall(_.symbol.category == BendSymbolCategory.Definition))
    assertTrue(fills.forall(_.status == BendCheckingStatus.Unchecked))

    val firstFill = BendSourceSymbols
      .declarations(firstRoot)
      .find(_.name == "Laws.claim")
      .get
    val lawBack = BendProofNavigation.destinations(
      firstRoot,
      firstFill,
      List(firstRoot.getVirtualFile.getPath)
    )
    assertEquals(1, lawBack.size)
    assertEquals(
      lawFile.getVirtualFile.getPath,
      lawBack.head.target.getContainingFile.getVirtualFile.getPath
    )

  def testUnfilledLawHasNoCandidateTarget(): Unit =
    val lawFile = myFixture.addFileToProject(
      "shared/LAWS.bend",
      "law unfinished:\n  Type\n"
    )
    val root = myFixture.addFileToProject(
      "PROOF.bend",
      "import ./shared/LAWS.bend as Laws\ndef main() -> Type:\n  Type\n"
    )
    val law = BendSourceSymbols
      .declarations(lawFile)
      .find(_.category == BendSymbolCategory.Law)
      .get
    assertTrue(
      BendProofNavigation
        .destinations(lawFile, law, List(root.getVirtualFile.getPath))
        .isEmpty
    )

  def testSingleStaleRootCandidateRequiresVisibleStatusChoice(): Unit =
    val _ = myFixture.addFileToProject(
      "shared/LAWS.bend",
      "law claim:\n  Type\n"
    )
    val root = myFixture.addFileToProject(
      "proof/PROOF.bend",
      "import ../shared/LAWS.bend as Laws\ndef Laws.claim():\n  ?TODO\n"
    )
    val fill = BendSourceSymbols
      .declarations(root)
      .find(_.name == "Laws.claim")
      .get
    val destination = BendProofDestination(
      root.getVirtualFile.getPath,
      BendSourceSymbols.fileId(root),
      fill,
      fill.declaration,
      BendCheckingStatus.Stale
    )
    assertTrue(
      "A single stale candidate must show its root/status in the chooser",
      BendProofNavigation.requiresStatusChoice(List(destination))
    )
    assertTrue(destination.toString.contains("Check stale"))

  def testLawAndCandidateFillDeclarationsHaveGutterLinks(): Unit =
    val lawFile = myFixture.addFileToProject(
      "law.bend",
      "# preceding declaration\nlaw claim:\n  Type\n"
    )
    val proofFile = myFixture.addFileToProject(
      "PROOF.bend",
      "def main():\n  0\ndef Laws.claim():\n  ?TODO\n"
    )
    val provider = new BendProofLineMarkerProvider
    val law = PsiTreeUtil.findChildOfType(lawFile, classOf[BendLaw])
    val fill = BendSourceSymbols
      .declarations(proofFile)
      .find(symbol =>
        symbol.category == BendSymbolCategory.Definition &&
          symbol.name == "Laws.claim"
      )
      .map(_.declaration)
      .orNull
    assertNotNull(law)
    assertNotNull(fill)
    val lawMarker = provider.getLineMarkerInfo(
      law.getNameIdentifier.getFirstChild
    )
    val fillMarker = provider.getLineMarkerInfo(
      fill.getNameIdentifier.getFirstChild
    )
    assertNotNull(lawMarker)
    assertNotNull(fillMarker)
    assertTrue(lawMarker.getLineMarkerTooltip.contains("candidate fills"))
    assertTrue(fillMarker.getLineMarkerTooltip.contains("matching law"))
    assertEquals(
      law.getNameIdentifier.getFirstChild.getTextRange.getStartOffset,
      lawMarker.startOffset
    )
    assertEquals(
      fill.getNameIdentifier.getFirstChild.getTextRange.getStartOffset,
      fillMarker.startOffset
    )

  def testNextAndPreviousActionsNavigateOnlyToSourceHoles(): Unit =
    val source =
      "def main():\n  \"?\"\n  \"?TODO\"\n  # ?comment\n  ?named\n  ?TODO\n"
    myFixture.configureByText("holes.bend", source)
    val forms = BendProofSurface.scan(myFixture.getFile.getText)
    assertEquals(
      List("named", "TODO"),
      forms.collect { case hole: BendProofForm.Hole => hole.name }
    )
    val bounded = BendProofSurface
      .scanBounded(
        myFixture.getFile.getText,
        0,
        8,
        1,
        () => false
      )
      .get
    assertTrue(
      "The token budget truncates a large source projection",
      bounded.truncated
    )
    assertTrue(bounded.forms.size <= 1)
    var cancellationChecks = 0
    assertTrue(
      "Proof scans check cancellation during tokenization",
      BendProofSurface
        .scanBounded(
          myFixture.getFile.getText,
          0,
          10000,
          100,
          () =>
            cancellationChecks += 1
            cancellationChecks > 3
        )
        .isEmpty
    )
    val first = source.indexOf("?named")
    val second = source.indexOf("?TODO", source.indexOf("?named"))
    myFixture.getEditor.getCaretModel.moveToOffset(0)
    myFixture.performEditorAction("Bend.NextProofHole")
    assertEquals(first, myFixture.getEditor.getCaretModel.getOffset)
    myFixture.performEditorAction("Bend.NextProofHole")
    assertEquals(second, myFixture.getEditor.getCaretModel.getOffset)
    myFixture.performEditorAction("Bend.PreviousProofHole")
    assertEquals(first, myFixture.getEditor.getCaretModel.getOffset)

    WriteCommandAction.runWriteCommandAction(
      getProject,
      new Runnable:
        override def run(): Unit = myFixture.getEditor.getDocument.setText(
          "def main():\n  ?fresh_name\n"
        )
    )
    PsiDocumentManager.getInstance(getProject).commitAllDocuments()
    myFixture.getEditor.getCaretModel.moveToOffset(0)
    myFixture.performEditorAction("Bend.NextProofHole")
    assertEquals(
      "Navigation must use the current unsaved hole map after an edit",
      "def main():\n  ?fresh_name\n".indexOf("?fresh_name"),
      myFixture.getEditor.getCaretModel.getOffset
    )

  def testNamedAndTodoHoleOffsetsWrap(): Unit =
    val source = "?alpha\n?TODO\n"
    val first = source.indexOf("?alpha")
    val last = source.indexOf("?TODO")
    assertEquals(
      Some(first),
      BendHoleNavigation.nextOffset(source, source.length)
    )
    assertEquals(Some(last), BendHoleNavigation.previousOffset(source, 0))
