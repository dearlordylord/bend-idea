package com.dearlordylord.bend.idea.features.editing

import com.intellij.application.options.CodeStyle
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.*

final class BendIndentTest extends BasePlatformTestCase:
  private def enter(source: String): String =
    myFixture.configureByText("indent.bend", source)
    myFixture.performEditorAction(IdeActions.ACTION_EDITOR_ENTER)
    myFixture.getEditor.getDocument.getText

  def testTwoSpaceDefaultsAndDeclarationEnter(): Unit =
    val file = myFixture.configureByText("options.bend", "def f(): 1")
    assertEquals(2, CodeStyle.getIndentOptions(file).INDENT_SIZE)
    assertEquals("def f():\n  ", enter("def f():<caret>"))

  def testNestedCaseAndDoEnter(): Unit =
    assertEquals(
      "def f(x: Nat):\n  match x:\n    case Zero{}:\n      ",
      enter("def f(x: Nat):\n  match x:\n    case Zero{}:<caret>")
    )
    assertEquals(
      "def f():\n  do IO<Unit>:\n    ",
      enter("def f():\n  do IO<Unit>:<caret>")
    )

  def testBackspaceStepsThroughLeadingIndentOnly(): Unit =
    myFixture.configureByText(
      "backspace.bend",
      "def f():\n  match x:\n    case Zero{}:\n      <caret>value"
    )
    myFixture.performEditorAction(IdeActions.ACTION_EDITOR_BACKSPACE)
    assertEquals(
      "def f():\n  match x:\n    case Zero{}:\n    value",
      myFixture.getEditor.getDocument.getText
    )
    myFixture.performEditorAction(IdeActions.ACTION_EDITOR_BACKSPACE)
    assertEquals(
      "def f():\n  match x:\n    case Zero{}:\n  value",
      myFixture.getEditor.getDocument.getText
    )

  def testContinuedAndIncompleteHeaders(): Unit =
    assertEquals("def f(x: Nat,\n  ", enter("def f(x: Nat,<caret>"))
    assertEquals("def f(\n  x: Nat,\n  ", enter("def f(\n  x: Nat,<caret>"))
    assertEquals("def broken(\n  ", enter("def broken(<caret>"))
    assertEquals(
      "def f():\n  match x:\n    ",
      enter("def f():\n  match x:<caret>")
    )

  def testCommentAndUnfinishedLiteralEnter(): Unit =
    assertEquals("def f():\n  # note\n  ", enter("def f():\n  # note<caret>"))
    assertEquals("def f(): \"open\n", enter("def f(): \"open<caret>"))

  def testBackspaceInExpressionDoesNotChangeLeadingIndentOrOperators(): Unit =
    myFixture.configureByText(
      "expression.bend",
      "def f():\n  value<caret> + other"
    )
    myFixture.performEditorAction(IdeActions.ACTION_EDITOR_BACKSPACE)
    assertEquals(
      "def f():\n  valu + other",
      myFixture.getEditor.getDocument.getText
    )

  def testEnterInsideInlineCallKeepsFollowingExpression(): Unit =
    assertEquals(
      "def f(): call(\n  value)",
      enter("def f(): call(<caret>value)")
    )

  def testBlankLineBackspaceKeepsCodeUntouched(): Unit =
    myFixture.configureByText("blank.bend", "def f():\n    <caret>\n  value")
    myFixture.performEditorAction(IdeActions.ACTION_EDITOR_BACKSPACE)
    assertEquals(
      "def f():\n  \n  value",
      myFixture.getEditor.getDocument.getText
    )
