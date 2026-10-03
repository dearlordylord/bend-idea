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

  def testNestedQualifiedConstructorPatternUsesExactResolvedRanges(): Unit =
    val module = myFixture.addFileToProject(
      "creature.bend",
      "type Creature is Data:\n  Cr{vitals, economy, ac, features, positive_ac, valid_grant, valid_features}\ntype Vitals is Data:\n  Alive{state, temp, kind}\ntype Economy is Data:\n  Ec{available, bonus, grant}\n"
    )
    val source =
      "import ./creature.bend as T\ntype Bool is Data:\n  True{}\n  False{}\ndef actor_available_creature(+actor: T.Creature) -> Bool:\n  match actor:\n    case T.Cr{T.Alive{state,temp,kind},T.Ec{True{},bonus,grant},ac,features,positive_ac,valid_grant,valid_features}:\n      True{}\n    case _: False{}\ndef unresolved(actor):\n  match actor:\n    case T.Missing{field}:\n      field\n"
    myFixture.configureByText("main.bend", source)
    val highlights = myFixture.doHighlighting().asScala.toList
    def exactColor(
        key: com.intellij.openapi.editor.colors.TextAttributesKey,
        start: Int,
        spelling: String
    ): Unit =
      assertTrue(
        s"$spelling at $start has $key",
        highlights.exists(info =>
          info.forcedTextAttributesKey == key && info.getText == spelling &&
            info.getStartOffset == start && info.getEndOffset == start + spelling.length
        )
      )
    def noConstructor(start: Int, spelling: String): Unit =
      assertFalse(
        s"$spelling at $start is not a constructor",
        highlights.exists(info =>
          info.forcedTextAttributesKey == BendColors.SemanticConstructor &&
            info.getStartOffset < start + spelling.length && info.getEndOffset > start
        )
      )
    val patternStart = source.indexOf("case T.Cr")
    for member <- List("Cr", "Alive", "Ec") do
      val aliasAt = source.indexOf(s"T.$member", patternStart)
      val memberAt = aliasAt + 2
      exactColor(BendColors.SemanticAlias, aliasAt, "T")
      exactColor(BendColors.SemanticConstructor, memberAt, member)
      assertFalse(
        "Dot is outside semantic ranges",
        highlights.exists(info =>
          Set(BendColors.SemanticAlias, BendColors.SemanticConstructor)
            .contains(info.forcedTextAttributesKey) &&
            info.getStartOffset <= aliasAt + 1 && info.getEndOffset > aliasAt + 1
        )
      )
      val alias = myFixture.getFile.findReferenceAt(aliasAt)
      assertNotNull(alias)
      assertEquals(new TextRange(0, 1), alias.getRangeInElement)
      val aliasTarget = alias.resolve()
      assertNotNull(aliasTarget)
      assertEquals(myFixture.getFile, aliasTarget.getContainingFile)
      val reference = myFixture.getFile.findReferenceAt(memberAt)
      assertNotNull(reference)
      assertEquals(
        new TextRange(2, 2 + member.length),
        reference.getRangeInElement
      )
      val target = reference.resolve()
      assertNotNull(target)
      assertEquals(module, target.getContainingFile)
      assertEquals(module.getText.indexOf(s"$member{"), target.getTextOffset)
    val patternTrue = source.indexOf("True{}", patternStart)
    val bodyTrue = source.indexOf("True{}", patternTrue + 1)
    for at <- List(patternTrue, bodyTrue) do
      exactColor(BendColors.SemanticConstructor, at, "True")
      val reference = myFixture.getFile.findReferenceAt(at)
      assertNotNull(reference)
      val target = reference.resolve()
      assertNotNull(target)
      assertEquals(myFixture.getFile, target.getContainingFile)
      assertEquals(source.indexOf("True{}"), target.getTextOffset)
    for field <- List(
        "state",
        "temp",
        "kind",
        "bonus",
        "grant",
        "ac",
        "features",
        "positive_ac",
        "valid_grant",
        "valid_features"
      )
    do noConstructor(source.indexOf(field, patternStart), field)
    noConstructor(source.indexOf("case _") + 5, "_")
    val missingAt = source.indexOf("T.Missing") + 2
    noConstructor(missingAt, "Missing")
    val missing = myFixture.getFile.findReferenceAt(missingAt)
    assertNotNull(missing)
    assertNull(
      "Unresolved constructor-shaped member stays unresolved",
      missing.resolve()
    )

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
