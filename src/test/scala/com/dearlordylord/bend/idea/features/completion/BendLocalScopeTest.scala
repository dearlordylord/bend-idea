package com.dearlordylord.bend.idea.features.completion

import com.dearlordylord.bend.idea.symbols.api.{
  BendBindingKind,
  BendSourceResolution,
  BendSourceSymbols
}
import com.intellij.codeInsight.lookup.{
  LookupElement,
  LookupElementPresentation
}
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.*

final class BendLocalScopeTest extends BasePlatformTestCase:
  private def at(source: String): (
      Set[String],
      List[com.dearlordylord.bend.idea.symbols.api.BendSourceBinding]
  ) =
    myFixture.configureByText("scope.bend", source)
    val offset = myFixture.getEditor.getCaretModel.getOffset
    val bindings = BendSourceSymbols.visibleBindings(myFixture.getFile, offset)
    val completions = Option(myFixture.completeBasic())
      .getOrElse(Array.empty[LookupElement])
      .map(_.getLookupString)
      .toSet
    (completions, bindings)

  private def completes(source: String, name: String): Boolean =
    val (items, _) = at(source)
    items.contains(name) || myFixture.getEditor.getDocument.getText
      .substring(0, myFixture.getEditor.getCaretModel.getOffset)
      .endsWith(name)

  def testTelescopeEarlierParametersOnlyInLaterTypesAndBody(): Unit =
    val own = at(
      "def f(~template: tem<caret>, +later: template) -> later:\n  later"
    )
    assertFalse(own._2.exists(_.name == "template"))
    val laterType = at(
      "def f(~template: Type, +later: tem<caret>) -> later:\n  later"
    )
    assertTrue(laterType._2.exists(_.name == "template"))
    assertEquals(
      "~template: Type",
      laterType._2.find(_.name == "template").get.source
    )
    val result = at(
      "def f(~template: Type, +later: template) -> lat<caret>:\n  later"
    )
    assertTrue(result._2.exists(_.name == "later"))
    val body = at(
      "def f(~template: Type, +later: template) -> later:\n  tem<caret>"
    )
    assertTrue(body._2.exists(_.name == "template"))
    assertTrue(body._2.exists(_.source == "+later: template"))
    val nested = at(
      "def f(~items: List<&2, Nat>, +count: Nat) -> Nat:\n  cou<caret>"
    )
    assertEquals(Set("items", "count"), nested._2.map(_.name).toSet)
    assertEquals(
      "~items: List<&2, Nat>",
      nested._2.find(_.name == "items").get.source
    )
    assertTrue(
      completes(
        "def f(~template: Type, value: template) -> Nat:\n  tem<caret>",
        "template"
      )
    )

  def testInlineAndParenthesizedLambdaDoesNotLeak(): Unit =
    assertTrue(
      at("def f() -> Nat:\n  use(x => x<caret>)")._2.exists(_.name == "x")
    )
    assertTrue(
      at("def f() -> Nat:\n  use(x =>\n    x<caret>\n  )")._2.exists(
        _.name == "x"
      )
    )
    assertFalse(
      at("def f() -> Nat:\n  use(x => x)\n  x<caret>")._2.exists(_.name == "x")
    )
    assertFalse(
      at("def f() -> Nat:\n  use(x => x, x<caret>)")._2.exists(_.name == "x")
    )
    assertEquals(
      BendBindingKind.Lambda,
      at("def f(x: Nat) -> Nat:\n  use(x => x<caret>)")._2
        .find(_.name == "x")
        .get
        .origin
    )

  def testLambdaQuantityAppearsInCompletionDetail(): Unit =
    myFixture.configureByText(
      "quantity.bend",
      "def f(xtra: Nat) -> Nat:\n  use(+x => x<caret>)"
    )
    val offset = myFixture.getEditor.getCaretModel.getOffset
    val binding = BendSourceSymbols
      .visibleBindings(myFixture.getFile, offset)
      .find(_.name == "x")
      .get
    assertEquals("+x", binding.source)
    assertEquals(
      myFixture.getFile.getText.indexOf("+x") + 1,
      binding.handle.nameOffset
    )
    val item = Option(myFixture.completeBasic())
      .getOrElse(Array.empty[LookupElement])
      .find(_.getLookupString == "x")
      .get
    val presentation = new LookupElementPresentation()
    item.renderElement(presentation)
    assertTrue(presentation.getTailText.contains("+x"))

  def testLocalsStopAtColumnZeroEvenWhenSurfacePsiRetainsLine(): Unit =
    val (items, bindings) = at("def f(x: Nat) -> Nat:\n  x\nx<caret>")
    assertFalse(bindings.exists(_.name == "x"))
    assertFalse(items.contains("x"))
    assertEquals(
      BendSourceResolution.Unresolved,
      BendSourceSymbols.resolveCurrentFile(
        myFixture.getFile,
        myFixture.getEditor.getCaretModel.getOffset,
        "x"
      )
    )

  def testLetContinuationAndUnfinishedRhs(): Unit =
    assertFalse(
      at("def f() -> Nat:\n  value = use(\n    val<caret>\n  )\n  value")._2
        .exists(_.name == "value")
    )
    assertTrue(
      at("def f() -> Nat:\n  value = use(\n    1\n  )\n  val<caret>")._2.exists(
        _.name == "value"
      )
    )
    assertTrue(
      at("def f() -> Nat:\n  (value = 1; val<caret>)")._2.exists(
        _.name == "value"
      )
    )
    assertFalse(
      at("def f() -> Nat:\n  (value = 1; value)\n  val<caret>")._2.exists(
        _.name == "value"
      )
    )
    assertFalse(
      at("def f() -> Nat:\n  value = <caret>\n  value")._2.exists(
        _.name == "value"
      )
    )

  def testParallelLetRhsUsesOuterScopeAndBindersStartTogether(): Unit =
    val rhs = at(
      "def f(outer: Nat) -> Nat:\n  left right = outer lef<caret>\n  left"
    )
    assertTrue(rhs._2.exists(_.name == "outer"))
    assertFalse(rhs._2.exists(_.name == "left"))
    assertFalse(rhs._2.exists(_.name == "right"))
    val continuation = at(
      "def f(outer: Nat) -> Nat:\n  left right = outer outer; rig<caret>"
    )
    assertTrue(continuation._2.exists(_.name == "left"))
    assertTrue(continuation._2.exists(_.name == "right"))
    assertTrue(
      completes("def f() -> Nat:\n  left right = 1 2; rig<caret>", "right")
    )
    assertEquals(
      Set(BendBindingKind.Let),
      continuation._2
        .filter(b => Set("left", "right").contains(b.name))
        .map(_.origin)
        .toSet
    )
    val shadowedRhs = at(
      "def f(left: Nat, right: Nat) -> Nat:\n  left right = lef<caret> right; left"
    )
    assertEquals(
      Set(BendBindingKind.Parameter),
      shadowedRhs._2
        .filter(b => Set("left", "right").contains(b.name))
        .map(_.origin)
        .toSet
    )
    val shadowedBody = at(
      "def f(left: Nat, right: Nat) -> Nat:\n  left right = left right; lef<caret>"
    )
    assertEquals(
      Set(BendBindingKind.Let),
      shadowedBody._2
        .filter(b => Set("left", "right").contains(b.name))
        .map(_.origin)
        .toSet
    )

  def testNearestShadowingPreservesBinderIdentity(): Unit =
    val outer =
      at("def f(x: Nat) -> Nat:\n  x<caret>")._2.find(_.name == "x").get
    val inner =
      at("def f(x: Nat) -> Nat:\n  x = 1; x<caret>")._2.find(_.name == "x").get
    assertNotEquals(outer.handle, inner.handle)
    assertEquals(BendBindingKind.Let, inner.origin)
    assertEquals(
      1,
      at("def f(x: Nat) -> Nat:\n  x = 1; x<caret>")._2.count(_.name == "x")
    )
    val offset = myFixture.getEditor.getCaretModel.getOffset
    assertEquals(
      inner.handle,
      BendSourceSymbols.resolveCurrentFile(myFixture.getFile, offset, "x") match
        case BendSourceResolution.ResolvedBinder(binding) => binding.handle
        case other => fail(s"Expected nearest binder, got $other"); inner.handle
    )
    val (items, bindings) = at(
      "def target() -> Nat:\n  0\ndef f(target: Nat) -> Nat:\n  tar<caret>"
    )
    assertEquals(1, bindings.count(_.name == "target"))
    assertTrue(items.count(_ == "target") <= 1)
    assertTrue(
      completes(
        "def target() -> Nat:\n  0\ndef f(target: Nat) -> Nat:\n  tar<caret>",
        "target"
      )
    )
