package com.dearlordylord.bend.idea.adapters.intellij

import com.dearlordylord.bend.idea.workspace.api.*
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import java.nio.file.{Files, Path}

/** VFS/document capture is kept outside workspace policy. */
final class BendLibrarySourceService(project: Project) extends BendLibrarySource:
  override def base(path: String, configurationRevision: Long): BendBaseState =
    try
      val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(Path.of(path))
      if file == null || file.isDirectory then BendBaseState.Missing(path)
      else
        val manager = FileDocumentManager.getInstance()
        val document = manager.getDocument(file)
        val unsaved = document != null && manager.isFileModified(file)
        val diskPath = Path.of(path)
        val text = if unsaved then document.getText else Files.readString(diskPath, file.getCharset)
        val revision = if unsaved then document.getModificationStamp
          else Files.getLastModifiedTime(diskPath).toMillis ^ text.hashCode.toLong
        BendBaseState.Available(BendBaseSource(
          Option(file.getCanonicalPath).getOrElse(file.getPath), text, revision,
          configurationRevision))
    catch
      case _: java.nio.file.InvalidPathException => BendBaseState.Missing(path)
      case _: java.io.IOException => BendBaseState.Missing(path)
      case _: SecurityException => BendBaseState.Missing(path)
