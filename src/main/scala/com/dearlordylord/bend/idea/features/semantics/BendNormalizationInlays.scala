package com.dearlordylord.bend.idea.features.semantics

import com.dearlordylord.bend.idea.analysis.api.BendCheckService
import com.dearlordylord.bend.idea.analysis.model.{
  BendCheckResult,
  BendNormalization,
  BendReliance
}
import com.dearlordylord.bend.idea.features.semantics.api.{
  BendCurrentLocationInquiry,
  BendInquiryLocation,
  BendInquiryOutcome
}
import com.intellij.codeInsight.hint.HintManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.{
  ActionGroup,
  AnAction,
  AnActionEvent,
  DefaultActionGroup
}
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.{
  Editor,
  EditorCustomElementRenderer,
  EditorFactory,
  Inlay
}
import com.intellij.openapi.editor.colors.{EditorColors, EditorFontType}
import com.intellij.openapi.editor.event.{
  DocumentEvent,
  DocumentListener,
  EditorFactoryEvent,
  EditorFactoryListener
}
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import java.awt.Graphics2D
import java.awt.geom.Rectangle2D
import scala.collection.mutable

/** Session-only presentation of an explicitly requested, current compiler fact.
  * Each editor owns at most one pin; compiler work stays behind the inquiry
  * API.
  */
final class BendNormalizationInlays(project: Project) extends Disposable:
  private final case class Pin(
      result: BendCheckResult,
      normal: BendNormalization,
      inlay: Inlay[BendNormalizedValueRenderer],
      lifetime: Disposable
  )
  private val checks = project.getService(classOf[BendCheckService])
  private val pins = mutable.Map.empty[Editor, Pin]
  private var disposed = false
  private val unsubscribe = checks.addStatusListener { root =>
    onEdt {
      pins.toList.foreach { case (editor, pin) =>
        if pin.result.key.root == root && !checks.isCurrent(pin.result) then
          remove(editor)
      }
    }
  }
  EditorFactory
    .getInstance()
    .addEditorFactoryListener(
      new EditorFactoryListener:
        override def editorReleased(event: EditorFactoryEvent): Unit =
          remove(event.getEditor)
      ,
      this
    )

  def pinnedAtCaret(editor: Editor): Boolean =
    pins
      .get(editor)
      .exists(pin =>
        pin.inlay.isValid && checks.isCurrent(pin.result) &&
          pin.normal.range.start <= editor.getCaretModel.getOffset &&
          editor.getCaretModel.getOffset <= pin.normal.range.end
      )

  def toggle(editor: Editor, file: VirtualFile): Unit =
    if pinnedAtCaret(editor) then remove(editor)
    else
      project
        .getService(classOf[BendCurrentLocationInquiry])
        .normalization(editor, file, "Pinning normalized Bend value") {
          case BendInquiryOutcome.Available(result, location, normal) =>
            pin(editor, result, location, normal)
          case BendInquiryOutcome.Unavailable(reason) =>
            HintManager.getInstance().showInformationHint(editor, reason)
          case BendInquiryOutcome.AmbiguousRoots =>
            HintManager
              .getInstance()
              .showInformationHint(
                editor,
                "Select one Bend root before pinning a value"
              )
        }

  private def pin(
      editor: Editor,
      result: BendCheckResult,
      location: BendInquiryLocation,
      normal: BendNormalization
  ): Unit =
    if disposed || project.isDisposed || editor.isDisposed ||
      !project
        .getService(classOf[BendCurrentLocationInquiry])
        .isCurrent(editor, result, location)
    then return
    remove(editor)
    val renderer = new BendNormalizedValueRenderer(
      normal.text,
      result.reliance,
      () => remove(editor)
    )
    Option(
      editor.getInlayModel.addInlineElement(normal.range.end, true, renderer)
    ).foreach { inlay =>
      val lifetime = Disposer.newDisposable("Bend normalized value pin")
      Disposer.register(this, lifetime)
      Disposer.register(lifetime, inlay)
      pins(editor) = Pin(result, normal, inlay, lifetime)
      editor.getDocument.addDocumentListener(
        new DocumentListener:
          override def beforeDocumentChange(event: DocumentEvent): Unit =
            remove(editor)
        ,
        lifetime
      )
    }

  private[semantics] def remove(editor: Editor): Unit =
    pins.remove(editor).foreach(pin => Disposer.dispose(pin.lifetime))

  private def onEdt(work: => Unit): Unit =
    val app = ApplicationManager.getApplication
    if app.isDispatchThread then
      if !disposed && !project.isDisposed then work
    else app.invokeLater(() => if !disposed && !project.isDisposed then work)

  override def dispose(): Unit =
    disposed = true
    unsubscribe()
    pins.keys.toList.foreach(remove)

/** A compact display label, not source text or a proof verdict. */
private[semantics] final class BendNormalizedValueRenderer(
    value: String,
    reliance: BendReliance,
    remove: () => Unit
) extends EditorCustomElementRenderer:
  private val compact = value.replaceAll("\\s+", " ").trim
  private val count = compact.codePointCount(0, compact.length)
  private val shown = if count <= 160 then compact
  else compact.substring(0, compact.offsetByCodePoints(0, 160)) + "…"
  val label: String = "  Bend ⇒ " + shown + (reliance match
    case BendReliance.UnsafeOrForeign => " (unsafe/foreign)"
    case BendReliance.Unknown         => " (reliance unknown)"
    case BendReliance.None            => "") + "  "

  override def calcWidthInPixels(inlay: Inlay[?]): Int =
    val editor = inlay.getEditor
    val font = editor.getColorsScheme.getFont(EditorFontType.PLAIN)
    math.max(
      1,
      editor.getContentComponent.getFontMetrics(font).stringWidth(label)
    )

  override def paint(
      inlay: Inlay[?],
      graphics: Graphics2D,
      bounds: Rectangle2D,
      attributes: TextAttributes
  ): Unit =
    val editor = inlay.getEditor
    val scheme = editor.getColorsScheme
    val font = scheme.getFont(EditorFontType.PLAIN)
    val metrics = editor.getContentComponent.getFontMetrics(font)
    val color =
      Option(scheme.getAttributes(EditorColors.FOLDED_TEXT_ATTRIBUTES))
        .flatMap(a => Option(a.getForegroundColor))
        .getOrElse(scheme.getDefaultForeground)
    val drawing = graphics.create()
    try
      drawing.setFont(font)
      drawing.setColor(color)
      drawing.drawString(
        label,
        bounds.getX.toInt,
        bounds.getY.toInt + (bounds.getHeight.toInt - metrics.getHeight) / 2 + metrics.getAscent
      )
    finally drawing.dispose()

  override def getContextMenuGroup(inlay: Inlay[?]): ActionGroup =
    new DefaultActionGroup(new AnAction("Remove Pinned Bend Value"):
      override def actionPerformed(event: AnActionEvent): Unit = remove())
