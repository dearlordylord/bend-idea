package com.dearlordylord.bend.idea.features.formatting

import com.dearlordylord.bend.idea.adapters.cli.{
  BendCliCheckBackend,
  RealBendCompilerFixture
}
import com.dearlordylord.bend.idea.analysis.model.*
import com.dearlordylord.bend.idea.model.FileId
import com.dearlordylord.bend.idea.toolchain.api.BendToolchainSelection
import com.dearlordylord.bend.idea.symbols.api.{
  BendSourceSymbols,
  BendSourceResolution,
  BendSymbolCategory
}
import com.intellij.psi.PsiFile
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.*
import java.nio.file.Files

/** Independently authored preventive adjacency cases. Provenance and the
  * mutation each assertion detects are recorded in docs/regression-fixtures.md.
  */
final class BendAdjacencyRegressionTest extends BasePlatformTestCase:
  private def check(
      text: String,
      expected: BendCheckOutcome = BendCheckOutcome.Success
  ): Unit =
    val compiler = RealBendCompilerFixture.inputs
    val directory = Files.createTempDirectory("bend-adjacency-")
    try
      val executable = compiler.writeLauncher(directory.resolve("bend"))
      val root = directory.resolve("root.bend")
      Files.writeString(root, text)
      val result = new BendCliCheckBackend(directory).check(
        BendCheckSnapshot(
          new FileId(root.toString, false),
          root.toString,
          text,
          1L,
          BendToolchainSelection(
            executable.toString,
            compiler.base.toString,
            "",
            true,
            1L
          )
        )
      )
      assertEquals(result.details, expected, result.outcome)
      if expected == BendCheckOutcome.Success then
        assertEquals(BendCompleteness.Complete, result.completeness)
        assertEquals(BendReliance.None, result.reliance)
      else
        assertEquals(BendCompleteness.Unknown, result.completeness)
        assertEquals(BendReliance.Unknown, result.reliance)
    finally
      val paths = Files.walk(directory)
      try
        paths
          .sorted(java.util.Comparator.reverseOrder())
          .forEach(p => { val _ = Files.deleteIfExists(p) })
      finally paths.close()

  def testSignedExponentsKeepNeighboringSubtractionAndUndo(): Unit =
    val source =
      "import Base\ndef scale(x: F32,y: F32) -> F32:\n    (x - 3.25e-4 + 7.5e+2 - y : F32)\n"
    for text <- List(source, source.replace("\n", "\r\n")) do
      check(text)
      myFixture.configureByText("exponents.bend", text)
      myFixture.performEditorAction(IdeActions.ACTION_EDITOR_REFORMAT)
      val formatted = myFixture.getEditor.getDocument.getText
      assertTrue(formatted.contains("3.25e-4"))
      assertTrue(formatted.contains("7.5e+2"))
      assertTrue(formatted.contains("x - 3.25e-4"))
      assertTrue(formatted.contains("7.5e+2 - y"))
      check(formatted)
      myFixture.performEditorAction(IdeActions.ACTION_EDITOR_REFORMAT)
      assertEquals(formatted, myFixture.getEditor.getDocument.getText)
      myFixture.performEditorAction(IdeActions.ACTION_UNDO)
      assertEquals(source, myFixture.getEditor.getDocument.getText)

  def testUnaryNegativeLiteralRetainsLexicalFormAndCompilerRejection(): Unit =
    // Bend 2.0.35 does not support unary minus on a numeric literal: its
    // parser expects a binder name after -. Keep that limitation observable.
    val source = "import Base\ndef negative() -> F32:\n    -2.5e-3\n"
    check(source, BendCheckOutcome.Failed)
    myFixture.configureByText("unary-control.bend", source)
    myFixture.performEditorAction(IdeActions.ACTION_EDITOR_REFORMAT)
    val formatted = myFixture.getEditor.getDocument.getText
    assertTrue(formatted.contains("-2.5e-3"))
    check(formatted, BendCheckOutcome.Failed)
    myFixture.performEditorAction(IdeActions.ACTION_UNDO)
    assertEquals(source, myFixture.getEditor.getDocument.getText)

  private def binder(
      file: PsiFile,
      body: String,
      name: String,
      declaration: String
  ): Unit =
    val offset = file.getText.indexOf(body) + body.lastIndexOf(name)
    val expected =
      file.getText.indexOf(declaration) + declaration.lastIndexOf(name)
    BendSourceSymbols.resolveCurrentFile(file, offset, name) match
      case BendSourceResolution.ResolvedBinder(binding) =>
        assertEquals(name, binding.name)
        assertEquals(expected, binding.handle.nameOffset)
      case other => fail(s"Expected $name binder at $body; got $other")
    myFixture.getEditor.getCaretModel.moveToOffset(offset)
    val reference = myFixture.getReferenceAtCaretPosition()
    assertNotNull(reference)
    val target = reference.resolve()
    assertNotNull(target)
    assertEquals(expected, target.getTextOffset)

  private def preserve(source: String)(assertRole: PsiFile => Unit): Unit =
    check(source)
    val file = myFixture.configureByText("adjacency.bend", source)
    assertRole(file)
    myFixture.performEditorAction(IdeActions.ACTION_EDITOR_REFORMAT)
    val formatted = file.getText
    assertRole(file)
    check(formatted)
    myFixture.performEditorAction(IdeActions.ACTION_UNDO)
    assertEquals(source, myFixture.getEditor.getDocument.getText)
    myFixture.performEditorAction(IdeActions.ACTION_EDITOR_REFORMAT)
    assertEquals(formatted, file.getText)
    myFixture.performEditorAction(IdeActions.ACTION_EDITOR_REFORMAT)
    assertEquals(formatted, file.getText)

  def testSuccessorReusableBinderAndAdditionControl(): Unit =
    val source =
      "import Base\ndef predecessor(n: Nat) -> Nat:\n  match n:\n    case 0n: 0n\n    case 1n+p: p\ndef reused(n: Nat) -> Nat:\n  match n:\n    case 0n: 0n\n    case 1n++r: r\ndef sum(n: Nat) -> Nat:\n    (1n + n : Nat)\n"
    preserve(source) { file =>
      binder(file, ": p", "p", "1n+p")
      binder(file, ": r", "r", "1n++r")
      binder(file, "+ n : Nat", "n", "sum(n")
      assertTrue(file.getText.contains("1n+p"))
      assertTrue(file.getText.contains("1n++r"))
      assertTrue(file.getText.contains("1n + n"))
    }
    for name <- List("p", "r") do
      val text = myFixture.getFile.getText
      val at = text.indexOf(if name == "p" then "1n+p" else "1n++r")
      myFixture.getEditor.getSelectionModel.removeSelection()
      myFixture.getEditor.getCaretModel.moveToOffset(at + 3)
      val selections = (0 until 6).map { _ =>
        myFixture.performEditorAction("EditorSelectWord")
        myFixture.getEditor.getSelectionModel.getSelectedText
      }
      assertTrue(
        selections.toString,
        selections.exists(s =>
          s != null && s.contains(if name == "p" then "1n+p" else "1n++r")
        )
      )
      assertEquals(text, myFixture.getEditor.getDocument.getText)

  def testQuantitiesErasedTemplatesAndContinuedParallelValues(): Unit =
    val source =
      "import Base\ndef gather(~A: Type,-B: Type,x: A) -> A:\n    x\ndef batch(x: U32,y: U32) -> U32:\n  a b = x\n    y\n  (a + b : U32)\ndef singleton() -> List<&2, U32>:\n  [9]\ndef picked() -> U32:\n  gather(~U32, U32, 6)\n"
    preserve(source) { file =>
      val gather =
        BendSourceSymbols.declarations(file).find(_.name == "gather").get
      assertEquals(List("A", "B", "x"), gather.signature.parameters.map(_.name))
      assertTrue(gather.signature.parameters.head.template)
      assertEquals(Some('-'), gather.signature.parameters(1).quantity)
      binder(file, "x\ndef batch", "x", "x: A")
      binder(file, "(a +", "a", "a b =")
      binder(file, "+ b :", "b", "a b =")
      assertTrue(file.getText.contains("-B: Type"))
      assertTrue(file.getText.contains("List<&2, U32>"))
      assertTrue(file.getText.contains("gather(~U32, U32, 6)"))
      assertTrue(file.getText.contains("a b = x\n    y"))
    }

  def testFixedDotOperatorAndCallSuffixKeepDottedDeclarationIdentity(): Unit =
    val source =
      "import Base\ndef Relay.pass(x: U32) -> U32:\n    x\ndef flagged(x: U32) -> U32:\n  Relay.pass!(x)\ndef ordinary(x: U32) -> U32:\n  (Relay.pass(x) .&. 7 : U32)\n"
    preserve(source) { file =>
      val declaration =
        BendSourceSymbols.declarations(file).find(_.name == "Relay.pass").get
      assertEquals(BendSymbolCategory.Definition, declaration.category)
      for offset <- List(
          file.getText.indexOf("Relay.pass!"),
          file.getText.lastIndexOf("Relay.pass(")
        )
      do
        BendSourceSymbols.resolveCurrentFile(file, offset, "Relay.pass") match
          case BendSourceResolution.Resolved(symbol) =>
            assertEquals(declaration.handle, symbol.handle)
          case other => fail(s"Dotted definition target lost: $other")
      assertTrue(file.getText.contains("Relay.pass!(x)"))
      assertTrue(file.getText.contains(".&."))
    }

  def testInlineMonadicBodyAndMixedWhitespaceRefusal(): Unit =
    val source =
      "import Base\ndef quiet() -> IO(Unit):\n    do IO<Unit>: IO.pure(Unit, Unit{})\ndef witness() -> {9 == 9 : U32}:\n  {==}\n"
    preserve(source) { file =>
      assertTrue(file.getText.contains("do IO<Unit>: IO.pure(Unit, Unit{})"))
      assertEquals(
        List("quiet", "witness"),
        BendSourceSymbols.declarations(file).map(_.name)
      )
    }
    val unsafe =
      "import Base\ndef mixed() -> U32:\n \t1\ndef unchanged(a: U32,b: U32) -> U32:\n  a\n"
    check(unsafe)
    myFixture.configureByText("unsafe-layout.bend", unsafe)
    myFixture.performEditorAction(IdeActions.ACTION_EDITOR_REFORMAT)
    assertEquals(unsafe, myFixture.getEditor.getDocument.getText)
    check(unsafe)

  def testNestedInlineCasesPreserveSiblingDedentAndFakeCommentHeader(): Unit =
    val source =
      "import Base\n# case Phantom{}: do IO<Unit>: preserve this exact comment\ndef choose(a: Bool,b: Bool) -> Bool:\n  match a:\n    case True{}:\n      match b:\n        case True{}: True{}\n        case False{}: False{}\n    case False{}: False{}\ndef witness() -> {4 == 4 : U32}:\n  {==}\n"
    preserve(source) { file =>
      assertTrue(
        file.getText.contains(
          "# case Phantom{}: do IO<Unit>: preserve this exact comment\n"
        )
      )
      assertTrue(
        file.getText.contains(
          "        case False{}: False{}\n    case False{}: False{}\n"
        )
      )
      binder(file, "match a:", "a", "choose(a")
      binder(file, "match b:", "b", "b: Bool")
      assertEquals(
        List("choose", "witness"),
        BendSourceSymbols.declarations(file).map(_.name)
      )
    }

  def testPartialParallelValueSelectionCannotRewriteHalfLayout(): Unit =
    val source =
      "import Base\ndef selected(x: U32,y: U32) -> U32:\n  a b = x\n    y\n  (a + b : U32)\n"
    check(source)
    myFixture.configureByText("parallel-selection.bend", source)
    val at = source.indexOf("a b =")
    myFixture.getEditor.getSelectionModel
      .setSelection(at, source.indexOf("\n  (a"))
    myFixture.performEditorAction(IdeActions.ACTION_EDITOR_REFORMAT)
    assertEquals(source, myFixture.getEditor.getDocument.getText)
    check(myFixture.getEditor.getDocument.getText)
