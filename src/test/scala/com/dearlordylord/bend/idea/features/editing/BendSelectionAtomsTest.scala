package com.dearlordylord.bend.idea.features.editing

import com.dearlordylord.bend.idea.symbols.api.BendSourceApplications
import com.dearlordylord.bend.idea.syntax.parser.BendSelectionAtoms
import com.dearlordylord.bend.idea.syntax.parser.BendSelectionExpressions
import com.dearlordylord.bend.idea.syntax.parser.BendSelectionForms
import com.dearlordylord.bend.idea.syntax.parser.BendSelectionStatements
import com.dearlordylord.bend.idea.syntax.psi.BendFoldingSurface
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.*
import scala.jdk.CollectionConverters.*

final class BendSelectionAtomsTest extends BasePlatformTestCase:
  private def ranges(source: String, caret: String): List[String] =
    val offset = source.indexOf(caret)
    assertTrue(offset >= 0)
    BendSelectionAtoms
      .at(source, offset)
      .map(span => source.substring(span.from, span.until))

  def testMarkersAndQualifiedComponents(): Unit =
    assertTrue(ranges("def f():\n  ?TODO\n", "TODO").contains("?TODO"))
    assertTrue(ranges("def f():\n  &2 value\n", "2").contains("&2"))
    assertTrue(ranges("def f():\n  &0 value\n", "0").contains("&0"))
    assertTrue(ranges("def f():\n  &1 value\n", "1").contains("&1"))
    assertTrue(ranges("def f():\n  +value\n", "value").contains("+value"))
    assertTrue(
      ranges("def f():\n  first\n  +value\n", "value")
        .contains("+value")
    )
    assertTrue(ranges("def f():\n  -value = 1\n", "value").contains("-value"))
    assertTrue(ranges("def f():\n  ~value\n", "value").contains("~value"))
    val qualified = ranges("def f():\n  M.U32.add\n", "add")
    assertTrue(qualified.contains("add"))
    assertTrue(qualified.contains("U32.add"))
    assertTrue(qualified.contains("M.U32.add"))
    assertFalse(ranges("def f():\n  a + b\n", "b").contains("+b"))

  def testLiteralContentAndWholeToken(): Unit =
    val source = "def f():\n  \"a\\n\\u{1F642}z\"\n"
    val selected = ranges(source, "1F642")
    assertTrue(selected.contains("a\\n\\u{1F642}z"))
    assertTrue(selected.contains("\"a\\n\\u{1F642}z\""))
    assertFalse(selected.exists(_.contains("def f")))
    assertTrue(ranges("def f():\n  \"\"\n", "\"\"").contains("\"\""))
    assertTrue(ranges("def f():\n  '\\n'\n", "\\n").contains("'\\n'"))

  def testExpandAndShrinkUsesWholeHole(): Unit =
    myFixture.configureByText("selection.bend", "def f():\n  ?TO<caret>DO\n")
    var found = false
    for _ <- 0 until 8 do
      myFixture.performEditorAction("EditorSelectWord")
      if myFixture.getEditor.getSelectionModel.getSelectedText == "?TODO" then
        found = true
    assertTrue("Expand Selection should include the hole marker", found)
    myFixture.performEditorAction("EditorUnSelectWord")
    assertTrue(myFixture.getEditor.getSelectionModel.hasSelection)

  def testApplicationsExpandFromHeadAndLastIndex(): Unit =
    val source =
      "def f() -> D<Nat>:\n  call(1)[2]\n  f(x)(y)\n  C{x}\n  D<Nat>\n  a < b\n"
    myFixture.configureByText("applications.bend", source)
    val handler = new BendSelectionHandler
    def selected(at: String): List[String] =
      val offset = source.indexOf(at)
      handler
        .select(
          myFixture.getFile.findElementAt(offset),
          myFixture.getEditor.getDocument.getCharsSequence,
          offset,
          myFixture.getEditor
        )
        .asScala
        .toList
        .map { range =>
          source.substring(range.getStartOffset, range.getEndOffset)
        }
    assertTrue(selected("call").contains("call(1)"))
    assertTrue(selected("call").contains("call(1)[2]"))
    assertTrue(selected("2]").contains("call(1)[2]"))
    assertTrue(selected("D<Nat>").contains("D<Nat>"))
    assertTrue(selected("f(x)").contains("f(x)(y)"))
    assertTrue(selected("y)").contains("f(x)(y)"))
    assertTrue(selected("C{x}").contains("C{x}"))
    assertFalse(selected("a < b").contains("a<b>"))

  def testNewlineStopsApplicationSpine(): Unit =
    myFixture.configureByText("spine.bend", "def f():\n  f\n  (x)\n  f!(y)\n")
    val source = myFixture.getFile.getText
    val prefixes = BendSourceApplications
      .prefixes(myFixture.getFile)
      .map(p => source.substring(p.from, p.until))
    assertFalse(prefixes.contains("f\n  (x)"))
    assertTrue(prefixes.contains("f!(y)"))
    myFixture.configureByText("compare.bend", "def f():\n  a < b\n")
    assertFalse(
      BendSourceApplications
        .prefixes(myFixture.getFile)
        .exists(prefix =>
          myFixture.getFile.getText
            .substring(prefix.from, prefix.until) == "a < b"
        )
    )

  def testGroupsAndIndexedWriteComponents(): Unit =
    val source =
      "def f():\n  (a + b, (c, d))\n  [first, second + third,]\n  [(a, b), c,]\n  [value : A & B ^ depth + one]\n  [seed : Nat * 2^power n]\n  array[index] <- replacement\n  next\n"
    myFixture.configureByText("groups.bend", source)
    def selected(at: String): List[String] =
      val offset = source.indexOf(at)
      new BendSelectionHandler()
        .select(
          myFixture.getFile.findElementAt(offset),
          myFixture.getEditor.getDocument.getCharsSequence,
          offset,
          myFixture.getEditor
        )
        .asScala
        .toList
        .map(range =>
          source.substring(range.getStartOffset, range.getEndOffset)
        )
    assertTrue(selected("a + b").contains("a + b"))
    assertTrue(selected("second").contains("second + third"))
    assertTrue(selected("value").contains("value"))
    assertTrue(selected("A & B").contains("A & B"))
    assertTrue(selected("depth").contains("depth + one"))
    assertTrue(selected("seed").contains("seed"))
    assertTrue(selected("power").contains("2^power n"))
    assertTrue(selected("(a, b)").contains("(a, b)"))
    assertTrue(selected("index").contains("array[index] <- replacement"))
    assertTrue(selected("replacement").contains("array[index] <- replacement"))
    myFixture.configureByText(
      "write-rewrite.bend",
      "def f():\n  array[i] <- %E : P; value\n"
    )
    val rewriteSource = myFixture.getFile.getText
    val rewriteOffset = rewriteSource.lastIndexOf("value")
    val writeRanges = new BendSelectionHandler()
      .select(
        myFixture.getFile.findElementAt(rewriteOffset),
        myFixture.getEditor.getDocument.getCharsSequence,
        rewriteOffset,
        myFixture.getEditor
      )
      .asScala
      .toList
      .map(range =>
        rewriteSource.substring(range.getStartOffset, range.getEndOffset)
      )
    assertTrue(writeRanges.contains("array[i] <- %E : P; value"))
    myFixture.configureByText(
      "nested-write.bend",
      "def f():\n  outer(array[i] <- value, other)\n"
    )
    val nestedSource = myFixture.getFile.getText
    val nestedOffset = nestedSource.indexOf("value")
    val nestedRanges = new BendSelectionHandler()
      .select(
        myFixture.getFile.findElementAt(nestedOffset),
        myFixture.getEditor.getDocument.getCharsSequence,
        nestedOffset,
        myFixture.getEditor
      )
      .asScala
      .toList
      .map(range =>
        nestedSource.substring(range.getStartOffset, range.getEndOffset)
      )
    assertTrue(nestedRanges.contains("array[i] <- value"))
    assertFalse(nestedRanges.contains("array[i] <- value, other)"))

  def testOperatorPrecedenceAndRightAssociation(): Unit =
    def expressions(line: String, caret: String): List[String] =
      val source = "def f() -> " + line + ":\n  ?TODO\n"
      BendSelectionExpressions
        .at(source, source.indexOf(caret))
        .map(span => source.substring(span.from, span.until))
    assertEquals(List("B & C", "A & B & C"), expressions("A & B & C", "B & C"))
    assertEquals(
      List("B -> C", "A -> B -> C"),
      expressions("A -> B -> C", "B -> C")
    )
    assertTrue(expressions("A | B | C", "B | C").contains("A | B | C"))
    assertTrue(expressions("a + b * c", "b * c").contains("b * c"))
    assertFalse(expressions("a + b * c", "a + b").contains("a + b"))
    assertTrue(expressions("a + (b * c)", "b * c").contains("a + (b * c)"))
    assertTrue(expressions("a ++ \"b\"", "\"b\"").contains("a ++ \"b\""))
    assertTrue(expressions("a >> b", "b").contains("a >> b"))
    val continued = "def f():\n  a &\n    b\n"
    assertTrue(
      BendSelectionExpressions
        .at(continued, continued.lastIndexOf("b"))
        .exists(span =>
          continued.substring(span.from, span.until) == "a &\n    b"
        )
    )
    assertFalse(expressions("a +b", "b").contains("a +b"))
    val mixedLines = "def f():\n  a *\n    b + c\n"
    assertFalse(
      BendSelectionExpressions
        .at(mixedLines, mixedLines.indexOf("b + c"))
        .exists(span => mixedLines.substring(span.from, span.until) == "b + c")
    )
    val contexts =
      "type Wrap is Data:\n  Wrap{field: A & B}\nlaw claim:\n  for value: A | B\n  {value : A | B}\ndef body():\n  a <&> b\n"
    def at(needle: String): List[String] =
      BendSelectionExpressions
        .at(contexts, contexts.indexOf(needle))
        .map(span => contexts.substring(span.from, span.until))
    assertTrue(at("B}").contains("A & B"))
    assertTrue(at("B\n").contains("A | B"))
    assertTrue(
      BendSelectionExpressions
        .at(contexts, contexts.lastIndexOf("B}"))
        .exists(span => contexts.substring(span.from, span.until) == "A | B")
    )
    assertTrue(at("b\n").contains("a <&> b"))

  def testEditorActionsReachExpressionApplicationAndBlock(): Unit =
    def reaches(source: String, expected: String): Unit =
      myFixture.configureByText("actions.bend", source)
      var found = false
      val steps = scala.collection.mutable.ListBuffer.empty[String]
      var count = 0
      while !found && count < 14 do
        myFixture.performEditorAction("EditorSelectWord")
        steps += Option(myFixture.getEditor.getSelectionModel.getSelectedText)
          .getOrElse("")
        if myFixture.getEditor.getSelectionModel.getSelectedText == expected
        then found = true
        count += 1
      assertTrue(
        s"Expand Selection should reach $expected; got ${steps.toList}",
        found
      )
      myFixture.performEditorAction("EditorUnSelectWord")
      assertEquals(
        steps.dropRight(1).lastOption.getOrElse(""),
        Option(myFixture.getEditor.getSelectionModel.getSelectedText)
          .getOrElse("")
      )
      myFixture.performEditorAction("EditorSelectWord")
      assertEquals(
        expected,
        myFixture.getEditor.getSelectionModel.getSelectedText
      )
    reaches("def f() -> A & <caret>B:\n  ?TODO\n", "A & B")
    reaches("def f() -> <caret>A & B:\n  ?TODO\n", "A & B")
    reaches("def f() -> A <caret>& B:\n  ?TODO\n", "A & B")
    reaches("def f():\n  ca<caret>ll(1)[2]\n", "call(1)[2]")
    reaches("def f() -> <caret>D<Nat>:\n  ?TODO\n", "D<Nat>")
    reaches("def f():\n  D<Na<caret>t>\n", "D<Nat>")
    reaches("def f():\n  C{<caret>x}\n", "C{x}")
    reaches("def f():\n  ou<caret>ter(1, second\n", "outer(1, second")
    reaches("def f():\n  outer(1, sec<caret>ond\n", "outer(1, second")
    reaches("def f():\n  [first, sec<caret>ond + third]\n", "second + third")
    reaches("def f():\n  (a + <caret>b, c)\n", "a + b")
    reaches("def f():\n  [value : Nat ^ <caret>depth + one]\n", "depth + one")
    reaches(
      "def f():\n  array[index] <- <caret>replacement\n",
      "array[index] <- replacement"
    )
    reaches("def f():\n  M.U32.a<caret>dd\n", "add")
    reaches("def f():\n  \"a\\u{1F642}<caret>z\"\n", "\"a\\u{1F642}z\"")
    reaches("def f():\n  &<caret>2 value\n", "&2")
    reaches("def f():\n  +<caret>value\n", "+value")
    reaches("def f():\n  -<caret>value = 1\n", "-value")
    reaches("def f():\n  ~<caret>value\n", "~value")
    myFixture.configureByText(
      "block.bend",
      "def f():\n  match value:\n    case Box{x}:\n      <caret>x\n"
    )
    assertTrue(
      BendFoldingSurface
        .selectionConstructs(myFixture.getFile)
        .exists(range =>
          myFixture.getFile.getText
            .substring(range.from, range.until) == "case Box{x}:\n      x\n"
        )
    )
    reaches(
      "def f():\n  match value:\n    case Box{x}:\n      <caret>x\n",
      "case Box{x}:\n      x\n"
    )
    reaches(
      "def f():\n  match value:\n    case Box{x}:\n      <caret>x\n",
      "match value:\n    case Box{x}:\n      x\n"
    )
    reaches(
      "law claim:\n  match value:\n    case Box{x}:\n      <caret>x\n",
      "match value:\n    case Box{x}:\n      x\n"
    )
    reaches(
      "def f():\n  do IO<Unit>:\n    return <caret>x\n",
      "do IO<Unit>:\n    return x\n"
    )
    reaches("def f():\n  x = 1\n  <caret>x\n", "x = 1\n  x\n")
    reaches("def f():\n  result = x => <caret>x + 1\n", "x => x + 1")
    reaches("def f():\n  proof = %E : P; <caret>E\n", "%E : P; E")
    reaches(
      "def f():\n  result = x =>\n    <caret>x + 1\n",
      "x =>\n    x + 1\n"
    )
    reaches("def f():\n  proof = %E : P;\n    <caret>E\n", "%E : P;\n    E\n")

  def testIncompleteCallStopsAtStatementBoundary(): Unit =
    val source = "def f():\n  outer(1, <caret>second; later\n  next\n"
    myFixture.configureByText("incomplete.bend", source)
    val offset = myFixture.getCaretOffset
    val selected = new BendSelectionHandler()
      .select(
        myFixture.getFile.findElementAt(offset),
        myFixture.getEditor.getDocument.getCharsSequence,
        offset,
        myFixture.getEditor
      )
      .asScala
      .toList
      .map(range =>
        myFixture.getFile.getText
          .substring(range.getStartOffset, range.getEndOffset)
      )
    assertTrue(selected.contains("outer(1, second"))
    myFixture.configureByText(
      "head.bend",
      "def f():\n  ou<caret>ter(1, second\n"
    )
    val head = myFixture.getCaretOffset
    val headRanges = new BendSelectionHandler()
      .select(
        myFixture.getFile.findElementAt(head),
        myFixture.getEditor.getDocument.getCharsSequence,
        head,
        myFixture.getEditor
      )
      .asScala
      .toList
      .map(range =>
        myFixture.getFile.getText
          .substring(range.getStartOffset, range.getEndOffset)
      )
    assertTrue(headRanges.contains("outer(1, second"))
    myFixture.configureByText(
      "type-head.bend",
      "def f() -> Li<caret>st<Nat:\n  ?TODO\n"
    )
    val typeOffset = myFixture.getCaretOffset
    val typeRanges = new BendSelectionHandler()
      .select(
        myFixture.getFile.findElementAt(typeOffset),
        myFixture.getEditor.getDocument.getCharsSequence,
        typeOffset,
        myFixture.getEditor
      )
      .asScala
      .toList
      .map(range =>
        myFixture.getFile.getText
          .substring(range.getStartOffset, range.getEndOffset)
      )
    assertTrue(typeRanges.contains("List<Nat"))

  def testLocalAndSameLineStatementRanges(): Unit =
    val source =
      "def f():\n  match x:\n    case C{y}:\n      a : Nat = y\n      b = a; b\n    case D{}:\n      z\n"
    val at = source.indexOf("b = a")
    val spans = BendSelectionStatements
      .at(source, at)
      .map(span => source.substring(span.from, span.until))
    assertTrue(spans.contains("b = a"))
    assertTrue(spans.contains("a : Nat = y\n      b = a; b\n"))
    assertFalse(spans.exists(_.contains("case D")))
    val parallel = "def f():\n  x y = a b\n  x\n"
    assertTrue(
      BendSelectionStatements
        .at(parallel, parallel.lastIndexOf("x"))
        .exists(span =>
          parallel.substring(span.from, span.until) ==
            "x y = a b\n  x\n"
        )
    )
    val crlf = "def f():\r\n  x = 1\r\n  x\r\n"
    assertTrue(
      BendSelectionStatements
        .at(crlf, crlf.lastIndexOf("x"))
        .exists(span =>
          crlf.substring(span.from, span.until) ==
            "x = 1\r\n  x\r\n"
        )
    )

  def testLambdaAndRewriteRanges(): Unit =
    val source = "def f():\n  result = x => x + 1\n  proof = %E : P; E\n"
    val lambda = BendSelectionForms
      .at(source, source.indexOf("x + 1"))
      .map(span => source.substring(span.from, span.until))
    assertTrue(lambda.contains("x => x + 1"))
    val rewrite = BendSelectionForms
      .at(source, source.lastIndexOf("E"))
      .map(span => source.substring(span.from, span.until))
    assertTrue(rewrite.contains("%E : P; E"))
    val statements = BendSelectionStatements
      .at(source, source.indexOf("%E"))
      .map(span => source.substring(span.from, span.until))
    assertFalse(statements.contains("proof = %E : P"))
    val multiline =
      "def f():\n  result = x =>\n    x + 1\n  proof = %E : P;\n    E\n"
    assertTrue(
      BendSelectionForms
        .at(multiline, multiline.indexOf("x + 1"))
        .exists(span =>
          multiline.substring(span.from, span.until) ==
            "x =>\n    x + 1\n"
        )
    )
    assertTrue(
      BendSelectionForms
        .at(multiline, multiline.indexOf("x =>"))
        .exists(span =>
          multiline.substring(span.from, span.until) ==
            "x =>\n    x + 1\n"
        )
    )
    assertTrue(
      BendSelectionForms
        .at(multiline, multiline.lastIndexOf("E"))
        .exists(span =>
          multiline.substring(span.from, span.until) ==
            "%E : P;\n    E\n"
        )
    )

  def testBinaryPlusDoesNotBecomeMarkedNameInEditor(): Unit =
    myFixture.configureByText("binary.bend", "def f():\n  a + <caret>b\n")
    val steps = (0 until 8).map { _ =>
      myFixture.performEditorAction("EditorSelectWord")
      myFixture.getEditor.getSelectionModel.getSelectedText
    }
    assertFalse(steps.contains("+b"))
    assertTrue(steps.contains("a + b"))

  def testMalformedBlockRangesStayWithinCurrentDeclaration(): Unit =
    myFixture.configureByText(
      "malformed.bend",
      "def f():\n  match <caret>x\n    case A:\n      x\ndef next():\n  1\n"
    )
    val offset = myFixture.getCaretOffset
    val ranges = new BendSelectionHandler()
      .select(
        myFixture.getFile.findElementAt(offset),
        myFixture.getEditor.getDocument.getCharsSequence,
        offset,
        myFixture.getEditor
      )
      .asScala
      .toList
      .map(range =>
        myFixture.getFile.getText
          .substring(range.getStartOffset, range.getEndOffset)
      )
    assertFalse(ranges.exists(_.contains("def next")))
