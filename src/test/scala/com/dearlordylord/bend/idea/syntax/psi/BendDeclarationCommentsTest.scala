package com.dearlordylord.bend.idea.syntax.psi

import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.*

final class BendDeclarationCommentsTest extends BasePlatformTestCase:
  def testSourceCommentsReadsOnlyTheAdjacentCommentBlock(): Unit =
    val prefix = "\n" * 4096
    val file = myFixture.addFileToProject(
      "comments.bend",
      prefix + "# primary\r\n# secondary\r\ndef selected():\r\n  0\r\n"
    )
    val selected = PsiTreeUtil
      .findChildrenOfType(file, classOf[BendDefinition])
      .stream()
      .filter(_.getName == "selected")
      .findFirst()
      .orElse(null)

    assertNotNull(selected)
    assertEquals("primary\nsecondary", selected.sourceComments)
