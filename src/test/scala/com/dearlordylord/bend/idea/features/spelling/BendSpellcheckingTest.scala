package com.dearlordylord.bend.idea.features.spelling

import com.dearlordylord.bend.idea.syntax.lexer.BendTokens
import com.intellij.openapi.util.TextRange
import com.intellij.psi.{
  PsiComment,
  PsiElement,
  PsiRecursiveElementWalkingVisitor
}
import com.intellij.spellchecker.inspections.{SpellCheckingInspection, Splitter}
import com.intellij.spellchecker.tokenizer.{
  SpellcheckingStrategy,
  TokenConsumer,
  Tokenizer
}
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.Consumer
import scala.collection.mutable
import scala.jdk.CollectionConverters.*
import org.junit.Assert.*

final class BendSpellcheckingTest extends BasePlatformTestCase:
  def testRegisteredInspectionHighlightsCommentAndStringTypos(): Unit =
    myFixture.enableInspections(new SpellCheckingInspection)
    val file = myFixture.configureByText(
      "spellcheck.bend",
      "import Base\n\n# A commment with a mispeled word.\n" +
        "def spellcheck_text() -> String:\n" +
        "  \"This strng has an intentional spelling error.\"\n"
    )
    val highlighted = myFixture.doHighlighting().asScala.toList.map { info =>
      file.getText.substring(info.getStartOffset, info.getEndOffset)
    }
    List("commment", "mispeled", "strng").foreach { typo =>
      assertTrue(
        s"Missing spelling highlight for $typo: $highlighted",
        highlighted.contains(typo)
      )
    }
    assertFalse(highlighted.contains("spellcheck_text"))

  def testCommentsAndOrdinaryStringTextUseIDETextTokenizers(): Unit =
    val file = myFixture.configureByText(
      "spell.bend",
      "# commment prose\n" +
        "def sample() -> String:\n  \"wrng\\ntext\"\n" +
        "def external() -> IO(Unit):\n  import \"../badword/native.c\"\n"
    )
    val strategy = new BendSpellcheckingStrategy
    val comment =
      PsiTreeUtilElements.ofType(file, classOf[PsiComment]).head
    assertTrue(
      strategy.getTokenizer(comment) ne SpellcheckingStrategy.EMPTY_TOKENIZER
    )
    val commentRanges = tokenRanges(strategy.getTokenizer(comment), comment)
    val proseRanges = commentRanges.flatMap { capture =>
      val areas = mutable.ListBuffer.empty[TextRange]
      capture.splitter.split(
        capture.text,
        capture.range,
        new Consumer[TextRange]:
          override def consume(area: TextRange): Unit =
            val _ = areas.addOne(area)
      )
      areas.toList.map(area => capture.offset + area.getStartOffset)
    }
    assertTrue(commentRanges.toString, proseRanges.nonEmpty)
    assertTrue(proseRanges.toString, proseRanges.forall(_ > 0))

    val contents = tokenElements(file, BendTokens.StringContent)
    val prose = contents.find(_.getText == "wrng").get
    val escapedTail = contents.find(_.getText == "text").get
    val path = contents.find(_.getText.contains("badword")).get
    assertTrue(
      strategy.getTokenizer(prose) eq SpellcheckingStrategy.TEXT_TOKENIZER
    )
    assertTrue(
      strategy.getTokenizer(path) eq SpellcheckingStrategy.EMPTY_TOKENIZER
    )
    assertTrue(!prose.getText.contains("\\"))
    assertTrue(!escapedTail.getText.contains("\\"))

    val escape = tokenElements(file, BendTokens.Escape).head
    assertTrue(
      strategy.getTokenizer(escape) eq SpellcheckingStrategy.EMPTY_TOKENIZER
    )
    val identifier = tokenElements(file, BendTokens.FunctionName).head
    assertTrue(
      strategy.getTokenizer(identifier) eq SpellcheckingStrategy.EMPTY_TOKENIZER
    )
    val operator = tokenElements(file, BendTokens.Operator).head
    assertTrue(
      strategy.getTokenizer(operator) eq SpellcheckingStrategy.EMPTY_TOKENIZER
    )

  def testCorrectionRangeStaysInsideStringContentBeforeEscape(): Unit =
    val file = myFixture.configureByText(
      "correction.bend",
      "def sample() -> String:\n  \"wrng\\ntext\"\n"
    )
    val content = tokenElements(file, BendTokens.StringContent)
      .find(_.getText == "wrng")
      .get
    val range = content.getTextRange
    assertEquals(
      "wrng",
      file.getText.substring(range.getStartOffset, range.getEndOffset)
    )
    val document = myFixture.getEditor.getDocument
    com.intellij.openapi.command.WriteCommandAction
      .writeCommandAction(getProject, file)
      .run(
        new com.intellij.util.ThrowableRunnable[RuntimeException]:
          override def run(): Unit =
            document.replaceString(
              range.getStartOffset,
              range.getEndOffset,
              "correct"
            )
      )
    com.intellij.psi.PsiDocumentManager
      .getInstance(getProject)
      .commitAllDocuments()
    assertTrue(file.getText.contains("\"correct\\ntext\""))

  private def tokenElements(
      file: com.intellij.psi.PsiFile,
      kind: com.intellij.psi.tree.IElementType
  ): List[PsiElement] =
    val result = mutable.ListBuffer.empty[PsiElement]
    file.accept(new PsiRecursiveElementWalkingVisitor:
      override def visitElement(element: PsiElement): Unit =
        if element.getNode != null && element.getNode.getElementType == kind
        then
          val _ = result.addOne(element)
        super.visitElement(element))
    result.toList

  private final case class TokenCapture(
      text: String,
      offset: Int,
      range: TextRange,
      splitter: Splitter
  )

  private def tokenRanges(
      tokenizer: Tokenizer[? <: PsiElement],
      element: PsiElement
  ): List[TokenCapture] =
    val ranges = mutable.ListBuffer.empty[TokenCapture]
    val consumer = new TokenConsumer:
      override def consumeToken(
          tokenElement: PsiElement,
          text: String,
          useRename: Boolean,
          offset: Int,
          range: TextRange,
          splitter: Splitter
      ): Unit =
        val _ = ranges.addOne(TokenCapture(text, offset, range, splitter))
    tokenizer
      .asInstanceOf[Tokenizer[PsiElement]]
      .tokenize(element, consumer)
    ranges.toList

private object PsiTreeUtilElements:
  def ofType[T <: PsiElement](
      file: com.intellij.psi.PsiFile,
      elementType: Class[T]
  ): List[T] =
    com.intellij.psi.util.PsiTreeUtil
      .findChildrenOfType(file, elementType)
      .asScala
      .toList
