package com.dearlordylord.bend.idea.features.formatting

import com.dearlordylord.bend.idea.adapters.cli.RealBendCompilerFixture
import com.dearlordylord.bend.idea.symbols.api.BendSourceSymbols
import com.dearlordylord.bend.idea.syntax.lexer.BendLexer
import com.dearlordylord.bend.idea.syntax.parser.{
  BendIndentPolicy,
  BendLayoutPolicy
}
import com.dearlordylord.bend.idea.syntax.psi.BendReferenceElement
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.psi.{PsiFile, TokenType}
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.{Files, Path}
import java.util.concurrent.TimeUnit
import scala.jdk.CollectionConverters.*
import org.junit.Assert.*

/** Complete bounded equivalents of the approved excerpts: proofs here are real
  * reflexivity witnesses, rather than holes or claims about game invariants.
  */
final class BendWrappingCompilerTest extends BasePlatformTestCase:
  private val types =
    """import Base
      |type AliveState is Data:
      |  AliveState{hp: Nat, maximum: Nat, positive: {hp == hp : Nat}, bound: {maximum == maximum : Nat}}
      |type Vitals is Data:
      |  Alive{state: AliveState, temp: Nat, kind: Nat}
      |type Economy is Data:
      |  Ec{available: Bool, bonus: Nat, grant: Nat}
      |type Creature is Data:
      |  Cr{vitals: Vitals, economy: Economy, ac: Nat, features: Nat, positive_ac: {ac == ac : Nat}, valid_grant: {features == features : Nat}, valid_features: {features == features : Nat}}
      |def minimum_positive_evidence_for_alive_state_with_preserved_dependent_witnesses(maximum: Nat, hp: Nat, positive: {hp == hp : Nat}, bound: {maximum == maximum : Nat}) -> {hp == hp : Nat}:
      |  positive
      |""".stripMargin

  private val fittingSignature =
    """def preserve.grouping(
      |  state: T.AliveState, temp: Nat,
      |  kind: Nat, +amount: Nat,
      |  evidence: {amount == amount : Nat}
      |) -> T.Vitals:
      |  T.Alive{state, temp, kind}
      |""".stripMargin

  private val source = "import Base\nimport ./types.bend as T\n" +
    BendWrappingFixtures.actorBefore + fittingSignature +
    """def heal.with_evidence(state: T.AliveState, temp: Nat, kind: Nat, +amount: Nat, evidence: {amount == amount : Nat}) -> T.Vitals:
      |  T.Alive{state,temp,kind}
      |def heal.pattern(state: T.AliveState, temp: Nat, kind: Nat) -> T.Vitals:
      |  match state:
      |    case T.AliveState{+hp,+maximum,+positive,+bound}:
      |      T.Alive{T.AliveState{hp,maximum,positive,bound},temp,kind}
      |def heal.alive(+hp: Nat, +maximum: Nat, temp: Nat, kind: Nat, positive: {hp == hp : Nat}, bound: {maximum == maximum : Nat}) -> T.Vitals:
      |  T.Alive{T.AliveState{hp,maximum,
      |    T.minimum_positive_evidence_for_alive_state_with_preserved_dependent_witnesses(maximum,hp,positive,bound),{==}},temp,kind}
      |""".stripMargin

  private val expected = "import Base\nimport ./types.bend as T\n" +
    BendWrappingFixtures.actorAfter + fittingSignature +
    """def heal.with_evidence(
      |  state: T.AliveState,
      |  temp: Nat,
      |  kind: Nat,
      |  +amount: Nat,
      |  evidence: {amount == amount : Nat}
      |) -> T.Vitals:
      |  T.Alive{state, temp, kind}
      |def heal.pattern(state: T.AliveState, temp: Nat, kind: Nat) -> T.Vitals:
      |  match state:
      |    case T.AliveState{+hp, +maximum, +positive, +bound}:
      |      T.Alive{T.AliveState{hp, maximum, positive, bound}, temp, kind}
      |def heal.alive(
      |  +hp: Nat,
      |  +maximum: Nat,
      |  temp: Nat,
      |  kind: Nat,
      |  positive: {hp == hp : Nat},
      |  bound: {maximum == maximum : Nat}
      |) -> T.Vitals:
      |  T.Alive{
      |    T.AliveState{
      |      hp,
      |      maximum,
      |      T.minimum_positive_evidence_for_alive_state_with_preserved_dependent_witnesses(
      |        maximum,
      |        hp,
      |        positive,
      |        bound
      |      ),
      |      {==}
      |    },
      |    temp,
      |    kind
      |  }
      |""".stripMargin

  private def tokens(file: PsiFile): Vector[(String, String, Int)] =
    val lexer = new BendLexer()
    val text = file.getText
    lexer.start(text)
    val result = Vector.newBuilder[(String, String, Int)]
    while lexer.getTokenType != null do
      if lexer.getTokenType != TokenType.WHITE_SPACE then
        result += ((
          lexer.getTokenType.toString,
          text.substring(lexer.getTokenStart, lexer.getTokenEnd),
          lexer.getTokenStart
        ))
      lexer.advance()
    result.result()

  /** Whitespace edits move offsets. Compare source identities by their stable
    * ordinal in the unchanged token sequence, including their owning file.
    */
  private def identity(file: PsiFile, offset: Int): (String, Int) =
    (file.getName, tokens(file).indexWhere(_._3 == offset))

  private def shape(file: PsiFile) =
    val stream = tokens(file)
    val declarations = BendSourceSymbols.declarations(file).map { symbol =>
      val range = symbol.declaration.getTextRange
      (
        symbol.category.toString,
        symbol.name,
        identity(file, symbol.handle.nameOffset),
        stream.filter(t => range.contains(t._3)).map(t => (t._1, t._2))
      )
    }
    val bindings = stream.flatMap { token =>
      BendSourceSymbols.bindingDeclaredAt(file, token._3).map { binding =>
        (
          binding.name,
          binding.origin.toString,
          identity(file, binding.handle.nameOffset)
        )
      }
    }
    val references = PsiTreeUtil
      .findChildrenOfType(file, classOf[BendReferenceElement])
      .asScala
      .toList
      .sortBy(_.getTextOffset)
      .flatMap { element =>
        element.getReferences.toList.map { reference =>
          val target = Option(reference.resolve()).map { resolved =>
            (
              resolved.getClass.getSimpleName,
              identity(resolved.getContainingFile, resolved.getTextOffset)
            )
          }
          (
            element.getText,
            identity(file, element.getTextOffset),
            reference.getRangeInElement.toString,
            target
          )
        }
      }
    (declarations, bindings, references)

  private def checkPinnedFile(root: Path): Unit =
    val compiler = RealBendCompilerFixture.inputs
    val output =
      root.resolveSibling(root.getFileName.toString + ".compiler.log")
    val process = new ProcessBuilder(
      compiler.bunExecutable.toString,
      compiler.main.toString,
      root.toString,
      "--check-only"
    ).redirectErrorStream(true).redirectOutput(output.toFile).start()
    if !process.waitFor(30, TimeUnit.SECONDS) then
      val _ = process.destroyForcibly()
      val _ = process.waitFor(5, TimeUnit.SECONDS)
      fail("Pinned compiler timed out checking wrapping fixture")
    val result = Files.readString(output)
    assertEquals(result, 0, process.exitValue())
    assertTrue(result, result.contains("All terms check."))

  def testCompleteImportedProofBearingProgramsPreserveCompilerAndSourceIdentities()
      : Unit =
    val compiler = RealBendCompilerFixture.inputs
    val directory = Files.createTempDirectory("bend-wrapping-complete-")
    try
      val _ = Files.writeString(directory.resolve("types.bend"), types)
      val original = directory.resolve("main.bend")
      val _ = Files.writeString(original, source)
      def check(text: String): Unit =
        val capture = Files.createTempDirectory(directory, "snapshot-")
        val _ = Files.writeString(capture.resolve("types.bend"), types)
        val root = capture.resolve("main.bend")
        val _ = Files.writeString(root, text)
        val output = capture.resolve("compiler.log")
        val process = new ProcessBuilder(
          compiler.bunExecutable.toString,
          compiler.main.toString,
          root.toString,
          "--check-only"
        )
          .redirectErrorStream(true)
          .redirectOutput(output.toFile)
          .start()
        if !process.waitFor(30, TimeUnit.SECONDS) then
          val _ = process.destroyForcibly()
          val _ = process.waitFor(5, TimeUnit.SECONDS)
          fail("Pinned compiler timed out checking wrapping fixture")
        val result = Files.readString(output)
        assertEquals(result, 0, process.exitValue())
        assertTrue(result, result.contains("All terms check."))
      val _ = myFixture.addFileToProject("types.bend", types)
      val file = myFixture.configureByText("main.bend", source)
      val beforeTokens = tokens(file).map(t => (t._1, t._2))
      val beforeShape = shape(file)
      assertTrue(
        "Imported constructor must have a native resolved reference",
        beforeShape._3.exists(r =>
          r._1 == "T.Cr" && r._4.exists(_._2._1 == "types.bend")
        )
      )
      assertTrue(
        "Dependent signature must expose its amount binder",
        beforeShape._2.exists(_._1 == "amount")
      )
      check(source)
      myFixture.performEditorAction(IdeActions.ACTION_EDITOR_REFORMAT)
      val formatted = myFixture.getEditor.getDocument.getText
      assertEquals(expected, formatted)
      assertTrue(formatted.contains(BendWrappingFixtures.actorAfter))
      assertTrue(
        "Fitting intentional multiline signature grouping stays intact",
        formatted.contains(fittingSignature)
      )
      assertTrue(
        formatted.contains(
          "case T.AliveState{+hp, +maximum, +positive, +bound}:"
        )
      )
      assertTrue(formatted.contains("def heal.with_evidence(\n"))
      assertTrue(formatted.contains("  T.Alive{\n"))
      assertTrue(
        formatted.contains(
          "T.minimum_positive_evidence_for_alive_state_with_preserved_dependent_witnesses(\n"
        )
      )
      assertEquals(beforeTokens, tokens(file).map(t => (t._1, t._2)))
      assertEquals(beforeShape, shape(file))
      check(formatted)
      myFixture.performEditorAction(IdeActions.ACTION_EDITOR_REFORMAT)
      assertEquals(formatted, myFixture.getEditor.getDocument.getText)
      myFixture.performEditorAction(IdeActions.ACTION_UNDO)
      assertEquals(source, myFixture.getEditor.getDocument.getText)
      assertEquals(source, Files.readString(original))
      assertEquals(types, Files.readString(directory.resolve("types.bend")))
    finally
      val paths = Files.walk(directory)
      try
        paths
          .sorted(java.util.Comparator.reverseOrder())
          .forEach(p => { val _ = Files.deleteIfExists(p) })
      finally paths.close()

  def testCompilerValidOmittedCommaBeforeGroupedAndBracketArgumentsIsRefused()
      : Unit =
    val compiler = RealBendCompilerFixture.inputs
    val directory = Files.createTempDirectory("bend-wrapping-omitted-comma-")
    try
      val examples = List(
        BendWrappingFixtures.separatedApplicationCases.head,
        "import Base\ndef combine_mixed_arguments(a: U32,b: List<&2, U32>,c: U32) -> U32:\n  a\ndef main() -> U32:\n  combine_mixed_arguments(1\n    [2], 3)\n"
      )
      for (text, index) <- examples.zipWithIndex do
        val root = directory.resolve(s"omitted-$index.bend")
        val _ = Files.writeString(root, text)
        def check(): Unit =
          val output = directory.resolve(s"compiler-$index.log")
          val process = new ProcessBuilder(
            compiler.bunExecutable.toString,
            compiler.main.toString,
            root.toString,
            "--check-only"
          )
            .redirectErrorStream(true)
            .redirectOutput(output.toFile)
            .start()
          if !process.waitFor(30, TimeUnit.SECONDS) then
            val _ = process.destroyForcibly()
            val _ = process.waitFor(5, TimeUnit.SECONDS)
            fail("Pinned compiler timed out checking omitted-comma fixture")
          val result = Files.readString(output)
          assertEquals(result, 0, process.exitValue())
          assertTrue(result, result.contains("All terms check."))
        check()
        val settings =
          BendLayoutPolicy.defaultSettings.copy(maxLineLength = Some(20))
        BendLayoutPolicy.format(text, settings) match
          case BendLayoutPolicy.Outcome.Unavailable(_) => ()
          case other                                   =>
            fail(
              s"Unsafe omitted-comma argument boundary must be refused: $other"
            )
        assertTrue(BendLayoutPolicy.edits(text, settings).isLeft)
        assertEquals(text, Files.readString(root))
        check()
    finally
      val paths = Files.walk(directory)
      try
        paths
          .sorted(java.util.Comparator.reverseOrder())
          .forEach(p => { val _ = Files.deleteIfExists(p) })
      finally paths.close()

  def testNondivisibleTabWidthsPreserveCompleteCompilerAndSourceIdentities()
      : Unit =
    val text =
      "import Base\ndef combine_three_arguments(a: U32,b: U32,c: U32) -> U32:\n  a\ndef main() -> U32:\n  combine_three_arguments(1,2,3)\n"
    val directory = Files.createTempDirectory("bend-wrapping-tab-remainders-")
    try
      val original = directory.resolve("original.bend")
      val _ = Files.writeString(original, text)
      checkPinnedFile(original)
      for tabWidth <- List(3, 8) do
        val settings = BendLayoutPolicy.Settings(4, true, tabWidth, Some(20))
        val formatted = BendLayoutPolicy.format(text, settings) match
          case BendLayoutPolicy.Outcome.Formatted(value) => value
          case other                                     =>
            fail(s"Complete tab-remainder fixture should format: $other")
            text
        assertEquals(
          BendLayoutPolicy.Outcome.Unchanged,
          BendLayoutPolicy.format(formatted, settings)
        )
        val before = myFixture.configureByText("tab-remainders.bend", text)
        val beforeTokens = tokens(before).map(t => (t._1, t._2))
        val beforeShape = shape(before)
        assertTrue(beforeShape._2.exists(_._1 == "a"))
        assertTrue(
          beforeShape._3.exists(r =>
            r._1 == "combine_three_arguments" && r._4.nonEmpty
          )
        )
        val after = myFixture.configureByText("tab-remainders.bend", formatted)
        assertEquals(beforeTokens, tokens(after).map(t => (t._1, t._2)))
        assertEquals(beforeShape, shape(after))
        assertTrue(
          formatted.contains(
            settings.indent(4).get + "combine_three_arguments(\n"
          )
        )
        assertTrue(formatted.contains(settings.indent(8).get + "1,\n"))
        val snapshot = directory.resolve(s"tabs-$tabWidth.bend")
        val _ = Files.writeString(snapshot, formatted)
        checkPinnedFile(snapshot)
      assertEquals(text, Files.readString(original))
    finally
      val paths = Files.walk(directory)
      try
        paths
          .sorted(java.util.Comparator.reverseOrder())
          .forEach(p => { val _ = Files.deleteIfExists(p) })
      finally paths.close()

  def testTypingRefusesTabStopIndentThatWouldMoveCompilerCaseOwnership(): Unit =
    val text =
      "import Base\ndef choose(a: Bool, b: Bool) -> Bool:\n  match a:\n    case True{}:\n      match b:\n        case True{}: True{}\n        case False{}: False{}\n    case False{}: False{}\n"
    val directory = Files.createTempDirectory("bend-typing-physical-tab-order-")
    try
      val original = directory.resolve("original.bend")
      val _ = Files.writeString(original, text)
      checkPinnedFile(original)
      val settings = BendLayoutPolicy.Settings(2, true, 8)
      val matchLine = "      match b:\n"
      val caret = text.indexOf(matchLine) + matchLine.length
      assertEquals(Some("\t"), settings.indent(8))
      assertEquals(None, BendIndentPolicy.afterEnter(text, caret, settings))
      val siblingLine = "        case True{}: True{}\n"
      val siblingCaret = text.indexOf(siblingLine) + siblingLine.length
      assertEquals(
        None,
        BendIndentPolicy.afterEnter(text, siblingCaret, settings)
      )
      val tabbedBlank =
        text.substring(0, caret) + "\t\n" + text.substring(caret)
      assertEquals(
        None,
        BendIndentPolicy.backspace(tabbedBlank, caret + 1, settings)
      )
      assertEquals(text, Files.readString(original))
      checkPinnedFile(original)
    finally
      val paths = Files.walk(directory)
      try
        paths
          .sorted(java.util.Comparator.reverseOrder())
          .forEach(p => { val _ = Files.deleteIfExists(p) })
      finally paths.close()

  def testMultilineStringFakeDeclarationRemainsByteIdenticalWhileRealHeaderFormats()
      : Unit =
    val literal = "\"hello\ndef fake():\n  contents\n\n\""
    val text =
      "import Base\ndef text(\n  x: U32,y: U32\n) -> String:\n  " + literal + "\n"
    val directory = Files.createTempDirectory("bend-format-multiline-string-")
    try
      val original = directory.resolve("original.bend")
      val _ = Files.writeString(original, text)
      checkPinnedFile(original)
      val formatted = BendLayoutPolicy.format(
        text,
        BendLayoutPolicy.Settings(4, false, 4)
      ) match
        case BendLayoutPolicy.Outcome.Formatted(value) => value
        case other                                     =>
          fail(
            s"Safe real header comma edit must remain available around multiline literal: $other"
          )
          text
      assertTrue(formatted.contains("x: U32, y: U32"))
      assertTrue(
        "The entire multiline string must remain byte-identical",
        formatted.contains(literal)
      )
      val before = myFixture.configureByText("literal-preservation.bend", text)
      val beforeTokens = tokens(before).map(t => (t._1, t._2))
      val beforeShape = shape(before)
      val after =
        myFixture.configureByText("literal-preservation.bend", formatted)
      assertEquals(beforeTokens, tokens(after).map(t => (t._1, t._2)))
      assertEquals(beforeShape, shape(after))
      val snapshot = directory.resolve("formatted.bend")
      val _ = Files.writeString(snapshot, formatted)
      checkPinnedFile(snapshot)
      assertEquals(text, Files.readString(original))
    finally
      val paths = Files.walk(directory)
      try
        paths
          .sorted(java.util.Comparator.reverseOrder())
          .forEach(p => { val _ = Files.deleteIfExists(p) })
      finally paths.close()

  def testUppercaseAndLowercaseNatComparisonsBothPreserveCompilerAndBinderIdentity()
      : Unit =
    val text =
      "import Base\ndef less.upper(A: Nat,B: Nat) -> Bool:\n  (A< B : Nat)\ndef less.lower(a: Nat,b: Nat) -> Bool:\n  (a< b : Nat)\n"
    val directory =
      Files.createTempDirectory("bend-format-uppercase-comparison-")
    try
      val original = directory.resolve("original.bend")
      val _ = Files.writeString(original, text)
      checkPinnedFile(original)
      val formatted = BendLayoutPolicy.format(
        text,
        BendLayoutPolicy.Settings(4, false, 4)
      ) match
        case BendLayoutPolicy.Outcome.Formatted(value) => value
        case other                                     =>
          fail(
            s"Ordinary annotated comparisons must remain available for both binder cases: $other"
          )
          text
      assertEquals(
        text
          .replace("Nat,B", "Nat, B")
          .replace("Nat,b", "Nat, b")
          .replace("\n  (", "\n    ("),
        formatted
      )
      val before =
        myFixture.configureByText("comparison-preservation.bend", text)
      val beforeTokens = tokens(before).map(t => (t._1, t._2))
      val beforeShape = shape(before)
      assertEquals(Set("A", "B", "a", "b"), beforeShape._2.map(_._1).toSet)
      assertTrue(beforeShape._3.exists(r => r._1 == "A" && r._4.nonEmpty))
      assertTrue(beforeShape._3.exists(r => r._1 == "a" && r._4.nonEmpty))
      val after =
        myFixture.configureByText("comparison-preservation.bend", formatted)
      assertEquals(beforeTokens, tokens(after).map(t => (t._1, t._2)))
      assertEquals(beforeShape, shape(after))
      val snapshot = directory.resolve("formatted.bend")
      val _ = Files.writeString(snapshot, formatted)
      checkPinnedFile(snapshot)
      assertEquals(text, Files.readString(original))
    finally
      val paths = Files.walk(directory)
      try
        paths
          .sorted(java.util.Comparator.reverseOrder())
          .forEach(p => { val _ = Files.deleteIfExists(p) })
      finally paths.close()

  def testTupleListAndContinuedResultPreserveCompilerAndSourceIdentities()
      : Unit =
    val directory = Files.createTempDirectory("bend-format-coverage-")
    try
      val root = directory.resolve("main.bend")
      val source = BendWrappingFixtures.coverageBefore
      val expected = BendWrappingFixtures.coverageAfter
      val _ = Files.writeString(root, source)
      checkPinnedFile(root)
      val file = myFixture.configureByText("coverage.bend", source)
      val beforeTokens = tokens(file).map(t => (t._1, t._2))
      val beforeShape = shape(file)
      myFixture.performEditorAction(IdeActions.ACTION_EDITOR_REFORMAT)
      assertEquals(expected, myFixture.getEditor.getDocument.getText)
      assertEquals(beforeTokens, tokens(file).map(t => (t._1, t._2)))
      assertEquals(beforeShape, shape(file))
      val _ = Files.writeString(root, expected)
      checkPinnedFile(root)
      myFixture.performEditorAction(IdeActions.ACTION_EDITOR_REFORMAT)
      assertEquals(expected, myFixture.getEditor.getDocument.getText)
      myFixture.performEditorAction(IdeActions.ACTION_UNDO)
      assertEquals(source, myFixture.getEditor.getDocument.getText)
    finally
      val paths = Files.walk(directory)
      try
        paths
          .sorted(java.util.Comparator.reverseOrder())
          .forEach(p => {
            val _ = Files.deleteIfExists(p)
          })
      finally paths.close()

  def testEqualityTelescopesResultsConstructorsAndConjunctionsPreserveProofsAndBindings()
      : Unit =
    val directory = Files.createTempDirectory("bend-format-equality-")
    try
      val root = directory.resolve("main.bend")
      val source = BendWrappingFixtures.equalityCoverageBefore
      val _ = Files.writeString(root, source)
      checkPinnedFile(root)
      val file = myFixture.configureByText("equalities.bend", source)
      val beforeTokens = tokens(file).map(t => (t._1, t._2))
      val beforeShape = shape(file)
      myFixture.performEditorAction(IdeActions.ACTION_EDITOR_REFORMAT)
      val formatted = myFixture.getEditor.getDocument.getText
      assertNotEquals(source, formatted)
      assertTrue(formatted.contains("for evidence: {\n"))
      assertTrue(formatted.contains("    == ("))
      assertTrue(formatted.contains("    != ("))
      assertTrue(formatted.contains("    : Nat\n"))
      assertTrue(formatted.contains("  {==}\n"))
      assertTrue(formatted.split("\n").forall(_.length <= 100))
      assertEquals(beforeTokens, tokens(file).map(t => (t._1, t._2)))
      assertEquals(beforeShape, shape(file))
      val _ = Files.writeString(root, formatted)
      checkPinnedFile(root)
      myFixture.performEditorAction(IdeActions.ACTION_EDITOR_REFORMAT)
      assertEquals(formatted, myFixture.getEditor.getDocument.getText)
      myFixture.performEditorAction(IdeActions.ACTION_UNDO)
      assertEquals(source, myFixture.getEditor.getDocument.getText)
    finally
      val paths = Files.walk(directory)
      try
        paths
          .sorted(java.util.Comparator.reverseOrder())
          .forEach(p => { val _ = Files.deleteIfExists(p) })
      finally paths.close()
