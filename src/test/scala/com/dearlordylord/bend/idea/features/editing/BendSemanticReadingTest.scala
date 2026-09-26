package com.dearlordylord.bend.idea.features.editing

import com.dearlordylord.bend.idea.syntax.lexer.BendColors
import com.dearlordylord.bend.idea.syntax.psi.BendDeclaration
import com.dearlordylord.bend.idea.toolchain.api.{
  BendToolchainChoices,
  BendToolchainSettings
}
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.TextRange
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.*
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

final class BendSemanticReadingTest extends BasePlatformTestCase:
  private var original: BendToolchainChoices = null
  private var temporary: Path = null

  override def setUp(): Unit =
    super.setUp()
    val settings = ApplicationManager.getApplication.getService(
      classOf[BendToolchainSettings]
    )
    original = settings.choices
    temporary = Files.createTempDirectory("bend-semantic-reading")
    val base = Files.writeString(temporary.resolve("base.bend"), "")
    settings.update(BendToolchainChoices(baseSource = base.toString))

  override def tearDown(): Unit =
    try
      ApplicationManager.getApplication
        .getService(classOf[BendToolchainSettings])
        .update(original)
      val stream = Files.walk(temporary)
      try
        stream
          .sorted(java.util.Comparator.reverseOrder())
          .forEach(path => {
            val _ = Files.deleteIfExists(path)
          })
      finally stream.close()
    finally super.tearDown()

  private def hasSemanticColor(
      key: com.intellij.openapi.editor.colors.TextAttributesKey,
      text: String
  ): Boolean =
    myFixture.doHighlighting().asScala.exists { info =>
      info.forcedTextAttributesKey == key &&
      info.getText == text
    }

  def testSemanticColorsUseResolvedSourceCategories(): Unit =
    val library = myFixture.addFileToProject(
      "library.bend",
      "def helper(value):\n  value\n"
    )
    assertNotNull(library)
    val source =
      "import ./library.bend as Library\ntype Box is Data:\n  Box{}\nlaw claim:\n  for value: Box\n  {value == value : Box}\ndef use(value: Box) -> Box:\n  local = value; local\ndef main(argument: Box) -> Box:\n  argument\n  Library.helper(argument)\n  claim\n  use(argument)\n  Box{}\n  unknown\n  \"Box claim\"\n  # Box claim\n  argument\n"
    myFixture.configureByText("main.bend", source)
    assertTrue(hasSemanticColor(BendColors.SemanticParameter, "argument"))
    assertTrue(hasSemanticColor(BendColors.SemanticLocal, "local"))
    assertTrue(hasSemanticColor(BendColors.SemanticConstructor, "Box"))
    assertTrue(hasSemanticColor(BendColors.SemanticDatatype, "Box"))
    assertTrue(hasSemanticColor(BendColors.SemanticLaw, "claim"))
    assertTrue(hasSemanticColor(BendColors.SemanticDefinition, "helper"))
    assertTrue(hasSemanticColor(BendColors.SemanticAlias, "Library"))
    assertFalse(hasSemanticColor(BendColors.SemanticDefinition, "unknown"))

  def testBreadcrumbsFollowNestedBodyAndDeclarationStructure(): Unit =
    val source =
      "def main(value):\n  match value:\n    case Box{item}:\n      do IO<Unit>:\n        ?TO<caret>DO\n"
    myFixture.configureByText("main.bend", source)
    val file = myFixture.getFile
    val offset = myFixture.getCaretOffset + 1
    val element = file.findElementAt(offset)
    val provider = new BendBreadcrumbsProvider
    assertTrue(provider.acceptElement(element))
    assertEquals("do IO<Unit>", provider.getElementInfo(element))
    val caseElement = provider.getParent(element)
    assertEquals("case Box{item}", provider.getElementInfo(caseElement))
    val matchElement = provider.getParent(caseElement)
    assertEquals("match value", provider.getElementInfo(matchElement))
    val declaration = provider.getParent(matchElement)
    assertTrue(declaration.isInstanceOf[BendDeclaration])
    assertEquals("def main", provider.getElementInfo(declaration))

  def testSelectionExpandsFromTokenToArgumentsCallsBlocksAndDeclaration()
      : Unit =
    val source =
      "def main():\n  outer(1, inner(2, <caret>3))\n"
    myFixture.configureByText("main.bend", source)
    val offset = myFixture.getCaretOffset
    val element = myFixture.getFile.findElementAt(offset)
    val ranges = new BendSelectionHandler()
      .select(
        element,
        myFixture.getEditor.getDocument.getCharsSequence,
        offset,
        myFixture.getEditor
      )
      .asScala
      .toList
    val selected = ranges.map(range => text(range))
    assertTrue(selected.contains("3"))
    assertTrue(selected.contains("inner(2, 3)"))
    assertTrue(selected.contains("outer(1, inner(2, 3))"))
    assertTrue(selected.contains("def main():\n  outer(1, inner(2, 3))"))
    assertEquals(
      ranges.sortBy(range => (range.getLength, range.getStartOffset)),
      ranges
    )

    myFixture.configureByText(
      "law.bend",
      "law reflexive:\n  for value: Nat\n  {<caret>value == value : Nat}\n"
    )
    val proofOffset = myFixture.getCaretOffset
    val proofElement = myFixture.getFile.findElementAt(proofOffset)
    val proofRanges = new BendSelectionHandler()
      .select(
        proofElement,
        myFixture.getEditor.getDocument.getCharsSequence,
        proofOffset,
        myFixture.getEditor
      )
      .asScala
      .toList
    assertTrue(
      proofRanges.exists(range => text(range) == "{value == value : Nat}")
    )

  private def text(range: TextRange): String =
    myFixture.getFile.getText
      .substring(range.getStartOffset, range.getEndOffset)
