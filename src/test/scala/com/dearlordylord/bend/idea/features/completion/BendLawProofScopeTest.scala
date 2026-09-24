package com.dearlordylord.bend.idea.features.completion

import com.dearlordylord.bend.idea.symbols.api.{
  BendSourceSymbols,
  BendSymbolCategory,
  BendSourceResolution
}
import com.dearlordylord.bend.idea.syntax.psi.BendProofForm
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.*

final class BendLawProofScopeTest extends BasePlatformTestCase:
  private def bindings(source: String): Set[String] =
    myFixture.configureByText("proof.bend", source)
    BendSourceSymbols
      .visibleBindings(
        myFixture.getFile,
        myFixture.getEditor.getCaretModel.getOffset
      )
      .map(_.name)
      .toSet

  private def candidates(source: String): Array[LookupElement] =
    myFixture.configureByText("proof.bend", source)
    Option(myFixture.completeBasic()).getOrElse(Array.empty[LookupElement])

  def testLawFillKeepsBothSourceSitesAndOneCompletion(): Unit =
    val source =
      "law claim:\n  for -n: Nat\n  {n == n : Nat}\ndef claim(n):\n  cla<caret>\n"
    val items = candidates(source).filter(_.getLookupString == "claim")
    val offset = myFixture.getEditor.getCaretModel.getOffset
    assertEquals(
      1,
      BendSourceSymbols
        .visibleCandidates(myFixture.getFile, offset)
        .count(_.name == "claim")
    )
    assertTrue(
      items.length == 1 || myFixture.getEditor.getDocument.getText
        .substring(0, offset)
        .endsWith("claim")
    )
    val sites = BendSourceSymbols
      .declarations(myFixture.getFile)
      .filter(_.name == "claim")
    assertEquals(
      List(BendSymbolCategory.Law, BendSymbolCategory.Definition),
      sites.map(_.category)
    )
    assertNotEquals(sites.head.handle, sites.last.handle)
    val link = BendSourceSymbols.logicalLaws(myFixture.getFile).head
    assertEquals(sites.head.handle, link.law.handle)
    assertEquals(List(sites.last.handle), link.fills.map(_.handle))
    assertFalse(link.isOpen)
    if items.nonEmpty then
      assertTrue(
        items.head.getObject
          .isInstanceOf[com.dearlordylord.bend.idea.syntax.psi.BendLaw]
      )
    assertEquals(
      BendSourceResolution.Resolved(sites.last),
      BendSourceSymbols.resolveCurrentFile(
        myFixture.getFile,
        offset,
        "claim",
        Some(BendSymbolCategory.Definition)
      )
    )

  def testOpenLawStaysSourceOnly(): Unit =
    val _ = candidates("law pending:\n  Type\ndef main():\n  pen<caret>")
    val link = BendSourceSymbols.logicalLaws(myFixture.getFile).head
    assertTrue(link.isOpen)
    assertTrue(link.fills.isEmpty)
    assertEquals("law pending:\n  Type", link.law.signature.source)

  def testLawClauseDomainsWhereAndOrdering(): Unit =
    assertFalse(
      bindings(
        "law proof:\n  for n: Nat\n  for m: <caret>Nat\n  {n == m : Nat}"
      ).contains("m")
    )
    assertTrue(
      bindings(
        "law proof:\n  for n: Nat\n  for m: <caret>Nat\n  {n == m : Nat}"
      ).contains("n")
    )
    assertEquals(
      Set("n", "m"),
      bindings(
        "law proof:\n  for n: Nat\n  exs m: Nat\n  {n == <caret>m : Nat}"
      )
    )
    assertTrue(
      bindings("law proof:\n  for n: Nat where {n == n : Nat}\n  <caret>Type")
        .contains("n")
    )
    assertFalse(
      bindings("law proof:\n  for n: <caret>Nat where {n == n : Nat}\n  Type")
        .contains("n")
    )
    assertTrue(
      bindings("law proof:\n  for n: Nat where {<caret>n == n : Nat}\n  Type")
        .contains("n")
    )
    assertEquals(
      Set("n", "m"),
      bindings("law proof: for n: Nat for m: Nat\n  {n == <caret>m : Nat}")
    )

  def testDependentArrowAndMotiveBoundaries(): Unit =
    assertTrue(
      bindings("law proof:\n  @x: Nat -> {<caret>x == x : Nat}").contains("x")
    )
    assertTrue(
      bindings("law proof:\n  @-x: Nat -> {<caret>x == x : Nat}").contains("x")
    )
    assertTrue(
      bindings(
        "law proof:\n  for f: @x: Nat -> {<caret>x == x : Nat}\n  for y: Nat\n  Type"
      ).contains("x")
    )
    assertFalse(
      bindings(
        "law proof:\n  for f: @x: Nat -> Nat\n  for y: <caret>Nat\n  Type"
      ).contains("x")
    )
    assertFalse(
      bindings(
        "law proof:\n  for f: @x: Nat -> Nat where {<caret>x == x : Nat}\n  Type"
      ).contains("x")
    )
    assertFalse(
      bindings("law proof:\n  @x: <caret>Nat -> {x == x : Nat}").contains("x")
    )
    assertTrue(
      bindings("law proof:\n  &x: Nat -> {<caret>x == x : Nat}").contains("x")
    )
    assertFalse(bindings("law proof:\n  @_: Nat -> <caret>Nat").contains("_"))
    assertTrue(
      bindings("def f(g: @x: Nat -> {<caret>x == x : Nat}) -> Nat:\n  0")
        .contains("x")
    )
    assertFalse(
      bindings("def f(g: @x: Nat -> Nat) -> {<caret>x == x : Nat}:\n  0")
        .contains("x")
    )
    assertFalse(
      bindings("def f(g: @x: Nat -> Nat) -> Nat:\n  <caret>0").contains("x")
    )
    assertEquals(
      Set("n", "e", "_"),
      bindings(
        "law proof:\n  for n: Nat\n  {n == n : Nat}\ndef proof(n):\n  %e@n : {_<caret> == n : Nat}; {==}"
      )
    )
    assertFalse(
      bindings(
        "law proof:\n  for n: Nat\n  {n == n : Nat}\ndef proof(n):\n  %e@n : {_ == n : Nat}; <caret>{==}"
      ).contains("e")
    )

  def testNamedAndTodoHolesAreNotDeclarations(): Unit =
    val _ = candidates(
      "law proof:\n  Type\ndef proof():\n  ?named\n  ?TODO\ndef main():\n  pro<caret>"
    )
    val names = BendSourceSymbols.declarations(myFixture.getFile).map(_.name)
    assertEquals(List("proof", "proof", "main"), names)
    assertFalse(names.contains("named"))
    assertFalse(names.contains("TODO"))

  def testBareFillParameterUsesLawTypeAndKeepsFillLocator(): Unit =
    myFixture.configureByText(
      "proof.bend",
      "law claim:\n  for -n: Nat\n  {n == n : Nat}\ndef claim(n):\n  n<caret>\n"
    )
    val bindings = BendSourceSymbols.visibleBindings(
      myFixture.getFile,
      myFixture.getEditor.getCaretModel.getOffset
    )
    val n = bindings.find(_.name == "n").get
    assertEquals(Some("Nat"), n.sourceSpecification)
    assertEquals(
      Some(BendSourceSymbols.logicalLaws(myFixture.getFile).head.law.handle),
      n.specification
    )
    assertEquals(
      myFixture.getFile.getText.indexOf("def claim(n)") + "def claim(".length,
      n.handle.nameOffset
    )

  def testWhereClauseRemainsInBareFillSpecification(): Unit =
    val items = candidates(
      "law my_thesis:\n  for x: Nat where IsEven(x)\n  Type\ndef my_thesis(x):\n  <caret>?TODO"
    )
    val x = BendSourceSymbols
      .visibleBindings(
        myFixture.getFile,
        myFixture.getEditor.getCaretModel.getOffset
      )
      .find(_.name == "x")
      .get
    assertEquals(Some("Nat where IsEven(x)"), x.sourceSpecification)
    assertEquals(
      Some(BendSourceSymbols.logicalLaws(myFixture.getFile).head.law.handle),
      x.specification
    )
    val item = items.find(_.getLookupString == "x").get
    val presentation = new LookupElementPresentation()
    item.renderElement(presentation)
    assertTrue(presentation.getTailText.contains("Nat where IsEven(x) (law)"))

  def testTypedDefinitionAfterLawIsNotItsFill(): Unit =
    val _ = candidates(
      "law claim:\n  Type\ndef claim(f: Nat -> Nat) -> IO(Unit):\n  ?TODO\ndef main():\n  cla<caret>"
    )
    assertTrue(BendSourceSymbols.logicalLaws(myFixture.getFile).head.isOpen)
    assertEquals(
      2,
      BendSourceSymbols
        .visibleCandidates(
          myFixture.getFile,
          myFixture.getEditor.getCaretModel.getOffset
        )
        .count(_.name == "claim")
    )
    val _ = candidates(
      "law claim:\n  Type\ndef claim(f: Nat -> Nat):\n  ?TODO\ndef main():\n  cla<caret>"
    )
    assertTrue(BendSourceSymbols.logicalLaws(myFixture.getFile).head.isOpen)

  def testProofFormsRemainDistinctThroughIncompleteEdit(): Unit =
    val _ = candidates(
      "law claim:\n  {0n == 0n : Nat}\ndef claim():\n  %e@{0n == 0n : Nat} : {_ == 0n : Nat};\n  (1 + 2 : U32)\n  ?named\n  ?TODO\n  ?\n  {==}\n  %incomplete : {"
    )
    val forms = BendSourceSymbols
      .declarations(myFixture.getFile)
      .last
      .declaration
      .proofForms
    assertEquals(
      Set("named", "TODO", ""),
      forms.collect { case h: BendProofForm.Hole => h.name }.toSet
    )
    assertTrue(forms.exists(_.isInstanceOf[BendProofForm.Rewrite]))
    assertTrue(forms.exists(_.isInstanceOf[BendProofForm.Equality]))
    assertTrue(forms.exists(_.isInstanceOf[BendProofForm.OperatorAnnotation]))
    assertTrue(forms.exists(_.isInstanceOf[BendProofForm.Reflexivity]))
    assertTrue(forms.exists {
      case r: BendProofForm.Rewrite => r.motive.nonEmpty; case _ => false
    })
