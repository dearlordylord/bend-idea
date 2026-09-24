package com.dearlordylord.bend.idea.features.completion

import com.dearlordylord.bend.idea.symbols.api.{
  BendBindingKind,
  BendSourceBinding,
  BendSourceSymbols
}
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.*

final class BendMatchDoScopeTest extends BasePlatformTestCase:
  private def visible(source: String): List[BendSourceBinding] =
    myFixture.configureByText("scope.bend", source)
    BendSourceSymbols.visibleBindings(
      myFixture.getFile,
      myFixture.getEditor.getCaretModel.getOffset
    )

  private def names(source: String): Set[String] =
    visible(source).map(_.name).toSet

  private def completionContains(source: String, name: String): Boolean =
    myFixture.configureByText("scope.bend", source)
    Option(myFixture.completeBasic())
      .getOrElse(Array.empty[LookupElement])
      .exists(_.getLookupString == name) ||
    myFixture.getEditor.getDocument.getText
      .take(myFixture.getEditor.getCaretModel.getOffset)
      .endsWith(name)

  def testNestedConstructorTupleAndListPatternBranches(): Unit =
    val first =
      "def f(xs: List<U32>) -> U32:\n  match xs:\n    case Cons{Tuple{+head, tail}, rest}:\n      hea<caret>"
    val bindings = visible(first)
    assertEquals(
      Set("head", "tail", "rest"),
      bindings.filter(_.origin == BendBindingKind.Pattern).map(_.name).toSet
    )
    assertEquals("+head", bindings.find(_.name == "head").get.source)
    assertTrue(completionContains(first, "head"))
    assertFalse(
      names(
        "def f(xs: List<U32>) -> U32:\n  match xs:\n    case Cons{Tuple{+head, tail}, rest}:\n      head\n    case Nil{}:\n      hea<caret>"
      ).contains("head")
    )
    assertEquals(
      Set("u", "v"),
      visible(
        "def f(xs: List<U32>) -> U32:\n  match xs:\n    case [+u, v]:\n      u<caret>"
      )
        .filter(_.origin == BendBindingKind.Pattern)
        .map(_.name)
        .toSet
    )

  def testNestedCasesAndMalformedNeighborRemainIsolated(): Unit =
    val nested =
      "def f(a: Nat, b: Nat):\n  match a:\n    case Outer{outer}:\n      match b:\n        case Inner{inner}:\n          inn<caret>\n        case Other{}:\n          0\n    case Else{}:\n      0"
    assertEquals(
      Set("outer", "inner"),
      visible(nested)
        .filter(_.origin == BendBindingKind.Pattern)
        .map(_.name)
        .toSet
    )
    assertEquals(
      Set("outer"),
      visible(
        nested
          .replace("inn<caret>", "inner")
          .replace(
            "case Other{}:\n          0",
            "case Other{}:\n          out<caret>"
          )
      )
        .filter(_.origin == BendBindingKind.Pattern)
        .map(_.name)
        .toSet
    )
    assertFalse(
      names(
        "def f(a: Nat):\n  match a:\n    case Pair{left, right}:\n      left\n    case Broken{:\n      rig<caret>"
      ).contains("right")
    )

  def testTypedDoBindingsBeginAfterRhsAndStopAtBlockEnd(): Unit =
    assertFalse(
      names(
        "def f() -> IO<Unit>:\n  do IO<Unit>:\n    value : Nat <- IO.pure(Nat, val<caret>)\n    value"
      ).contains("value")
    )
    assertFalse(
      names(
        "def f() -> IO<Unit>:\n  do IO<Unit>:\n    value : Nat = val<caret>; value"
      ).contains("value")
    )
    assertFalse(
      names(
        "def f() -> IO<Unit>:\n  do IO<Unit>:\n    value : Nat =\n    val<caret>"
      ).contains("value")
    )
    val later = visible(
      "def f() -> IO<Unit>:\n  do IO<Unit>:\n    value : Nat <- IO.pure(Nat, 1)\n    val<caret>"
    )
    assertEquals(BendBindingKind.Do, later.find(_.name == "value").get.origin)
    assertTrue(
      completionContains(
        "def f() -> IO<Unit>:\n  do IO<Unit>:\n    value : Nat = 1; val<caret>",
        "value"
      )
    )
    assertFalse(
      names(
        "def f() -> IO<Unit>:\n  do IO<Unit>:\n    value : Nat <- IO.pure(Nat, 1)\n    return value\n  val<caret>"
      ).contains("value")
    )
    assertFalse(
      names("def f() -> IO<Unit>:\n  do IO<Unit>:\n    return val<caret>")
        .contains("value")
    )
    assertEquals(
      BendBindingKind.Do,
      visible(
        "def f() -> IO<Unit>:\n  do IO<Unit>:\n    value : Nat = 1\n    return val<caret>"
      ).find(_.name == "value").get.origin
    )

  def testDoBindingStopsAtEnclosingCaseEvenWhenSiblingIsColumnAligned(): Unit =
    val source =
      "def f(x: Nat) -> IO<Unit>:\n  match x:\n    case A{a}:\n      do IO<Unit>:\n        v : Nat = 1\n        return v\n    case B{}:\n      v<caret>"
    assertFalse(names(source).contains("v"))
    assertTrue(
      names(
        "def f(x: Nat) -> IO<Unit>:\n  match x:\n    case A{a}:\n      do IO<Unit>:\n        v : Nat = 1\n        return v<caret>\n    case B{}:\n      0"
      ).contains("v")
    )

  def testTypedDoBindingAcceptsDeeperContinuationColumn(): Unit =
    val source =
      "def f() -> Result<&2, &2, U32, U32>:\n  do Result<&2, &2, U32, U32>:\n  x : U32 <- Done{1}\n    return x<caret>"
    assertEquals(
      BendBindingKind.Do,
      visible(source).find(_.name == "x").get.origin
    )
    assertEquals(
      BendBindingKind.Do,
      visible(
        "def f() -> IO<Unit>:\n  do IO<Unit>:\n    x : Nat = 1\n  return x<caret>"
      ).find(_.name == "x").get.origin
    )
    assertEquals(
      BendBindingKind.Do,
      visible(
        "def f() -> IO<Unit>:\n  do IO<Unit>:\n    x : Nat =\n      1\n    return x<caret>"
      ).find(_.name == "x").get.origin
    )
    assertEquals(
      BendBindingKind.Do,
      visible(
        "def f() -> IO<Unit>:\n  do IO<Unit>:\n    x : Nat <-\n      IO.pure(Nat, 1)\n    return x<caret>"
      ).find(_.name == "x").get.origin
    )

  def testArrayWriteRebindsOnlyForContinuation(): Unit =
    val source =
      "def f(arr: Array<Nat>) -> Array<Nat>:\n  Array.set(Nat, arr, 0, 1)\n  arr<caret>"
    val rebound = visible(source).find(_.name == "arr").get
    assertEquals(BendBindingKind.Let, rebound.origin)
    assertNotEquals(
      visible("def f(arr: Array<Nat>) -> IO<Unit>:\n  arr<caret>")
        .find(_.name == "arr")
        .get
        .handle,
      rebound.handle
    )
    assertEquals(
      BendBindingKind.Parameter,
      visible(
        "def f(arr: Array<Nat>) -> Array<Nat>:\n  Array.set(Nat, arr, 0, 1)<caret>"
      ).find(_.name == "arr").get.origin
    )
    assertEquals(
      BendBindingKind.Parameter,
      visible(
        "def f(arr: Array<Nat>) -> IO<Unit>:\n  do IO<Unit>:\n    Array.set(Nat, arr, 0, 1)\n    arr<caret>"
      ).find(_.name == "arr").get.origin
    )
    assertEquals(
      BendBindingKind.Let,
      visible(
        "def f(arr: Array<Nat>) -> Array<Nat>:\n  arr[0] <- 1\n  arr<caret>"
      ).find(_.name == "arr").get.origin
    )
    assertEquals(
      BendBindingKind.Parameter,
      visible(
        "def f(arr: Array<Nat>) -> Array<Nat>:\n  arr[0] <- arr<caret>\n  arr"
      ).find(_.name == "arr").get.origin
    )
    assertEquals(
      BendBindingKind.Parameter,
      visible(
        "def f(arr: Array<Nat>):\n  y = Array.set(Nat, arr, 0, 1); arr<caret>"
      ).find(_.name == "arr").get.origin
    )
    assertEquals(
      BendBindingKind.Parameter,
      visible(
        "def f(arr: Array<Nat>):\n  use(Array.set(Nat, arr, 0, 1)); arr<caret>"
      ).find(_.name == "arr").get.origin
    )
    val inlineDefinition =
      "def f(arr: Array<Nat>): Array.set(Nat, arr, 0, 1); arr<caret>"
    val inlineBinding = visible(inlineDefinition).find(_.name == "arr").get
    assertEquals(BendBindingKind.Let, inlineBinding.origin)
    assertEquals(
      inlineDefinition.indexOf("arr, 0"),
      inlineBinding.handle.nameOffset
    )
    assertEquals(
      BendBindingKind.Let,
      visible(
        "def f(arr: Array<Nat>):\n  match arr:\n    case A{}: Array.set(Nat, arr, 0, 1); arr<caret>"
      ).find(_.name == "arr").get.origin
    )

  def testColumnSensitiveInlineAndEmptyMatch(): Unit =
    assertTrue(
      names("def f(x: Nat): match x:\n  case C{a}: a<caret>").contains("a")
    )
    assertFalse(
      names("def f(x: Nat): match x:\n  case C{a}: a\n  case D{b}: a<caret>")
        .contains("a")
    )
    assertEquals(
      Set("b"),
      visible(
        "def f(x: Nat):\n  match x:\n   case C{a}: a\n        case D{b}: b<caret>"
      )
        .filter(_.origin == BendBindingKind.Pattern)
        .map(_.name)
        .toSet
    )
    assertFalse(names("def f(x: Nat):\n  match x:\n  a<caret>").contains("a"))
    assertFalse(
      names("def f(x: Nat):\n  match x:\n    0\n    case C{a}: a<caret>")
        .contains("a")
    )
    assertTrue(
      names("def f() -> IO<Unit>:\n  do IO<Unit>: v : Nat = 1; v<caret>")
        .contains("v")
    )
