package com.dearlordylord.bend.idea.features.editing

import com.intellij.openapi.editor.event.{
  EditorFactoryEvent,
  EditorFactoryListener
}

final class BendEditorRelease extends EditorFactoryListener:
  override def editorReleased(event: EditorFactoryEvent): Unit =
    BendInsertedAngles.clear(event.getEditor)
