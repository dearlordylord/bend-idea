package com.dearlordylord.bend.idea.adapters.intellij

import com.dearlordylord.bend.idea.workspace.api.*
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.roots.ProjectRootManager
import java.nio.file.{Files, Path}
import com.dearlordylord.bend.idea.model.FileId
import com.dearlordylord.bend.idea.workspace.model.BendSourceRecord
import com.dearlordylord.bend.idea.workspace.ports.BendSourceCatalog

/** VFS/document capture is kept outside workspace policy. */
final class BendLibrarySourceService(project: Project) extends BendLibrarySource, BendSourceCatalog:
  override def source(path: String): Option[BendSourceRecord] =
    try
      val local = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(Path.of(path))
      val projectFile = ProjectRootManager.getInstance(project).getContentRoots.iterator.flatMap { root =>
        Option.when(path.startsWith(root.getPath.stripSuffix("/") + "/"))(
          path.drop(root.getPath.stripSuffix("/").length + 1))
          .flatMap(relative => Option(root.findFileByRelativePath(relative)))
      }.take(1).toList.headOption
      val file = projectFile.orElse(Option(local)).orNull
      if file == null || file.isDirectory then None
      else
        val manager = FileDocumentManager.getInstance()
        val document = manager.getDocument(file)
        val onDisk = file.isInLocalFileSystem && Files.exists(Path.of(path))
        val useDocument = document != null && (manager.isFileModified(file) || !onDisk)
        val text = if useDocument then document.getText
          else if onDisk then Files.readString(Path.of(path), file.getCharset)
          else new String(file.contentsToByteArray(), file.getCharset)
        val revision = if useDocument then document.getModificationStamp
          else if onDisk then Files.getLastModifiedTime(Path.of(path)).toMillis ^ text.hashCode.toLong
          else file.getModificationStamp
        val canonical = Option(file.getCanonicalPath)
        Some(BendSourceRecord(new FileId(canonical.getOrElse(file.getPath), canonical.isDefined),
          file.getPath, text, revision, BendImportLines.parse(text)))
    catch
      case _: java.nio.file.InvalidPathException => None
      case _: java.io.IOException => None
      case _: SecurityException => None

  override def base(path: String, configurationRevision: Long): BendBaseState =
    source(path) match
      case Some(record) => BendBaseState.Available(BendBaseSource(record.id.value,
        record.text, record.revision, configurationRevision))
      case None => BendBaseState.Missing(path)
