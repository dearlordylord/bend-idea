package com.dearlordylord.bend.idea.features.completion

import com.intellij.codeInsight.lookup.{LookupElement, LookupElementPresentation}
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.*

final class BendCompletionTest extends BasePlatformTestCase:
  override def setUp(): Unit =
    super.setUp()
    com.intellij.codeInsight.template.impl.TemplateManagerImpl.setTemplateTesting(getTestRootDisposable)

  private def names(source: String): Set[String] =
    myFixture.configureByText("sample.bend", source)
    Option(myFixture.completeBasic()).map(_.map(_.getLookupString).toSet).getOrElse(Set.empty)

  def testTopLevelAndBodyKeywords(): Unit =
    val top = names("<caret>")
    assertTrue(top.contains("def"))
    assertTrue(top.contains("type"))
    assertTrue(top.contains("law"))
    assertTrue(top.contains("import"))
    assertFalse(top.contains("class"))
    assertFalse(top.contains("while"))
    assertTrue(names("de<caret>").contains("def"))
    assertTrue(names("@unsafe de<caret>").contains("def"))
    assertTrue(names("type Name <caret>").contains("is"))
    val kind = names("type Name is D<caret>")
    assertTrue(kind.contains("Data") || myFixture.getEditor.getDocument.getText.endsWith("Data"))
    val resultType = names("def f() -> <caret>")
    assertTrue(resultType.contains("Type"))
    assertFalse(resultType.contains("match"))
    assertFalse(resultType.contains("do"))
    assertFalse(resultType.contains("return"))
    for source <- List(
      "def f(x: <caret>)",
      "type Box is Data:\n  Boxed{value: <caret>}",
      "law claim:\n  for x: <caret>",
      "law claim:\n  exs x: <caret>"
    ) do
      val typePosition = names(source)
      assertTrue(source, typePosition.contains("Type"))
      assertTrue(source, typePosition.contains("Data"))
      assertFalse(source, typePosition.contains("match"))
      assertFalse(source, typePosition.contains("do"))
      assertFalse(source, typePosition.contains("return"))
      assertFalse(source, typePosition.contains("for"))
      assertFalse(source, typePosition.contains("exs"))
      assertFalse(source, typePosition.contains("where"))
    assertTrue(names("type Name is Data:\n  <caret>").isEmpty)
    val cases = names("def main():\n  match x:\n    ca<caret>")
    assertTrue(cases.contains("case") || myFixture.getEditor.getDocument.getText.endsWith("case"))
    val body = names("def main():\n  ma<caret>")
    assertTrue(body.contains("match"))
    assertFalse(body.contains("import"))
    assertFalse(names("def main():\n  ret<caret>").contains("return"))

  def testCommentAndClosedOrIncompleteStringsSuppressSuggestions(): Unit =
    for source <- List(
      "# def <caret>",
      "def x(): \"def <caret>\"",
      "def x(): \"def <caret>",
      "def x(): 'def <caret>",
      "def x(): \"escape \\u{broken<caret>"
    ) do assertTrue(source, names(source).isEmpty)

  def testOrdinaryTypingAndIncompleteSourceStillComplete(): Unit =
    myFixture.configureByText("typing.bend", "def main():\n  <caret>")
    myFixture.`type`("ma")
    val candidates = Option(myFixture.completeBasic()).getOrElse(Array.empty[LookupElement])
    assertTrue(candidates.exists(_.getLookupString == "match"))
    val incomplete = names("def main():\n  do IO<Unit>:\n    ret<caret>")
    assertTrue(incomplete.contains("return") || myFixture.getEditor.getDocument.getText.endsWith("return"))

  def testAllSixSnippetsExpandThroughLookup(): Unit =
    val cases = List(
      ("def", "def name(x: U32) -> U32:\n  x", "name"),
      ("type", "type Name is Data:\n  Constructor{value: U32}", "Name"),
      ("law", "law name:\n  for x: Nat\n  {x == x : Nat}", "name"),
      ("match", "match value:\n  case Some{x}:\n    x\n  case None{}:\n    0", "value"),
      ("do", "do IO<Unit>:\n  IO.print(\"Hello, Bend!\")", "Unit"),
      ("import", "import ./module.bend as M", "./module.bend")
    )
    for (trigger, expected, firstField) <- cases do
      val prefix = if trigger == "match" || trigger == "do" then "def main():\n  " else ""
      myFixture.configureByText("snippet.bend", prefix + trigger.take(2) + "<caret>")
      val items = Option(myFixture.completeBasic()).getOrElse(Array.empty[LookupElement])
      val snippet = items.find { item =>
        val presentation = new LookupElementPresentation()
        item.renderElement(presentation)
        item.getLookupString == trigger && presentation.getItemText == trigger + " (snippet)"
      }
      assertTrue(s"Missing $trigger snippet", snippet.isDefined)
      myFixture.getLookup.setCurrentItem(snippet.get)
      myFixture.finishLookup('\t')
      val editor = myFixture.getEditor
      val indented = if prefix.nonEmpty then expected.replace("\n", "\n  ") else expected
      assertEquals(prefix + indented, editor.getDocument.getText)
      assertEquals(s"$trigger first editable field", firstField, editor.getSelectionModel.getSelectedText)
      // Tab through editable fields and verify the final caret is after the skeleton.
      var steps = 0
      while editor.getSelectionModel.hasSelection && steps < 12 do
        myFixture.`type`("\t")
        steps += 1
      assertFalse(s"$trigger template did not finish", editor.getSelectionModel.hasSelection)
      assertEquals(prefix.length + indented.length, editor.getCaretModel.getOffset)

  def testEditingLawPlaceholderUpdatesLinkedOccurrences(): Unit =
    myFixture.configureByText("linked.bend", "la<caret>")
    val items = Option(myFixture.completeBasic()).getOrElse(Array.empty[LookupElement])
    val snippet = items.find { item =>
      val presentation = new LookupElementPresentation()
      item.renderElement(presentation)
      presentation.getItemText == "law (snippet)"
    }.get
    myFixture.getLookup.setCurrentItem(snippet)
    myFixture.finishLookup('\t')
    assertEquals("name", myFixture.getEditor.getSelectionModel.getSelectedText)
    myFixture.`type`("reflexive")
    assertTrue(myFixture.getEditor.getDocument.getText.startsWith("law reflexive:"))
    myFixture.`type`("\t")
    assertEquals("x", myFixture.getEditor.getSelectionModel.getSelectedText)
    myFixture.`type`("value")
    assertTrue(myFixture.getEditor.getDocument.getText.contains("{value == value : Nat}"))

  def testSnippetInsertionCanBeUndone(): Unit =
    myFixture.configureByText("undo.bend", "de<caret>")
    val items = Option(myFixture.completeBasic()).getOrElse(Array.empty[LookupElement])
    val snippet = items.find { item =>
      val presentation = new LookupElementPresentation()
      item.renderElement(presentation)
      presentation.getItemText == "def (snippet)"
    }.get
    myFixture.getLookup.setCurrentItem(snippet)
    myFixture.finishLookup('\t')
    for _ <- 0 until 4 do myFixture.`type`("\t")
    var undos = 0
    while myFixture.getEditor.getDocument.getText != "de" && undos < 3 do
      myFixture.performEditorAction(IdeActions.ACTION_UNDO)
      undos += 1
    assertEquals("de", myFixture.getEditor.getDocument.getText)
