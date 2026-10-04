package com.dearlordylord.bend.idea.features.editing

import com.dearlordylord.bend.idea.symbols.api.{
  BendSourceSymbols,
  BendSourceResolution
}
import com.intellij.codeInsight.folding.CodeFoldingManager
import com.intellij.ide.structureView.TreeBasedStructureViewBuilder
import com.intellij.lang.LanguageStructureViewBuilder
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.psi.{PsiFile, PsiFileFactory, PsiDocumentManager}
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.*

/** Original break/repair controls, motivated by the pinned Tree-sitter recovery
  * policy; these assert native Bend structure rather than another parser's
  * tree.
  */
final class BendRecoveryRegressionTest extends BasePlatformTestCase:
  private val prefix = "def anchor(seed: Nat) -> Nat:\n  seed\n"
  private val suffix = "def neighbor(item: Nat) -> Nat:\n  item\n"

  private def structure(file: PsiFile) =
    BendSourceSymbols.declarations(file).map { symbol =>
      (
        symbol.name,
        symbol.category.toString,
        symbol.signature.source,
        symbol.signature.parameters.map(p => (p.name, p.source))
      )
    }

  private def usefulNeighbor(file: PsiFile): Unit =
    val symbols = BendSourceSymbols.declarations(file)
    assertTrue(symbols.exists(_.name == "anchor"))
    assertTrue(symbols.exists(_.name == "neighbor"))
    val use = file.getText.indexOf("\n  seed") + 3
    BendSourceSymbols.resolveCurrentFile(file, use, "seed") match
      case BendSourceResolution.ResolvedBinder(binding) =>
        assertEquals("seed", binding.name)
        assertEquals(
          file.getText.indexOf("seed: Nat"),
          binding.handle.nameOffset
        )
      case other => fail(s"Surviving anchor parameter lost: $other")
    val reference = file.findReferenceAt(use)
    assertNotNull(reference)
    val target = reference.resolve()
    assertNotNull(target)
    assertEquals(file.getText.indexOf("seed: Nat"), target.getTextOffset)
    val builder = LanguageStructureViewBuilder.getInstance
      .getStructureViewBuilder(file)
      .asInstanceOf[TreeBasedStructureViewBuilder]
    val model = builder.createStructureViewModel(myFixture.getEditor)
    try
      val labels = model.getRoot.getChildren.toList
        .map(_.getPresentation.getPresentableText)
      assertTrue(labels.exists(_.startsWith("def anchor")))
      assertTrue(labels.exists(_.startsWith("def neighbor")))
    finally model.dispose()
    CodeFoldingManager
      .getInstance(getProject)
      .updateFoldRegions(myFixture.getEditor)
    myFixture.performEditorAction(IdeActions.ACTION_COLLAPSE_ALL_REGIONS)
    assertTrue(
      myFixture.getEditor.getFoldingModel.getAllFoldRegions.exists { region =>
        region.getStartOffset == prefix.indexOf(
          ':',
          prefix.indexOf("->")
        ) + 1 && !region.isExpanded
      }
    )

  private def breakRepair(
      good: String,
      fragment: String,
      broken: String
  ): Unit =
    val source = prefix + good + suffix
    val file = myFixture.configureByText("repair.bend", source)
    myFixture.performEditorAction(IdeActions.ACTION_EXPAND_ALL_REGIONS)
    val start = source.indexOf(fragment, prefix.length)
    assertTrue(start >= prefix.length)
    myFixture.getEditor.getSelectionModel
      .setSelection(start, start + fragment.length)
    myFixture.`type`(broken)
    PsiDocumentManager.getInstance(getProject).commitAllDocuments()
    assertEquals(
      source.substring(0, start) + broken + source.substring(
        start + fragment.length
      ),
      file.getText
    )
    usefulNeighbor(file)
    val damaged =
      BendSourceSymbols.declarations(file).find(_.name == "edited").get
    assertTrue(damaged.signature.parameters.exists(_.name == "value"))
    assertFalse(damaged.signature.parameters.exists(_.name == "neighbor"))
    myFixture.performEditorAction(IdeActions.ACTION_EXPAND_ALL_REGIONS)
    myFixture.getEditor.getSelectionModel
      .setSelection(start, start + broken.length)
    myFixture.`type`(fragment)
    PsiDocumentManager.getInstance(getProject).commitAllDocuments()
    assertEquals(source, file.getText)
    val fresh = PsiFileFactory
      .getInstance(getProject)
      .createFileFromText("fresh.bend", file.getFileType, source)
    assertEquals(structure(fresh), structure(file))
    val bodyUse = if good.contains("\n  value\n") then
      source.indexOf("\n  value\n") + 3
    else if good.contains("match value:") then
      source.indexOf("match value:") + 6
    else -1
    if bodyUse >= 0 then
      def target(current: PsiFile): Int =
        BendSourceSymbols.resolveCurrentFile(current, bodyUse, "value") match
          case BendSourceResolution.ResolvedBinder(binding) =>
            binding.handle.nameOffset
          case other =>
            throw new AssertionError(
              s"Repaired value target unavailable: $other"
            )
      assertEquals(source.indexOf("value: "), target(file))
      assertEquals(target(fresh), target(file))
    usefulNeighbor(file)

  def testUnsavedParameterAndTypeApplicationRepair(): Unit =
    breakRepair(
      "def edited(value: Nat, spare: Nat) -> Nat:\n  value\n",
      ") ->",
      " ->"
    )
    breakRepair("def edited(value: List<&2, Nat>) -> Nat:\n  0n\n", ">)", ")")

  def testUnsavedSuffixHeaderAndNestedMatchRepair(): Unit =
    breakRepair("def edited(value: Nat) -> Nat:\n  value\n", "Nat:", "Nat!:")
    breakRepair(
      "def edited(value: Nat) -> Nat:\n  value\n",
      ") -> Nat:",
      ") Nat:"
    )
    breakRepair(
      "def edited(value: Nat) -> Nat:\n  match value:\n    case 0n: 0n\n    case 1n+outer:\n      match outer:\n        case 0n: 0n\n        case 1n+inner: inner\n",
      "match outer:",
      "match outer"
    )

  def testUnterminatedLiteralRepairDoesNotInventDeclarations(): Unit =
    val source =
      prefix + suffix + "def edited(value: Nat) -> String:\n  \"first\ndef imaginary(): fake\nlast\"\n"
    val file = myFixture.configureByText("literal-repair.bend", source)
    myFixture.performEditorAction(IdeActions.ACTION_EXPAND_ALL_REGIONS)
    val quote = source.lastIndexOf('"')
    myFixture.getEditor.getSelectionModel.setSelection(quote, quote + 1)
    myFixture.performEditorAction(IdeActions.ACTION_EDITOR_DELETE)
    PsiDocumentManager.getInstance(getProject).commitAllDocuments()
    usefulNeighbor(file)
    assertFalse(
      BendSourceSymbols.declarations(file).exists(_.name == "imaginary")
    )
    myFixture.getEditor.getCaretModel.moveToOffset(quote)
    myFixture.`type`("\"")
    PsiDocumentManager.getInstance(getProject).commitAllDocuments()
    assertEquals(source, file.getText)
    val fresh = PsiFileFactory
      .getInstance(getProject)
      .createFileFromText("fresh-literal.bend", file.getFileType, source)
    assertEquals(structure(fresh), structure(file))
    assertFalse(
      BendSourceSymbols.declarations(file).exists(_.name == "imaginary")
    )
    usefulNeighbor(file)
