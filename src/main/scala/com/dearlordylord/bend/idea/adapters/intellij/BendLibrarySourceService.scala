package com.dearlordylord.bend.idea.adapters.intellij

import com.dearlordylord.bend.idea.workspace.api.*
import com.intellij.openapi.fileEditor.{FileDocumentManager, FileEditorManager}
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.roots.ProjectRootManager
import java.nio.file.{Files, Path}
import com.dearlordylord.bend.idea.model.FileId
import com.dearlordylord.bend.idea.workspace.model.BendSourceRecord
import com.dearlordylord.bend.idea.workspace.ports.BendSourceCatalog

/** VFS/document capture is kept outside workspace policy. */
final class BendLibrarySourceService(project: Project) extends BendLibrarySource, BendSourceCatalog, BendWorkspacePaths:
  override def children(path: String, limit: Int): List[BendPathEntry] =
    if limit <= 0 then Nil
    else
      val entries = scala.collection.mutable.LinkedHashMap.empty[String, BendPathEntry]
      try
        val directory = Path.of(path).toAbsolutePath.normalize()
        // Reserve the bounded inventory for active source buffers first. A full
        // disk directory must not hide a newly opened module that is not saved.
        FileEditorManager.getInstance(project).getOpenFiles.iterator
          .filter(file => file.isValid && !file.isDirectory && file.getName.endsWith(".bend") &&
            file.getParent != null &&
            Path.of(file.getParent.getPath).toAbsolutePath.normalize() == directory)
          .take(limit).foreach { file =>
            if entries.size < limit then entries.getOrElseUpdate(file.getName,
              BendPathEntry(file.getName, false))
          }
        if Files.isDirectory(directory) then
          val stream = Files.newDirectoryStream(directory)
          try
            val iterator = stream.iterator()
            while iterator.hasNext && entries.size < limit do
              val child = iterator.next()
              val name = child.getFileName.toString
              entries.getOrElseUpdate(name, BendPathEntry(name, Files.isDirectory(child)))
          finally stream.close()
        // In-memory VFS roots have no on-disk directory to enumerate.
        if !Files.isDirectory(directory) then
          ProjectRootManager.getInstance(project).getContentRoots.iterator
            .flatMap { root =>
              val base = root.getPath.stripSuffix("/")
              if directory.toString == base then Some(root)
              else if directory.toString.startsWith(base + "/") then
                Option(root.findFileByRelativePath(directory.toString.drop(base.length + 1)))
              else None
            }
            .take(1).foreach { virtualDirectory =>
              virtualDirectory.getChildren.iterator.take(limit).foreach { child =>
                if entries.size < limit then entries.getOrElseUpdate(child.getName,
                  BendPathEntry(child.getName, child.isDirectory))
              }
            }
      catch
        case _: java.nio.file.InvalidPathException => ()
        case _: java.io.IOException => ()
        case _: SecurityException => ()
      entries.values.toList

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
