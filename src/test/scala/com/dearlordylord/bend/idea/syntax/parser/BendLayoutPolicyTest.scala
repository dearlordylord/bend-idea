package com.dearlordylord.bend.idea.syntax.parser

import org.junit.Assert.*
import org.junit.Test

final class BendLayoutPolicyTest:
  private val twoSpaces = BendLayoutPolicy.Settings(2, false, 8)

  @Test def equalityOperandsTypeAndNestedCallsWrapIdempotently(): Unit =
    val before =
      "law identity:\n  for evidence: {combine(alpha,beta,gamma) == (combine(alpha,beta,gamma)) : Nat}\n  {combine(alpha,beta,gamma) != (other(alpha,beta,gamma)) : Nat}\n"
    val after =
      "law identity:\n  for evidence: {\n    combine(\n      alpha,\n      beta,\n      gamma\n    )\n    == (combine(\n      alpha,\n      beta,\n      gamma\n    ))\n    : Nat\n  }\n  {\n    combine(\n      alpha,\n      beta,\n      gamma\n    )\n    != (other(\n      alpha,\n      beta,\n      gamma\n    ))\n    : Nat\n  }\n"
    val settings = twoSpaces.copy(maxLineLength = Some(25))
    for source <- List(
        before,
        before.replace("== (", "==\n    (").replace("!= (", "!=\n    (")
      )
    do
      assertEquals(
        BendLayoutPolicy.Outcome.Formatted(after),
        BendLayoutPolicy.format(source, settings)
      )
    assertEquals(
      BendLayoutPolicy.Outcome.Unchanged,
      BendLayoutPolicy.format(after, settings)
    )

  @Test def fittingOrDisabledEqualityWrappingKeepsIntentionalBreaks(): Unit =
    val sources = List(
      "law same:\n  {alpha == beta : Nat}\n",
      "law same:\n  {\n    alpha == beta : Nat\n  }\n",
      "law same:\n  {\n    alpha\n    == (beta)\n    : Nat\n  }\n"
    )
    for source <- sources do
      assertEquals(
        BendLayoutPolicy.Outcome.Unchanged,
        BendLayoutPolicy.format(source, twoSpaces)
      )
      assertEquals(
        BendLayoutPolicy.Outcome.Unchanged,
        BendLayoutPolicy.format(source, twoSpaces.copy(maxLineLength = None))
      )

  @Test def unsafeOrIncompleteEqualityTermsRefuseTheWholeEdit(): Unit =
    val settings = twoSpaces.copy(maxLineLength = Some(10))
    for source <- List(
        "law same:\n  {alpha == : Nat}\n",
        "law same:\n  {alpha == beta :}\n",
        "law same:\n  {alpha == beta}\n",
        "law same:\n  {alpha == beta == gamma : Nat}\n",
        "law same:\n  {alpha == beta : Nat : Nat}\n",
        "law same:\n  {alpha == beta, gamma : Nat}\n",
        "law same:\n  {alpha == beta + : Nat}\n",
        "law same:\n  {alpha == beta : # attachment\n    Nat}\n",
        "law same:\n  {combine(alpha\n    (beta),gamma) == beta : Nat}\n",
        "law same:\n  {combine(alpha\n    [beta],gamma) == beta : Nat}\n",
        "law same:\n  {alpha == beta => beta : Nat}\n",
        "law same:\n  {alpha == [beta\n    : Nat*4] : Nat}\n",
        "law same:\r\n  {alpha == beta : Nat}\n"
      )
    do assertTrue(source, BendLayoutPolicy.edits(source, settings).isLeft)

  @Test def reflexivityAnnotationsAndTypeApplicationsRemainAtomic(): Unit =
    val settings = twoSpaces.copy(maxLineLength = Some(1))
    for source <- List(
        "def witness():\n  {==}\n",
        "def witness():\n  {value : TypeName}\n",
        "def witness():\n  {combine(alpha, beta) : Nat}\n",
        "def witness():\n  Family<{alpha == beta : Nat}>\n",
        "def witness():\n  [{alpha == beta : Nat} : Nat*4]\n",
        "def witness():\n  array[{alpha == beta : Nat}]\n"
      )
    do
      assertEquals(
        source,
        BendLayoutPolicy.Outcome.Unchanged,
        BendLayoutPolicy.format(source, settings)
      )

  @Test def formattingReportsChangesAndIsIdempotent(): Unit =
    val source = "def main(x: U32,y: U32) -> U32:\n    U32.add(x,y)\n"
    val expected = "def main(x: U32, y: U32) -> U32:\n  U32.add(x, y)\n"
    assertEquals(
      BendLayoutPolicy.Outcome.Formatted(expected),
      BendLayoutPolicy.format(source, twoSpaces)
    )
    assertEquals(
      BendLayoutPolicy.Outcome.Unchanged,
      BendLayoutPolicy.format(expected, twoSpaces)
    )

  @Test def lineEndingsAndFinalNewlineArePreserved(): Unit =
    val source = "def main(x: U32,y: U32) -> U32:\r\n    U32.add(x,y)"
    val expected = "def main(x: U32, y: U32) -> U32:\r\n  U32.add(x, y)"
    assertEquals(
      BendLayoutPolicy.Outcome.Formatted(expected),
      BendLayoutPolicy.format(source, twoSpaces)
    )

  @Test def incompleteSourceIsUnavailableWithoutAnEdit(): Unit =
    val source = "def main(x: U32,y: U32\n  x\n"
    assertTrue(
      BendLayoutPolicy
        .format(source, twoSpaces)
        .isInstanceOf[
          BendLayoutPolicy.Outcome.Unavailable
        ]
    )
    assertTrue(
      BendLayoutPolicy
        .format("def f():\n \t1\n", twoSpaces)
        .isInstanceOf[
          BendLayoutPolicy.Outcome.Unavailable
        ]
    )
    assertTrue(
      BendLayoutPolicy
        .format("def f():\n  \"\\q\"\n", twoSpaces)
        .isInstanceOf[
          BendLayoutPolicy.Outcome.Unavailable
        ]
    )

  @Test def tabsUseVisualColumnsAndRejectNoncanonicalMixedPrefixes(): Unit =
    val tabs = BendLayoutPolicy.Settings(4, true, 4)
    val nested = "def f():\n\tmatch x:\n"
    assertEquals(
      Some("\t\t"),
      BendIndentPolicy.afterEnter(nested, nested.length, tabs)
    )
    assertEquals(
      Some("\t"),
      BendIndentPolicy.backspace("def f():\n\t\tvalue", 11, tabs)
    )
    val wideTabs = BendLayoutPolicy.Settings(2, true, 8)
    val root = "def f():\n"
    assertEquals(
      Some("  "),
      BendIndentPolicy.afterEnter(root, root.length, wideTabs)
    )
    val mixed = "def f():\n \tcase Zero{}:\n"
    assertEquals(None, BendIndentPolicy.afterEnter(mixed, mixed.length, tabs))
    assertEquals(
      None,
      BendIndentPolicy.backspace("def f():\n \tvalue", 11, tabs)
    )

  @Test def editorConfigKeepsTabDisplaySeparateFromIndentation(): Unit =
    val base = BendLayoutPolicy.Settings(2, false, 2)
    assertEquals(
      Right(BendLayoutPolicy.Settings(2, false, 8)),
      BendLayoutPolicy.fromEditorConfig(
        Map("indent_style" -> "space", "tab_width" -> "8"),
        base
      )
    )
    assertEquals(
      Right(BendLayoutPolicy.Settings(3, true, 3)),
      BendLayoutPolicy.fromEditorConfig(
        Map(
          "indent_style" -> "tab",
          "indent_size" -> "tab",
          "tab_width" -> "3"
        ),
        base
      )
    )
    assertEquals(
      Right(BendLayoutPolicy.Settings(2, false, 8)),
      BendLayoutPolicy.fromEditorConfig(
        Map("indent_size" -> "unset", "tab_width" -> "8"),
        base
      )
    )
    val editorTabs = BendLayoutPolicy.Settings(2, true, 6)
    assertEquals(
      Right(BendLayoutPolicy.Settings(6, true, 6)),
      BendLayoutPolicy.fromEditorConfig(Map("indent_size" -> "tab"), editorTabs)
    )

  @Test def tabIndentationUsesMaximumTabsAndRemainderSpaces(): Unit =
    val narrow = BendLayoutPolicy.Settings(4, true, 3)
    val wide = BendLayoutPolicy.Settings(4, true, 8)
    assertEquals(Some("\t "), narrow.indent(4))
    assertEquals(Some("\t\t  "), narrow.indent(8))
    assertEquals(Some("    "), wide.indent(4))
    assertEquals(Some("\t"), wide.indent(8))
    for settings <- List(narrow, wide) do
      val prefix = settings.indent(4).get
      val source = "def f():\n" + prefix + "1\n"
      assertEquals(
        BendLayoutPolicy.Outcome.Unchanged,
        BendLayoutPolicy.format(source, settings)
      )
      val header = "def f():\n"
      assertEquals(
        Some(prefix),
        BendIndentPolicy.afterEnter(header, header.length, settings)
      )
      val entered = "def f():\n" + prefix + "match x:\n"
      val deeperPrefix = settings.indent(8).get
      val safeIncrease =
        Option.when(deeperPrefix.length > prefix.length)(deeperPrefix)
      assertEquals(
        safeIncrease,
        BendIndentPolicy.afterEnter(entered, entered.length, settings)
      )
      val deeper = "def f():\n" + settings.indent(8).get
      val safeDecrease =
        Option.when(prefix.length < deeperPrefix.length)(prefix)
      assertEquals(
        safeDecrease,
        BendIndentPolicy.backspace(deeper, deeper.length, settings)
      )
    val noncanonical = "def f():\n \t1\n"
    assertTrue(
      BendLayoutPolicy
        .format(noncanonical, narrow)
        .isInstanceOf[BendLayoutPolicy.Outcome.Unavailable]
    )
    val enteredNoncanonical = "def f():\n \tmatch x:\n"
    assertEquals(
      None,
      BendIndentPolicy.afterEnter(
        enteredNoncanonical,
        enteredNoncanonical.length,
        narrow
      )
    )

  @Test def wrappedSimpleBodyUsesItsNormalizedIndentation(): Unit =
    val settings = BendLayoutPolicy.Settings(4, true, 3, Some(20))
    val source = "def f():\n  combine(alpha,beta,gamma)\n"
    val expected =
      "def f():\n\t combine(\n\t\t  alpha,\n\t\t  beta,\n\t\t  gamma\n\t )\n"
    assertEquals(
      BendLayoutPolicy.Outcome.Formatted(expected),
      BendLayoutPolicy.format(source, settings)
    )
    assertEquals(
      BendLayoutPolicy.Outcome.Unchanged,
      BendLayoutPolicy.format(expected, settings)
    )

  @Test def invalidStandardPropertiesAreIgnoredIndependently(): Unit =
    val base = BendLayoutPolicy.Settings(2, true, 6)
    assertEquals(
      Right(base),
      BendLayoutPolicy.fromEditorConfig(
        Map(
          "indent_style" -> "invalid",
          "indent_size" -> "invalid",
          "tab_width" -> "invalid"
        ),
        base
      )
    )
    assertEquals(
      Right(BendLayoutPolicy.Settings(4, true, 4)),
      BendLayoutPolicy.fromEditorConfig(
        Map(
          "indent_style" -> "invalid",
          "indent_size" -> "4",
          "tab_width" -> "invalid"
        ),
        base
      )
    )
    assertEquals(
      Right(BendLayoutPolicy.Settings(2, false, 8)),
      BendLayoutPolicy.fromEditorConfig(
        Map(
          "indent_style" -> "space",
          "indent_size" -> "0",
          "tab_width" -> "8"
        ),
        base
      )
    )
    assertEquals(
      Right(BendLayoutPolicy.Settings(6, true, 6)),
      BendLayoutPolicy.fromEditorConfig(
        Map("indent_size" -> "tab", "tab_width" -> "-1"),
        base
      )
    )
    assertTrue(
      BendLayoutPolicy
        .fromEditorConfig(
          Map("indent_size" -> "invalid", "bend_max_line_length" -> "invalid"),
          base
        )
        .isLeft
    )

  @Test def typingPreservesPhysicalIndentationOrderAcrossTabStops(): Unit =
    val settings = BendLayoutPolicy.Settings(2, true, 8)
    val outerCase = "def f():\n    case True{}:\n"
    assertEquals(
      Some("      "),
      BendIndentPolicy.afterEnter(outerCase, outerCase.length, settings)
    )
    val nestedMatch = "def f():\n    case True{}:\n      match value:\n"
    assertEquals(
      None,
      BendIndentPolicy.afterEnter(nestedMatch, nestedMatch.length, settings)
    )
    val tabbed = "def f():\n\t"
    assertEquals(
      None,
      BendIndentPolicy.backspace(tabbed, tabbed.length, settings)
    )
    val nextLevel = "def f():\n\tcase True{}:\n"
    assertEquals(
      Some("\t  "),
      BendIndentPolicy.afterEnter(nextLevel, nextLevel.length, settings)
    )
    val safeDedent = "def f():\n\t  "
    assertEquals(
      Some("\t"),
      BendIndentPolicy.backspace(safeDedent, safeDedent.length, settings)
    )

  @Test def enteringSiblingLinesPreservesPhysicalIndentation(): Unit =
    val settings = BendLayoutPolicy.Settings(2, true, 8)
    val spacedCase = "def f():\n        case True{}: True{}\n"
    assertEquals(
      None,
      BendIndentPolicy.afterEnter(spacedCase, spacedCase.length, settings)
    )
    val tabbedCase = "def f():\n\tcase True{}: True{}\n"
    assertEquals(
      Some("\t"),
      BendIndentPolicy.afterEnter(tabbedCase, tabbedCase.length, settings)
    )
    val spacedComment = "def f():\n        # comment\n"
    assertEquals(
      None,
      BendIndentPolicy.afterEnter(spacedComment, spacedComment.length, settings)
    )
    val tabbedComment = "def f():\n\t# comment\n"
    assertEquals(
      Some("\t"),
      BendIndentPolicy.afterEnter(tabbedComment, tabbedComment.length, settings)
    )

  @Test def declarationIndentationNeverEditsMultilineLiteralContents(): Unit =
    val literal = "\"hello\ndef fake():\n  contents\n\n\""
    val source =
      "def text(\n  x: U32\n) -> String:\n  " + literal + "\ndef other(x: U32,y: U32) -> U32:\n  x\n"
    val expected =
      "def text(\n  x: U32\n) -> String:\n  " + literal + "\ndef other(x: U32, y: U32) -> U32:\n    x\n"
    val settings = BendLayoutPolicy.Settings(4, false, 4)
    assertEquals(
      BendLayoutPolicy.Outcome.Formatted(expected),
      BendLayoutPolicy.format(source, settings)
    )
    assertEquals(
      BendLayoutPolicy.Outcome.Unchanged,
      BendLayoutPolicy.format(expected, settings)
    )

  @Test def comparisonRecognitionDoesNotDependOnBinderCapitalization(): Unit =
    for (left, right) <- List(("A", "B"), ("a", "b")) do
      val source =
        s"def less($left: Nat,$right: Nat) -> Bool:\n    ($left< $right : Nat)\n"
      val expected =
        s"def less($left: Nat, $right: Nat) -> Bool:\n  ($left< $right : Nat)\n"
      assertEquals(
        BendLayoutPolicy.Outcome.Formatted(expected),
        BendLayoutPolicy.format(source, twoSpaces)
      )
      assertEquals(
        BendLayoutPolicy.Outcome.Unchanged,
        BendLayoutPolicy.format(expected, twoSpaces)
      )
    for source <- List(
        "type Family<a: Type:\n  Constructor{}\n",
        "def f(x: Family<Nat, Nat) -> Nat:\n  x\n"
      )
    do
      assertTrue(
        BendLayoutPolicy
          .format(source, twoSpaces)
          .isInstanceOf[BendLayoutPolicy.Outcome.Unavailable]
      )

  @Test def newListFormsRetainIncompleteAndAtomicGuards(): Unit =
    val settings = twoSpaces.copy(maxLineLength = Some(20))
    for source <- List(
        "def f():\n  (alpha,,beta)\n",
        "def f():\n  (alpha,)\n",
        "def f():\n  [alpha *,beta]\n",
        "def f():\n  (alpha *,beta)\n",
        "def f(first: U32)\n    first\n",
        "def f(first: U32)\n-> U32:\n  first\n",
        "def f(first: U32)\n    -> :\n  first\n",
        "def f():\n  [alpha, # attachment\n    beta]\n",
        "def f():\n  (alpha, # attachment\n    beta)\n"
      )
    do assertTrue(source, BendLayoutPolicy.edits(source, settings).isLeft)
    for source <- List(
        "def f():\n  array[index]\n",
        "def f():\n  [value : U32*4]\n",
        "def f():\n  (alpha, beta)\n",
        "def f():\n  [alpha, beta]\n"
      )
    do
      assertEquals(
        source,
        BendLayoutPolicy.Outcome.Unchanged,
        BendLayoutPolicy.format(source, settings)
      )
