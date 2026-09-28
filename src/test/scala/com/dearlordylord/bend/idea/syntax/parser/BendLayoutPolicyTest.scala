package com.dearlordylord.bend.idea.syntax.parser

import org.junit.Assert.*
import org.junit.Test

final class BendLayoutPolicyTest:
  private val twoSpaces = BendLayoutPolicy.Settings(2, false, 8)

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

  @Test def tabsUseVisualColumnsAndRejectUnrepresentableLevels(): Unit =
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
    assertEquals(None, BendIndentPolicy.afterEnter(root, root.length, wideTabs))
    val mixed = "def f():\n \tcase Zero{}:\n"
    assertEquals(None, BendIndentPolicy.afterEnter(mixed, mixed.length, tabs))
    assertEquals(
      None,
      BendIndentPolicy.backspace("def f():\n \tvalue", 11, tabs)
    )
