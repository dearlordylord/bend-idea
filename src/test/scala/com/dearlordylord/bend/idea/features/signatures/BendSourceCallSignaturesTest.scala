package com.dearlordylord.bend.idea.features.signatures

import com.dearlordylord.bend.idea.symbols.api.{
  BendApplicationKind,
  BendImportedSymbolCatalog,
  BendSourceResolution,
  BendSymbolCategory,
  BendSourceApplications,
  BendSourceCallSignatures
}
import com.dearlordylord.bend.idea.workspace.api.BendLoadingConfiguration
import com.dearlordylord.bend.idea.workspace.api.BendWorkspaceGraph
import com.dearlordylord.bend.idea.workspace.model.BendSourceRecord
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
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.testFramework.utils.parameterInfo.{
  MockCreateParameterInfoContext,
  MockParameterInfoUIContext,
  MockUpdateParameterInfoContext
}
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.ServiceContainerUtil
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

  def testImportedConstructorHintsInsideLawEquality(): Unit =
    myFixture.addFileToProject(
      "battle/types.bend",
      "type D20 is Data:\n  R01{}\ntype MedicineCheck is Data:\n" +
        "  MedicineCheck{die: D20, bonus: Nat, penalty: Nat}\n"
    )
    myFixture.addFileToProject(
      "battle/reducer/help_stabilize.bend",
      "import ../types.bend as T\ndef medicine_succeeds(check: T.MedicineCheck) -> Bool:\n  True{}\n"
    )
    val source = myFixture.addFileToProject(
      "laws/help_stabilize.bend",
      "import ../battle/types.bend as T\n" +
        "import ../battle/reducer/help_stabilize.bend as Help\n" +
        "law medicine_natural_one_can_succeed:\n" +
        "  {Help.medicine_succeeds(T.MedicineCheck{T.R01{},9n,0n}) == True{} : Bool}\n"
    )
    myFixture.configureFromExistingVirtualFile(source.getVirtualFile)
    val reference = PsiTreeUtil
      .findChildrenOfType(myFixture.getFile, classOf[BendReferenceElement])
      .asScala
      .find(_.getText == "T.MedicineCheck")
      .get
    val hints = (new BendParameterNameHintsProvider)
      .getParameterHints(reference, myFixture.getFile)
      .asScala
      .toList
    assertEquals(List("die:", "bonus:", "penalty:"), hints.map(_.getText))
    myFixture.doHighlighting()
    val offsets = myFixture.getEditor.getInlayModel
      .getInlineElementsInRange(0, myFixture.getFile.getTextLength)
      .asScala
      .map(_.getOffset)
      .toSet
    List("T.R01{}", "9n", "0n").foreach { argument =>
      assertTrue(argument, offsets.contains(source.getText.indexOf(argument)))
    }

  def testHintPassRetriesChangedDependencyWithoutPublishingOldFields(): Unit =
    checkHintPassRevisionChange(false)

  def testHintPassStopsAfterTwoChangingCaptures(): Unit =
    checkHintPassRevisionChange(true)

  private def checkHintPassRevisionChange(keepChanging: Boolean): Unit =
    val types = myFixture.addFileToProject(
      "types.bend",
      "type Check is Data:\n  Check{old: Nat}\n"
    )
    val source = myFixture.addFileToProject(
      "main.bend",
      "import ./types.bend as T\ndef main():\n  T.Check{9n}\n"
    )
    myFixture.configureFromExistingVirtualFile(source.getVirtualFile)
    val delegate = getProject.getService(classOf[BendWorkspaceGraph])
    var captures = 0
    val graph = new BendWorkspaceGraph:
      override def load(
          root: BendSourceRecord,
          basePath: String,
          packageCache: String,
          canceled: () => Boolean
      ) =
        val captured = delegate.load(root, basePath, packageCache, canceled)
        captures += 1
        if captures == 1 || keepChanging then
          WriteCommandAction.runWriteCommandAction(
            getProject,
            new Runnable:
              override def run(): Unit =
                val document =
                  PsiDocumentManager.getInstance(getProject).getDocument(types)
                document.setText(
                  s"type Check is Data:\n  Check{field$captures: Nat}\n"
                )
                PsiDocumentManager.getInstance(getProject).commitAllDocuments()
          )
        captured

      override def siblingLaws(proofPath: String) =
        delegate.siblingLaws(proofPath)
    ServiceContainerUtil.replaceService(
      getProject,
      classOf[BendWorkspaceGraph],
      graph,
      getTestRootDisposable
    )
    val reference = PsiTreeUtil
      .findChildrenOfType(myFixture.getFile, classOf[BendReferenceElement])
      .asScala
      .find(_.getText == "T.Check")
      .get
    val hints = (new BendParameterNameHintsProvider)
      .getParameterHints(reference, myFixture.getFile)
      .asScala
      .toList
    assertEquals(2, captures)
    assertEquals(
      if keepChanging then Nil else List("field1:"),
      hints.map(_.getText)
    )

  def testNestedImportedConstructorHintsRefreshAfterDependencyEdit(): Unit =
    val types = myFixture.addFileToProject(
      "battle/types.bend",
      "type Battle is Data:\n  B{roster: Nat, active: Nat, round: Nat}\n" +
        "type Creature is Data:\n  Cr{vitals: Nat, ac: Nat}\n"
    )
    val source = myFixture.addFileToProject(
      "laws/admission.bend",
      "import ../battle/types.bend as T\ndef main():\n" +
        "  T.B{[T.Cr{10n, 12n}], 0n, 1n}\n"
    )
    myFixture.configureFromExistingVirtualFile(source.getVirtualFile)
    val provider = new BendParameterNameHintsProvider
    def labels(name: String): List[String] =
      val reference = PsiTreeUtil
        .findChildrenOfType(myFixture.getFile, classOf[BendReferenceElement])
        .asScala
        .find(_.getText == name)
        .get
      provider
        .getParameterHints(reference, myFixture.getFile)
        .asScala
        .map(_.getText)
        .toList
    assertEquals(List("roster:", "active:", "round:"), labels("T.B"))
    assertEquals(List("vitals:", "ac:"), labels("T.Cr"))
    myFixture.doHighlighting()
    val inlayOffsets = myFixture.getEditor.getInlayModel
      .getInlineElementsInRange(0, myFixture.getFile.getTextLength)
      .asScala
      .map(_.getOffset)
      .toSet
    assertTrue(
      inlayOffsets.contains(myFixture.getFile.getText.indexOf("[T.Cr"))
    )
    assertTrue(inlayOffsets.contains(myFixture.getFile.getText.indexOf("10n")))
    val document = PsiDocumentManager
      .getInstance(getProject)
      .getDocument(types)
    WriteCommandAction.runWriteCommandAction(
      getProject,
      new Runnable:
        override def run(): Unit =
          document.setText(
            "type Battle is Data:\n  B{members: Nat, active: Nat, round: Nat}\n" +
              "type Creature is Data:\n  Cr{vitals: Nat, ac: Nat}\n"
          )
    )
    PsiDocumentManager.getInstance(getProject).commitAllDocuments()
    assertEquals(List("members:", "active:", "round:"), labels("T.B"))

  def testImportedVfsChangeRefreshesConstructorHints(): Unit =
    val types = myFixture.addFileToProject(
      "battle/types.bend",
      "type Battle is Data:\n  B{roster: Nat}\n"
    )
    val source = myFixture.addFileToProject(
      "laws/admission.bend",
      "import ../battle/types.bend as T\ndef main():\n  T.B{1n}\n"
    )
    myFixture.configureFromExistingVirtualFile(source.getVirtualFile)
    val reference = PsiTreeUtil
      .findChildrenOfType(myFixture.getFile, classOf[BendReferenceElement])
      .asScala
      .find(_.getText == "T.B")
      .get
    val provider = new BendParameterNameHintsProvider
    def label: String =
      provider.getParameterHints(reference, myFixture.getFile).get(0).getText
    assertEquals("roster:", label)
    WriteCommandAction.runWriteCommandAction(
      getProject,
      new Runnable:
        override def run(): Unit =
          VfsUtil.saveText(
            types.getVirtualFile,
            "type Battle is Data:\n  B{party: Nat}\n"
          )
    )
    assertEquals("party:", label)

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

  def testEligibleSelectionAgreesAcrossCompletionAndNavigation(): Unit =
    val _ = Files.writeString(
      temporary.resolve("base.bend"),
      "def value(base: Nat):\n  base\ntype Same is Data:\n  Same{field: Nat}\n"
    )
    myFixture.addFileToProject(
      "lib.bend",
      "def value(imported: Nat):\n  imported\n"
    )
    val scenarios = List(
      (
        "import Base\ndef value(local: Nat):\n  local\ndef main():\n  <caret>value(1)\n",
        "value",
        Some(BendSymbolCategory.Definition),
        true
      ),
      (
        "import Base\nimport lib.bend as M\ndef M.value(local: Nat):\n  local\ndef main():\n  <caret>M.value(1)\n",
        "M.value",
        Some(BendSymbolCategory.Definition),
        true
      ),
      (
        "import Base\nimport absent.bend as M\ndef M.value(local: Nat):\n  local\ndef main():\n  <caret>M.value(1)\n",
        "M.value",
        Some(BendSymbolCategory.Definition),
        true
      ),
      (
        "import Base\ndef main():\n  <caret>Same{1}\n",
        "Same",
        Some(BendSymbolCategory.Constructor),
        true
      ),
      (
        "import Base\ndef main():\n  <caret>Same<Nat>\n",
        "Same",
        Some(BendSymbolCategory.Datatype),
        true
      ),
      ("def main(x: Nat):\n  let x = 1\n  <caret>x\n", "x", None, true),
      (
        "def main():\n  <caret>later(1)\ndef later(x: Nat):\n  x\n",
        "later",
        Some(BendSymbolCategory.Definition),
        false
      )
    )
    def identity(result: BendSourceResolution) = result match
      case BendSourceResolution.Resolved(symbol)        => List(symbol.handle)
      case BendSourceResolution.ResolvedBinder(binding) => List(binding.handle)
      case BendSourceResolution.Ambiguous(symbols)      => symbols.map(_.handle)
      case BendSourceResolution.Unresolved              => Nil
    val catalog = getProject.getService(classOf[BendImportedSymbolCatalog])
    val (basePath, packageCache) =
      getProject.getService(classOf[BendLoadingConfiguration]).paths
    scenarios.foreach { case (source, name, category, eligible) =>
      myFixture.configureByText("main.bend", source)
      val file = myFixture.getFile
      val offset = myFixture.getCaretOffset
      val snapshot = catalog.navigationSnapshot(file, basePath, packageCache)
      val (_, current, imported) =
        catalog.visibleWithCurrent(file, offset, basePath, packageCache)
      val (snapshotCurrent, snapshotImported) =
        catalog.visibleWithNavigationSnapshot(snapshot, offset)
      assertEquals(current.map(_.handle), snapshotCurrent.map(_.handle))
      assertEquals(
        imported.map { case (name, symbol) => name -> symbol.handle },
        snapshotImported.map { case (name, symbol) => name -> symbol.handle }
      )
      val direct =
        catalog.resolve(file, offset, name, basePath, packageCache, category)
      val navigation =
        catalog.resolveForNavigation(snapshot, offset, name, category)
      assertEquals(eligible, navigation.eligible)
      if eligible then
        assertFalse(identity(direct).isEmpty)
        assertEquals(identity(direct), identity(navigation.target))
      else
        assertEquals(BendSourceResolution.Unresolved, direct)
        assertFalse(identity(navigation.target).isEmpty)
    }

  def testSingleAndBatchSignaturesAgreeOnLawAliasesAmbiguityAndForwardCalls()
      : Unit =
    myFixture.addFileToProject(
      "lib.bend",
      "law call:\n  for expected: Nat\n  {expected == expected : Nat}\ndef call(actual):\n  actual\ntype Pair is Data:\n  Pair{field: Nat}\n"
    )
    val scenarios = List(
      (
        "import lib.bend as M\ndef main():\n  M.call(1)\n  M.call(2)\n  M.Pair{3}\n",
        List(List("expected"), List("expected"), List("field"))
      ),
      (
        "law call:\n  for expected: Nat\n  {expected == expected : Nat}\ndef call(actual):\n  actual\ndef main():\n  call(1)\n",
        List(List("expected"))
      ),
      (
        "def call(a: Nat):\n  a\ndef call(b: Nat):\n  b\ndef main():\n  call(1)\n",
        List(Nil)
      ),
      ("def main():\n  later(1)\ndef later(x: Nat):\n  x\n", List(Nil)),
      ("def main():\n  missing(1)\n", List(Nil))
    )
    val catalog = getProject.getService(classOf[BendImportedSymbolCatalog])
    val (basePath, packageCache) =
      getProject.getService(classOf[BendLoadingConfiguration]).paths
    scenarios.foreach { case (source, expected) =>
      myFixture.configureByText("main.bend", source)
      val file = myFixture.getFile
      val applications = BendSourceApplications
        .forCallees(file)
        .values
        .filter(application =>
          source.substring(
            source.lastIndexOf('\n', application.calleeFrom - 1) + 1,
            application.calleeFrom
          ) == "  "
        )
        .toList
        .sortBy(_.calleeFrom)
      val snapshot = catalog.navigationSnapshot(file, basePath, packageCache)
      val batch =
        BendSourceCallSignatures.hintsForCallees(snapshot, applications)
      val single = applications.map(application =>
        BendSourceCallSignatures.hints(file, application)
      )
      assertEquals(expected, single.map(_.map(_._1)))
      assertEquals(
        single,
        applications.map(application => batch(application.calleeFrom))
      )
    }
