package com.dearlordylord.bend.idea.features.editing

import com.intellij.ide.structureView.TreeBasedStructureViewBuilder
import com.intellij.lang.LanguageStructureViewBuilder
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.*

final class BendStructureTest extends BasePlatformTestCase:
  def testOutlineLabelsAndNavigation(): Unit =
    val file = myFixture.configureByText("outline.bend",
      "def first(x: Nat) -> Nat:\n  x\nlaw claim:\n  {1 == 1 : U32}\ntype Pair is Data:\n  Pair{left: Nat, right: Nat}\ndef last(): 0\n")
    val builder = LanguageStructureViewBuilder.getInstance.getStructureViewBuilder(file)
      .asInstanceOf[TreeBasedStructureViewBuilder]
    val model = builder.createStructureViewModel(myFixture.getEditor)
    try
      val declarations = model.getRoot.getChildren.toList
      assertEquals(4, declarations.size)
      assertTrue(declarations.head.getPresentation.getPresentableText.startsWith("def first"))
      assertTrue(declarations(1).getPresentation.getPresentableText.startsWith("law claim"))
      assertTrue(declarations(1).getPresentation.getPresentableText.length <= 120)
      assertTrue(declarations(2).getPresentation.getPresentableText.startsWith("type Pair"))
      assertTrue(declarations(3).getPresentation.getPresentableText.startsWith("def last"))
      val constructors = declarations(2).getChildren.toList
      assertEquals(1, constructors.size)
      assertTrue(constructors.head.getPresentation.getPresentableText.startsWith("Pair{"))
      val constructor = constructors.head.asInstanceOf[com.intellij.ide.structureView.StructureViewTreeElement]
      assertEquals(file.getText.indexOf("Pair{left"),
        constructor.getValue.asInstanceOf[com.intellij.psi.PsiElement].getTextRange.getStartOffset)
      constructor.navigate(false)
      assertEquals(file.getText.indexOf("Pair{left"), myFixture.getEditor.getCaretModel.getOffset)
    finally model.dispose()

  def testMalformedAndCommentedDeclarationsDoNotHideNeighbors(): Unit =
    val file = myFixture.configureByText("recover.bend",
      "# def fake(): 0\ndef broken(\n  \"law ghost: 0\"\ndef good(): 1\ntype Maybe is Data:\n  Some{value: Nat}\n")
    val builder = LanguageStructureViewBuilder.getInstance.getStructureViewBuilder(file)
      .asInstanceOf[TreeBasedStructureViewBuilder]
    val model = builder.createStructureViewModel(myFixture.getEditor)
    try
      val names = model.getRoot.getChildren.toList.map(_.getPresentation.getPresentableText)
      assertEquals(3, names.size)
      assertTrue(names.head.startsWith("def broken"))
      assertTrue(names(1).startsWith("def good"))
      assertTrue(names(2).startsWith("type Maybe"))
    finally model.dispose()
