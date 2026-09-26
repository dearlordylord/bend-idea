package com.dearlordylord.bend.idea.features.signatures

import com.dearlordylord.bend.idea.symbols.api.{
  BendApplicationKind,
  BendSourceApplications,
  BendSourceCallSignatures
}
import com.dearlordylord.bend.idea.syntax.BendLanguage
import com.dearlordylord.bend.idea.syntax.psi.BendReferenceElement
import com.dearlordylord.bend.idea.toolchain.api.{
  BendToolchainChoices,
  BendToolchainSettings
}
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.codeInsight.hints.InlayHintsSettings
import com.intellij.codeInsight.hint.ShowParameterInfoHandler
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.utils.parameterInfo.{
  MockCreateParameterInfoContext,
  MockParameterInfoUIContext,
  MockUpdateParameterInfoContext
}
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.*
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

final class BendSourceCallSignaturesTest extends BasePlatformTestCase:
  private var original: BendToolchainChoices = null
  private var temporary: Path = null

  override def setUp(): Unit =
    super.setUp()
    val settings = ApplicationManager.getApplication.getService(
      classOf[BendToolchainSettings]
    )
    original = settings.choices
    temporary = Files.createTempDirectory("bend-signatures")
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

  private def signature(source: String) =
    myFixture.configureByText("main.bend", source)
    val application = BendSourceApplications
      .at(myFixture.getFile, myFixture.getCaretOffset)
      .getOrElse(
        fail("Expected a source application at the caret").asInstanceOf[Nothing]
      )
    val result = BendSourceCallSignatures
      .resolve(myFixture.getFile, application)
      .getOrElse(
        fail("Expected a unique source signature").asInstanceOf[Nothing]
      )
    (application, result)

  def testNestedPartialApplicationTracksInnermostActiveArgument(): Unit =
    val (application, info) = signature(
      "def inner(left: Nat, right: Nat) -> Nat:\n  left\ndef outer(a: Nat, b: Nat):\n  a\ndef main():\n  outer(1, inner(2, <caret>))\n"
    )
    assertEquals(BendApplicationKind.Function, application.kind)
    assertEquals("inner", application.callee)
    assertEquals(1, info.activeParameter)
    assertEquals("inner(left: Nat, right: Nat)", info.text)
    assertEquals(
      "right: Nat",
      info.text.substring(
        info.parameterRanges(1)._1,
        info.parameterRanges(1)._2
      )
    )

  def testConstructorFieldsAndDatatypeQuantityArgumentsAlign(): Unit =
    val (constructor, constructorInfo) = signature(
      "type Pair<a, -A: Kind(a)> is Kind(a):\n  Pair{left: A, +right: A}\ndef main():\n  Pair{left: 1, right: <caret>2}\n"
    )
    assertEquals(BendApplicationKind.Constructor, constructor.kind)
    assertEquals(1, constructorInfo.activeParameter)
    assertTrue(constructorInfo.text.contains("+right: A"))

    val (datatype, datatypeInfo) = signature(
      "type Pair<a, -A: Kind(a)> is Kind(a):\n  Pair{left: A}\ndef main(value: Pair<N<caret>at>) -> Pair<Nat>:\n  value\n"
    )
    assertEquals(BendApplicationKind.Datatype, datatype.kind)
    assertEquals("Pair<-A: Kind(a)>", datatypeInfo.text)
    assertEquals(0, datatypeInfo.activeParameter)
    assertFalse(datatypeInfo.text.contains("<a,"))

    val (_, explicit) = signature(
      "type Pair<a, -A: Kind(a)> is Kind(a):\n  Pair{left: A}\ndef main(value: Pair<&2, N<caret>at>) -> Pair<&2, Nat>:\n  value\n"
    )
    assertTrue(explicit.text.startsWith("Pair<a, -A"))
    assertEquals(1, explicit.activeParameter)

  def testDatatypeApplicationsInBodiesIncludeIncompleteAngles(): Unit =
    val (_, complete) = signature(
      "type Pair<a, -A: Kind(a)> is Kind(a):\n  Pair{left: A}\ndef main():\n  Pair<N<caret>at>\n"
    )
    assertEquals("Pair<-A: Kind(a)>", complete.text)
    assertEquals(0, complete.activeParameter)

    val (_, incomplete) = signature(
      "type Pair<a, -A: Kind(a)> is Kind(a):\n  Pair{left: A}\ndef main():\n  Pair<N<caret>at\n"
    )
    assertEquals("Pair<-A: Kind(a)>", incomplete.text)
    assertFalse(incomplete.application.complete)
    assertEquals(0, incomplete.activeParameter)

  def testIncompleteApplicationsStopAtNextDeclaration(): Unit =
    val source =
      "def first(value):\n  f(1,\ndef second(value):\n  next(2)\n"
    myFixture.configureByText("main.bend", source)
    val file = myFixture.getFile
    val nextOffset = source.indexOf("next(2)") + "next(".length
    val current = BendSourceApplications.at(file, nextOffset).get
    assertEquals("next", current.callee)
    val earlier = BendSourceApplications
      .forCallee(file, source.indexOf("f("))
      .get
    assertFalse(earlier.complete)
    assertEquals(List("1"), earlier.arguments.map(_.source))

  def testLawBackedDefinitionUsesLawTelescope(): Unit =
    val (_, info) = signature(
      "law claim:\n  for -n: Nat\n  for value: Nat\n  {value == value : Nat}\ndef claim(left, right):\n  left\ndef main():\n  claim(1, <caret>2)\n"
    )
    assertEquals("claim(-n: Nat, value: Nat)", info.text)
    assertEquals(1, info.activeParameter)

  def testTemplateAndErasedParametersRemainInSourceSignature(): Unit =
    val (_, info) = signature(
      "def apply(~mapper: Nat -> Nat, -proof: Nat, value: Nat) -> Nat:\n  value\ndef main():\n  apply(~id, proof, <caret>1)\n"
    )
    assertEquals(
      "apply(~mapper: Nat -> Nat, -proof: Nat, value: Nat)",
      info.text
    )
    assertEquals(2, info.activeParameter)

  def testHintsUseCurrentUniqueSourceSignatureAndSkipObviousNames(): Unit =
    myFixture.configureByText(
      "main.bend",
      "def combine(left: Nat, scale: Nat):\n  left\ndef main():\n  combine(1, 2)\n"
    )
    val provider = new BendParameterNameHintsProvider
    def hints =
      val reference = PsiTreeUtil
        .findChildrenOfType(myFixture.getFile, classOf[BendReferenceElement])
        .asScala
        .find(_.getText == "combine")
        .get
      provider.getParameterHints(reference, myFixture.getFile).asScala.toList
    assertEquals(List("left:", "scale:"), hints.map(_.getText))
    assertEquals(
      List(
        myFixture.getFile.getText.indexOf("1, 2"),
        myFixture.getFile.getText.indexOf("2)")
      ),
      hints.map(_.getOffset)
    )
    assertEquals("Show Bend parameter names", provider.getMainCheckboxText)

    myFixture.configureByText(
      "main.bend",
      "def inner(first: Nat, second: Nat):\n  first\ndef outer(left: Nat, right: Nat):\n  left\ndef main():\n  outer(1, inner(2, 3))\n"
    )
    val references = PsiTreeUtil
      .findChildrenOfType(myFixture.getFile, classOf[BendReferenceElement])
      .asScala
    val outer = references.find(_.getText == "outer").get
    val inner = references.find(_.getText == "inner").get
    val outerHints =
      provider.getParameterHints(outer, myFixture.getFile).asScala.toList
    val innerHints =
      provider.getParameterHints(inner, myFixture.getFile).asScala.toList
    assertEquals(List("left:", "right:"), outerHints.map(_.getText))
    assertEquals(List("first:", "second:"), innerHints.map(_.getText))
    assertEquals(
      List(
        myFixture.getFile.getText.indexOf("1, inner"),
        myFixture.getFile.getText.indexOf("inner(2")
      ),
      outerHints.map(_.getOffset)
    )

    myFixture.configureByText(
      "main.bend",
      "def identity(value: Nat):\n  value\ndef main(value: Nat):\n  identity(value)\n"
    )
    val reference = PsiTreeUtil
      .findChildrenOfType(myFixture.getFile, classOf[BendReferenceElement])
      .asScala
      .find(_.getText == "identity")
      .get
    assertTrue(provider.getParameterHints(reference, myFixture.getFile).isEmpty)

    val constructorSource =
      "type Pair<a, -A: Kind(a)> is Kind(a):\n  Pair{left: A, right: A}\ndef main():\n  Pair{1, 2}\n"
    myFixture.configureByText("main.bend", constructorSource)
    val constructor = PsiTreeUtil
      .findChildrenOfType(myFixture.getFile, classOf[BendReferenceElement])
      .asScala
      .find(_.getText == "Pair")
      .get
    val constructorHints = provider
      .getParameterHints(constructor, myFixture.getFile)
      .asScala
      .toList
    assertEquals(List("left:", "right:"), constructorHints.map(_.getText))
    assertEquals(
      List(
        constructorSource.indexOf("1, 2"),
        constructorSource.indexOf("2}")
      ),
      constructorHints.map(_.getOffset)
    )

    myFixture.configureByText(
      "main.bend",
      "type Pair is Data:\n  Pair{left: Nat, right: Nat}\ndef main():\n  Pair{left: 1, right: 2}\n"
    )
    val explicitlyLabeledConstructor = PsiTreeUtil
      .findChildrenOfType(myFixture.getFile, classOf[BendReferenceElement])
      .asScala
      .find(_.getText == "Pair")
      .get
    assertTrue(
      provider
        .getParameterHints(explicitlyLabeledConstructor, myFixture.getFile)
        .isEmpty
    )

  def testUnsavedSignatureChangeIsReadWithoutAStaleHintCache(): Unit =
    myFixture.configureByText(
      "main.bend",
      "def call(old: Nat):\n  old\ndef main():\n  call(1)\n"
    )
    val reference = PsiTreeUtil
      .findChildrenOfType(myFixture.getFile, classOf[BendReferenceElement])
      .asScala
      .find(_.getText == "call")
      .get
    val provider = new BendParameterNameHintsProvider
    assertEquals(
      "old:",
      provider.getParameterHints(reference, myFixture.getFile).get(0).getText
    )
    WriteCommandAction.runWriteCommandAction(
      getProject,
      new Runnable:
        override def run(): Unit =
          val document = myFixture.getEditor.getDocument
          document.setText(
            "def call(current: Nat):\n  current\ndef main():\n  call(1)\n"
          )
    )
    PsiDocumentManager.getInstance(getProject).commitAllDocuments()
    val updated = PsiTreeUtil
      .findChildrenOfType(myFixture.getFile, classOf[BendReferenceElement])
      .asScala
      .find(_.getText == "call")
      .get
    assertEquals(
      "current:",
      provider.getParameterHints(updated, myFixture.getFile).get(0).getText
    )

  def testParameterNameHintsRespectTheStandardLanguageToggle(): Unit =
    val settings = InlayHintsSettings.instance()
    val originalGlobal = settings.hintsEnabledGlobally()
    val originalLanguage = settings.hintsEnabled(BendLanguage.instance)
    try
      settings.setEnabledGlobally(true)
      settings.setHintsEnabledForLanguage(BendLanguage.instance, false)
      assertFalse(settings.hintsEnabled(BendLanguage.instance))
      settings.setHintsEnabledForLanguage(BendLanguage.instance, true)
      assertTrue(settings.hintsEnabled(BendLanguage.instance))
      assertEquals(
        "Show Bend parameter names",
        new BendParameterNameHintsProvider().getMainCheckboxText
      )
    finally
      settings.setHintsEnabledForLanguage(
        BendLanguage.instance,
        originalLanguage
      )
      settings.setEnabledGlobally(originalGlobal)

  def testParameterInfoPopupRefreshesAfterAnUnsavedSignatureEdit(): Unit =
    val before =
      "def call(first: Nat, second: Nat):\n  first\ndef main():\n  call(1, <caret>2)\n"
    myFixture.configureByText("main.bend", before)
    val handler = new BendParameterInfoHandler
    assertTrue(
      ShowParameterInfoHandler
        .getHandlers(getProject, BendLanguage.instance)
        .exists(_.isInstanceOf[BendParameterInfoHandler])
    )
    val create = new MockCreateParameterInfoContext(
      myFixture.getEditor,
      myFixture.getFile
    )
    val owner = handler.findElementForParameterInfo(create)
    assertNotNull(owner)
    handler.showParameterInfo(owner, create)
    val items = create.getItemsToShow
    assertEquals(1, items.length)
    assertEquals(
      "call(first: Nat, second: Nat)",
      items(0)
        .asInstanceOf[
          com.dearlordylord.bend.idea.symbols.api.BendRenderedCallSignature
        ]
        .text
    )

    val update = new MockUpdateParameterInfoContext(
      myFixture.getEditor,
      myFixture.getFile,
      items
    )
    handler.updateParameterInfo(owner, update)
    assertEquals(1, update.getCurrentParameter)

    val after =
      "def call(left: Nat, right: Nat, extra: Nat):\n  left\ndef main():\n  call(1, 2, extra)\n"
    WriteCommandAction.runWriteCommandAction(
      getProject,
      new Runnable:
        override def run(): Unit =
          val document = myFixture.getEditor.getDocument
          document.setText(after)
          myFixture.getEditor.getCaretModel.moveToOffset(
            after.indexOf("extra)") + "extra".length
          )
    )
    PsiDocumentManager.getInstance(getProject).commitAllDocuments()
    val newOwner = handler.findElementForUpdatingParameterInfo(update)
    assertNotNull(newOwner)
    handler.updateParameterInfo(newOwner, update)
    assertEquals(2, update.getCurrentParameter)
    val signature = items(0).asInstanceOf[
      com.dearlordylord.bend.idea.symbols.api.BendRenderedCallSignature
    ]
    assertEquals("call(left: Nat, right: Nat, extra: Nat)", signature.text)

    val ui = new MockParameterInfoUIContext(newOwner)
    ui.setCurrentParameterIndex(update.getCurrentParameter)
    handler.updateUI(signature, ui)
    assertEquals(signature.text, ui.getText)
    assertEquals(
      "extra: Nat",
      signature.text.substring(ui.getHighlightStart, ui.getHighlightEnd)
    )
