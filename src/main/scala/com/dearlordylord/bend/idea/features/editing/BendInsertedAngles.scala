package com.dearlordylord.bend.idea.features.editing

import com.intellij.openapi.editor.{Editor, RangeMarker}
import com.intellij.openapi.util.Key
import scala.collection.mutable.ArrayBuffer

/** Editor-local provenance for auto-inserted angle closers. Markers follow intervening edits. */
private[editing] object BendInsertedAngles:
  private val key = Key.create[ArrayBuffer[RangeMarker]]("bend.inserted.angle.pairs")

  private def markers(editor: Editor): ArrayBuffer[RangeMarker] =
    Option(editor.getUserData(key)).getOrElse {
      val created = ArrayBuffer.empty[RangeMarker]
      editor.putUserData(key, created)
      created
    }

  private def live(editor: Editor): ArrayBuffer[RangeMarker] =
    val values = markers(editor)
    values.filterInPlace(_.isValid)
    values

  def add(editor: Editor, from: Int): Unit =
    val marker = editor.getDocument.createRangeMarker(from, from + 2)
    val values = live(editor)
    values += marker
    if values.size > 256 then values.remove(0).dispose()

  def consumeCloserAt(editor: Editor, offset: Int): Boolean =
    val source = editor.getDocument.getCharsSequence
    if offset >= source.length || source.charAt(offset) != '>' then return false
    val values = live(editor)
    val index = values.indexWhere(marker => marker.getEndOffset == offset + 1 &&
      marker.getStartOffset < offset && source.charAt(marker.getStartOffset) == '<')
    if index < 0 then false
    else
      values.remove(index).dispose()
      true

  def consumeEmptyPairAt(editor: Editor, openingOffset: Int): Boolean =
    val source = editor.getDocument.getCharsSequence
    if openingOffset + 1 >= source.length || source.charAt(openingOffset) != '<' ||
        source.charAt(openingOffset + 1) != '>' then return false
    val values = live(editor)
    val index = values.indexWhere(marker => marker.getStartOffset == openingOffset &&
      marker.getEndOffset == openingOffset + 2)
    if index < 0 then false
    else
      values.remove(index).dispose()
      true

  def clear(editor: Editor): Unit =
    Option(editor.getUserData(key)).foreach(_.foreach(_.dispose()))
    editor.putUserData(key, null)
