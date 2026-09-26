package com.dearlordylord.bend.idea.features.execution

import com.dearlordylord.bend.idea.syntax.BendLanguage
import com.intellij.execution.actions.{
  ConfigurationContext,
  RunConfigurationProducer
}
import com.intellij.execution.lineMarker.RunLineMarkerContributor
import com.intellij.psi.PsiElement
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.*
import scala.jdk.CollectionConverters.*

final class BendMainRunContextTest extends BasePlatformTestCase:
  def testMainGetsRunGutterAndContextConfiguration(): Unit =
    assertTrue(
      RunLineMarkerContributor.EXTENSION
        .allForLanguage(BendLanguage.instance)
        .asScala
        .exists(_.isInstanceOf[BendMainRunLineMarkerContributor])
    )
    assertTrue(
      RunConfigurationProducer
        .getProducers(getProject)
        .asScala
        .exists(_.isInstanceOf[BendMainRunConfigurationProducer])
    )
    val file = myFixture.configureByText(
      "main.bend",
      "import Base\n\ndef <caret>main() -> IO(Unit):\n  IO.print(\"hello\")\n"
    )
    val nameLeaf =
      file.findElementAt(myFixture.getEditor.getCaretModel.getOffset)
    val marker = new BendMainRunLineMarkerContributor().getInfo(nameLeaf)
    assertNotNull("main should have a Run gutter marker", marker)
    assertTrue(
      "Run gutter should expose standard executor actions",
      marker.actions.nonEmpty
    )

    val context = new ConfigurationContext(nameLeaf)
    val producer = new BendMainRunConfigurationProducer
    val produced = producer.createConfigurationFromContext(context)
    assertNotNull("main should produce a Bend Run configuration", produced)
    val configuration =
      produced.getConfiguration.asInstanceOf[BendRunConfiguration]
    assertEquals(file.getVirtualFile.getPath, configuration.rootPath)
    assertEquals("Bend Run", configuration.getFactory.getId)
    assertTrue(producer.isConfigurationFromContext(configuration, context))

  def testOnlyTopLevelZeroArgumentMainGetsRunGutter(): Unit =
    List(
      "def <caret>helper() -> Nat:\n  0n\n",
      "def <caret>main(value: Nat) -> Nat:\n  value\n",
      "law <caret>main:\n  Type\n",
      "def <caret>main( -> Nat:\n  0n\n"
    ).zipWithIndex.foreach { case (source, index) =>
      val file = myFixture.configureByText(s"other$index.bend", source)
      val leaf = file.findElementAt(myFixture.getEditor.getCaretModel.getOffset)
      assertNull(new BendMainRunLineMarkerContributor().getInfo(leaf))
      assertNull(
        new BendMainRunConfigurationProducer()
          .createConfigurationFromContext(new ConfigurationContext(leaf))
      )
    }

  def testMainMarkerIsOnlyOnNameLeaf(): Unit =
    val file = myFixture.configureByText(
      "main.bend",
      "def <caret>main() -> Nat:\n  0n\n"
    )
    val leaf: PsiElement =
      file.findElementAt(myFixture.getEditor.getCaretModel.getOffset)
    assertNotNull(new BendMainRunLineMarkerContributor().getInfo(leaf))
    assertNull(new BendMainRunLineMarkerContributor().getInfo(leaf.getParent))
