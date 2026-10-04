package com.dearlordylord.bend.idea.features.semantics

import com.dearlordylord.bend.idea.adapters.cli.RealBendCompilerFixture
import com.dearlordylord.bend.idea.analysis.api.BendCheckService
import com.dearlordylord.bend.idea.model.FileId
import com.dearlordylord.bend.idea.test.VfsTestRoots
import com.dearlordylord.bend.idea.toolchain.api.{
  BendToolchainChoices,
  BendToolchainSettings
}
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.{Files, Path}
import org.junit.Assert.*
import com.intellij.util.ui.UIUtil
import com.intellij.openapi.editor.EditorFactory
import com.dearlordylord.bend.idea.analysis.model.BendReliance

final class BendNormalizationInlayTest extends BasePlatformTestCase:
  private var original: BendToolchainChoices = null
  private var directory: Path = null

  override def setUp(): Unit =
    super.setUp()
    VfsTestRoots.allowSystemTemporaryDirectory(getTestRootDisposable)
    val settings = ApplicationManager.getApplication.getService(
      classOf[BendToolchainSettings]
    )
    original = settings.choices
    directory = Files.createTempDirectory("bend-normal-editor-")
    val compiler = RealBendCompilerFixture.inputs
    val _ = compiler.writeStructuredLauncher(directory.resolve("bend"))
    val selectedBase = directory.resolve("base.bend")
    Files.copy(compiler.base, selectedBase)
    settings.update(
      BendToolchainChoices(
        executable = directory.resolve("bend").toString,
        baseSource = selectedBase.toString,
        diagnosticsEnabled = false
      )
    )

  override def tearDown(): Unit =
    try
      ApplicationManager.getApplication
        .getService(classOf[BendToolchainSettings])
        .update(original)
      if directory != null then
        val files = Files.walk(directory)
        try
          files
            .sorted(java.util.Comparator.reverseOrder())
            .forEach(p => {
              val _ = Files.deleteIfExists(p)
            })
        finally files.close()
    finally super.tearDown()

  private def id: FileId =
    val file = myFixture.getFile.getVirtualFile
    new FileId(
      Option(file.getCanonicalPath).getOrElse(file.getPath),
      file.getCanonicalPath != null
    )

  private val source =
    "import Base\ndef id(A: Type, x: A) -> A:\n  x\ndef use() -> U32:\n  id(U32, 7)\n"

  private def prepare(): Unit =
    myFixture.configureByText("pin.bend", source)
    myFixture.getEditor.getCaretModel.moveToOffset(
      source.lastIndexOf("id(U32, 7)") + "id(U32, 7".length
    )

  private def inlays =
    myFixture.getEditor.getInlayModel.getInlineElementsInRange(
      0,
      myFixture.getEditor.getDocument.getTextLength,
      classOf[BendNormalizedValueRenderer]
    )

  private def awaitPin(): Unit =
    val deadline = System.nanoTime() + 20_000_000_000L
    while inlays.isEmpty && System.nanoTime() < deadline do
      UIUtil.dispatchAllInvocationEvents()
      Thread.sleep(25)
    assertEquals("expected one compiler-backed inlay", 1, inlays.size())

  def testPinAndUnpinPreserveSourceAndSurviveCaretMovement(): Unit =
    prepare()
    myFixture.performEditorAction("Bend.PinNormalizedValue")
    awaitPin()
    val pin = inlays.get(0)
    val normal = getProject
      .getService(classOf[BendCheckService])
      .result(id)
      .flatMap(_.normalization)
      .get
    assertEquals(normal.range.end, pin.getOffset)
    assertTrue(pin.getRenderer.label.contains("Bend ⇒ 7"))
    assertTrue(pin.getWidthInPixels > 0)
    myFixture.getEditor.getCaretModel.moveToOffset(0)
    assertTrue(pin.isValid)
    myFixture.getEditor.getCaretModel.moveToOffset(normal.range.end - 1)
    myFixture.performEditorAction("Bend.PinNormalizedValue")
    assertFalse(pin.isValid)
    assertTrue(inlays.isEmpty)
    assertEquals(source, myFixture.getEditor.getDocument.getText)

  def testDocumentEditRemovesPinImmediately(): Unit =
    prepare()
    myFixture.performEditorAction("Bend.PinNormalizedValue")
    awaitPin()
    val pin = inlays.get(0)
    WriteCommandAction.runWriteCommandAction(
      getProject,
      new Runnable:
        override def run(): Unit =
          myFixture.getEditor.getDocument.insertString(0, "# changed\n")
    )
    assertFalse(pin.isValid)
    assertTrue(inlays.isEmpty)

  def testConfigurationChangeRemovesPin(): Unit =
    prepare()
    myFixture.performEditorAction("Bend.PinNormalizedValue")
    awaitPin()
    val pin = inlays.get(0)
    getProject.getService(classOf[BendCheckService]).configurationChanged()
    UIUtil.dispatchAllInvocationEvents()
    assertFalse(pin.isValid)

  def testEditWhileNormalizingCannotPublishAnInlay(): Unit =
    val marker = directory.resolve("normalize-started")
    val _ = RealBendCompilerFixture.inputs.writeStructuredLauncher(
      directory.resolve("bend"),
      normalizationPrelude = s"  printf x > \"${marker.toString}\"\n  sleep 6"
    )
    prepare()
    myFixture.performEditorAction("Bend.PinNormalizedValue")
    val deadline = System.nanoTime() + 20_000_000_000L
    while !Files.exists(marker) && System.nanoTime() < deadline do
      UIUtil.dispatchAllInvocationEvents()
      Thread.sleep(25)
    assertTrue("normalization worker did not start", Files.exists(marker))
    WriteCommandAction.runWriteCommandAction(
      getProject,
      new Runnable:
        override def run(): Unit =
          myFixture.getEditor.getDocument.insertString(0, "# changed\n")
    )
    val checks = getProject.getService(classOf[BendCheckService])
    while checks.busy && System.nanoTime() < deadline do
      UIUtil.dispatchAllInvocationEvents()
      Thread.sleep(25)
    UIUtil.dispatchAllInvocationEvents()
    assertFalse(checks.busy)
    assertTrue(inlays.isEmpty)

  private def secondaryPins(editor: com.intellij.openapi.editor.Editor) =
    editor.getInlayModel.getInlineElementsInRange(
      0,
      editor.getDocument.getTextLength,
      classOf[BendNormalizedValueRenderer]
    )

  def testReleasedEditorDisposesItsPin(): Unit =
    prepare()
    val editor = EditorFactory
      .getInstance()
      .createEditor(
        myFixture.getEditor.getDocument,
        getProject
      )
    var released = false
    try
      editor.getCaretModel.moveToOffset(
        myFixture.getEditor.getCaretModel.getOffset
      )
      getProject
        .getService(classOf[BendNormalizationInlays])
        .toggle(editor, myFixture.getFile.getVirtualFile)
      val deadline = System.nanoTime() + 20_000_000_000L
      while secondaryPins(editor).isEmpty && System.nanoTime() < deadline do
        UIUtil.dispatchAllInvocationEvents()
        Thread.sleep(25)
      assertEquals(1, secondaryPins(editor).size())
      val pin = secondaryPins(editor).get(0)
      EditorFactory.getInstance().releaseEditor(editor)
      released = true
      assertFalse(pin.isValid)
    finally if !released then EditorFactory.getInstance().releaseEditor(editor)

  private val noRemoval: () => Unit = () => ()

  def testLabelsPreserveRelianceAndBoundUnicodeDisplay(): Unit =
    assertTrue(
      new BendNormalizedValueRenderer(
        "7",
        BendReliance.UnsafeOrForeign,
        noRemoval
      ).label.contains("unsafe/foreign")
    )
    assertTrue(
      new BendNormalizedValueRenderer(
        "7",
        BendReliance.Unknown,
        noRemoval
      ).label
        .contains("reliance unknown")
    )
    val label = new BendNormalizedValueRenderer(
      "😀" * 200,
      BendReliance.None,
      noRemoval
    ).label
    assertTrue(label.contains("…"))
    assertFalse(label.contains("\uFFFD"))
