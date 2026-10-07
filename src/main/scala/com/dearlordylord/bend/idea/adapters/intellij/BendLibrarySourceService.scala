package com.dearlordylord.bend.idea.adapters.intellij

import com.dearlordylord.bend.idea.workspace.api.*
import com.intellij.openapi.fileEditor.{FileDocumentManager, FileEditorManager}
import com.intellij.openapi.project.Project
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.psi.search.{FilenameIndex, GlobalSearchScope}
import com.intellij.openapi.project.IndexNotReadyException
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import com.dearlordylord.bend.idea.model.FileId
import com.dearlordylord.bend.idea.workspace.model.BendSourceRecord

/** VFS/document capture is kept outside workspace policy. */
final class BendLibrarySourceService(project: Project)
    extends BendLibrarySource,
      BendSourceCatalog,
      BendWorkspacePaths:
  override def children(path: String, limit: Int): List[BendPathEntry] =
    if limit <= 0 then Nil
    else
      val entries =
        scala.collection.mutable.LinkedHashMap.empty[String, BendPathEntry]
      try
        val directory = Path.of(path).toAbsolutePath.normalize()
        // Reserve the bounded inventory for active source buffers first. A full
        // disk directory must not hide a newly opened module that is not saved.
        FileEditorManager
          .getInstance(project)
          .getOpenFiles
          .iterator
          .filter(file =>
            file.isValid && !file.isDirectory && file.getName.endsWith(
              ".bend"
            ) &&
              file.getParent != null &&
              Path
                .of(file.getParent.getPath)
                .toAbsolutePath
                .normalize() == directory
          )
          .take(limit)
          .foreach { file =>
            if entries.size < limit then
              val _ = entries.getOrElseUpdate(
                file.getName,
                BendPathEntry(file.getName, false)
              )
          }
        if Files.isDirectory(directory) then
          val stream = Files.newDirectoryStream(directory)
          try
            val iterator = stream.iterator()
            while iterator.hasNext && entries.size < limit do
              val child = iterator.next()
              val name = child.getFileName.toString
              val _ = entries.getOrElseUpdate(
                name,
                BendPathEntry(name, Files.isDirectory(child))
              )
          finally stream.close()
        // In-memory VFS roots have no on-disk directory to enumerate.
        if !Files.isDirectory(directory) then
          ProjectRootManager
            .getInstance(project)
            .getContentRoots
            .iterator
            .flatMap { root =>
              val base = root.getPath.stripSuffix("/")
              if directory.toString == base then Some(root)
              else if directory.toString.startsWith(base + "/") then
                Option(
                  root.findFileByRelativePath(
                    directory.toString.drop(base.length + 1)
                  )
                )
              else None
            }
            .take(1)
            .foreach { virtualDirectory =>
              virtualDirectory.getChildren.iterator.take(limit).foreach {
                child =>
                  if entries.size < limit then
                    val _ = entries.getOrElseUpdate(
                      child.getName,
                      BendPathEntry(child.getName, child.isDirectory)
                    )
              }
            }
      catch
        case _: java.nio.file.InvalidPathException => ()
        case _: java.io.IOException                => ()
        case _: SecurityException                  => ()
      entries.values.toList

  override def filesNamed(
      name: String,
      limit: Int
  ): BendNamedPathInventory =
    if limit <= 0 || !name.endsWith(".bend") then
      BendNamedPathInventory(Nil, BendPathInventoryStatus.Complete)
    else
      ReadAction.compute(() => {
        val fileIndex = ProjectRootManager
          .getInstance(project)
          .getFileIndex
        val open = FileEditorManager
          .getInstance(project)
          .getOpenFiles
          .iterator
          .filter(file =>
            file.isValid && fileIndex.isInContent(file) &&
              !file.isDirectory && file.getName == name
          )
          .map(_.getPath)
          .distinct
          .take(limit + 1)
          .toList
        val openPaths = open.toSet
        val remainingCandidates = (limit + 1 - open.size).max(0)
        val indexed: Option[List[String]] =
          try
            Some(
              FilenameIndex
                .getVirtualFilesByName(
                  name,
                  GlobalSearchScope.projectScope(project)
                )
                .asScala
                .iterator
                .filter(file => file.isValid && !file.isDirectory)
                .map(_.getPath)
                .filterNot(openPaths.contains)
                .distinct
                .take(remainingCandidates)
                .toList
            )
          catch case _: IndexNotReadyException => None
        val candidates = open ++ indexed.getOrElse(Nil)
        BendNamedPathInventory(
          candidates.take(limit),
          BendPathInventoryStatus.of(indexed.isDefined, candidates.size > limit)
        )
      })

  override def filesWithExtension(
      extension: String,
      limit: Int
  ): Either[String, List[String]] =
    if limit <= 0 || extension.isEmpty then
      Left("A positive limit and file extension are required.")
    else
      ReadAction.compute(() =>
        try
          val ext = extension.stripPrefix(".")
          val fileIndex = ProjectRootManager
            .getInstance(project)
            .getFileIndex
          val found = scala.collection.mutable.LinkedHashSet.empty[String]
          val open = FileEditorManager
            .getInstance(project)
            .getOpenFiles
            .iterator
            .filter(file =>
              file.isValid && fileIndex.isInContent(file) &&
                !file.isDirectory &&
                file.getExtension == ext
            )
          while open.hasNext && found.size <= limit do
            val _ = found.add(open.next().getPath)
          val indexed = FilenameIndex
            .getAllFilesByExt(
              project,
              ext,
              GlobalSearchScope.projectScope(project)
            )
            .asScala
            .iterator
          while indexed.hasNext && found.size <= limit do
            val file = indexed.next()
            if file.isValid && fileIndex.isInContent(file) &&
              !file.isDirectory && file.getExtension == ext
            then
              val _ = found.add(file.getPath)
          val bounded = found.toList
          if bounded.size > limit then
            Left("Project source inventory exceeds the safe refactoring limit.")
          else Right(bounded)
        catch
          case _: IndexNotReadyException =>
            Left("Project file index is unavailable for safe refactoring.")
      )

  override def canonicalPath(path: String): String =
    if path.isEmpty then path
    else
      try
        val requested = Path.of(path)
        if Files.exists(requested) then requested.toRealPath().toString
        else
          val absolute = requested.toAbsolutePath.normalize()
          val parent = absolute.getParent
          if parent != null && Files.exists(parent) then
            parent.toRealPath().resolve(absolute.getFileName).toString
          else absolute.toString
      catch
        case _: java.io.IOException                => path
        case _: java.nio.file.InvalidPathException => path
        case _: SecurityException                  => path

  override def cachedPackageHash(
      packageCache: String,
      name: String
  ): Option[String] =
    if !BendImportPaths.isNamedPackage(name) || packageCache.isEmpty then None
    else
      try
        val input = Files.newInputStream(
          Path.of(packageCache).resolve("names").resolve(name)
        )
        val bytes = try input.readNBytes(129)
        finally input.close()
        if bytes.length > 128 then None
        else
          Option(
            new String(bytes, java.nio.charset.StandardCharsets.UTF_8).trim
          ).filter(BendImportPaths.isPackageHash)
      catch
        case _: java.io.IOException                => None
        case _: java.nio.file.InvalidPathException => None
        case _: SecurityException                  => None

  private def currentVirtual(path: String) =
    ReadAction.compute(() => {
      val local =
        LocalFileSystem.getInstance().findFileByNioFile(Path.of(path))
      val projectFile = ProjectRootManager
        .getInstance(project)
        .getContentRoots
        .iterator
        .flatMap { root =>
          Option
            .when(path.startsWith(root.getPath.stripSuffix("/") + "/"))(
              path.drop(root.getPath.stripSuffix("/").length + 1)
            )
            .flatMap(relative => Option(root.findFileByRelativePath(relative)))
        }
        .take(1)
        .toList
        .headOption
      val resolved = projectFile.orElse(Option(local))
      // Closing an editor does not discard a modified document. A canonical
      // check path can have a different VFS handle from that document's alias.
      val documents = FileDocumentManager.getInstance()
      val identity = resolved
        .flatMap(candidate => Option(candidate.getCanonicalPath))
        .getOrElse(path)
      val modified = resolved.filter(documents.isFileModified).orElse {
        documents.getUnsavedDocuments.iterator
          .flatMap(document => Option(documents.getFile(document)))
          .find(file =>
            file.isValid && file.getCanonicalPath != null &&
              file.getCanonicalPath == identity
          )
      }
      // Unmodified open documents still retain their current editor revision.
      val open = FileEditorManager
        .getInstance(project)
        .getOpenFiles
        .find(openFile =>
          openFile.isValid && openFile.getCanonicalPath != null &&
            openFile.getCanonicalPath == identity
        )
      val file = modified.orElse(open).orElse(resolved).orNull
      if file == null || !file.isValid then None
      else
        Some(
          (
            file,
            file.isDirectory,
            file.isInLocalFileSystem,
            file.getCharset,
            file.getPath
          )
        )
    })

  override def currentSourcePath(path: String): Option[String] =
    try currentVirtual(path).map(_._5)
    catch
      case _: java.nio.file.InvalidPathException => None
      case _: SecurityException                  => None

  override def source(path: String): Option[BendSourceRecord] =
    try
      val virtual = currentVirtual(path)
      if virtual.isEmpty then
        val disk = Path.of(path)
        if !Files.isRegularFile(disk) then None
        else
          val text = Files.readString(disk)
          val canonical = disk.toRealPath().toString
          val revision =
            Files.getLastModifiedTime(disk).toMillis ^ text.hashCode.toLong
          Some(
            BendSourceRecord(
              new FileId(canonical, true),
              path,
              text,
              revision,
              BendImportLines.parse(text)
            )
          )
      else if virtual.get._2 then None
      else
        val (file, _, local, charset, filePath) = virtual.get
        val manager = FileDocumentManager.getInstance()
        val onDisk = local && Files.exists(Path.of(path))
        val captured = ReadAction.compute(() => {
          if !file.isValid then None
          else
            val document = manager.getDocument(file)
            val useDocument =
              document != null && (manager.isFileModified(file) || !onDisk ||
                FileEditorManager
                  .getInstance(project)
                  .getOpenFiles
                  .contains(file))
            val text = if useDocument then Some(document.getText) else None
            val revision =
              if useDocument then Some(document.getModificationStamp) else None
            val canonical = Option(file.getCanonicalPath)
            val contents = if !useDocument && !onDisk then
              Some(new String(file.contentsToByteArray(), charset))
            else None
            Some(
              (text, revision, canonical, file.getModificationStamp, contents)
            )
        })
        captured.map { state =>
          val text = if state._1.nonEmpty then state._1.get
          else if onDisk then Files.readString(Path.of(path), charset)
          else state._5.getOrElse("")
          val revision = if state._2.nonEmpty then state._2.get
          else if onDisk then
            Files
              .getLastModifiedTime(Path.of(path))
              .toMillis ^ text.hashCode.toLong
          else state._4
          BendSourceRecord(
            new FileId(state._3.getOrElse(filePath), state._3.isDefined),
            filePath,
            text,
            revision,
            BendImportLines.parse(text)
          )
        }
    catch
      case _: java.nio.file.InvalidPathException => None
      case _: java.io.IOException                => None
      case _: SecurityException                  => None

  override def base(path: String, configurationRevision: Long): BendBaseState =
    source(path) match
      case Some(record) =>
        BendBaseState.Available(
          BendBaseSource(
            record.id.value,
            record.text,
            record.revision,
            configurationRevision
          )
        )
      case None => BendBaseState.Missing(path)
