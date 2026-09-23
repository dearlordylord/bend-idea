package com.dearlordylord.bend.idea.features.completion

import com.dearlordylord.bend.idea.toolchain.api.BendToolchainSettings
import com.dearlordylord.bend.idea.workspace.api.{
  BendImportLines,
  BendImportPaths,
  BendWorkspacePaths
}
import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.completion.{
  CompletionParameters,
  CompletionResultSet,
  InsertionContext
}
import com.intellij.codeInsight.lookup.{LookupElement, LookupElementBuilder}
import com.intellij.openapi.application.ApplicationManager

/** One-level, offline import path suggestions. The catalog owns filesystem
  * access.
  */
private[completion] object BendImportPathCompletion:
  private final case class Site(pathStart: Int, prefix: String)

  def add(
      parameters: CompletionParameters,
      result: CompletionResultSet,
      source: String,
      offset: Int
  ): Boolean =
    site(source, offset) match
      case None           => false
      case Some(location) =>
        val file = parameters.getOriginalFile.getVirtualFile
        if file == null then return true
        val selected = ApplicationManager.getApplication
          .getService(classOf[BendToolchainSettings])
          .selection
        val catalog = parameters.getOriginalFile.getProject.getService(
          classOf[BendWorkspacePaths]
        )
        val directory = BendImportPaths.directory(
          file.getPath,
          selected.packageCache,
          location.prefix
        )
        val slash = location.prefix.lastIndexOf('/')
        val parent = if slash < 0 then "" else location.prefix.take(slash + 1)
        val names =
          scala.collection.mutable.LinkedHashMap.empty[String, Boolean]
        if location.prefix.isEmpty then
          names("Base") = false
          names("./") = true
          names("../") = true
        else if "Base".startsWith(location.prefix) then names("Base") = false
        val hashRoot = location.prefix.nonEmpty &&
          location.prefix.lastIndexOf('/') < location.prefix.length - 1 &&
          BendImportPaths.isHashRootPrefix(location.prefix)
        catalog.children(directory, 256).foreach { entry =>
          val name = entry.name
          val valid =
            name.nonEmpty && !name.startsWith(".") && name != "node_modules" &&
              !name.exists(c =>
                c.isWhitespace || c == '#' || c == '\'' || c == '"'
              )
          if valid then
            if entry.directory && !BendImportPaths.isHashRootFragment(
                parent + name
              )
            then names(parent + name + "/") = true
            else if name.endsWith(".bend") then names(parent + name) = false
        }
        if hashRoot then
          catalog.children(selected.packageCache, 256).foreach { entry =>
            if entry.directory && entry.name.matches("0x[0-9a-f]+") then
              names(parent + entry.name + "/") = true
          }
        names.iterator
          .filter { case (name, _) => name.startsWith(location.prefix) }
          .foreach { case (name, folder) =>
            result
              .withPrefixMatcher(location.prefix)
              .addElement(
                LookupElementBuilder
                  .create(name)
                  .withTypeText(if name == "Base" then "Bend standard library"
                  else if folder then "Bend directory"
                  else "Bend module")
                  .withInsertHandler(
                    (insertion: InsertionContext, _: LookupElement) =>
                      insertion.setAddCompletionChar(false)
                      val document = insertion.getDocument
                      var end = location.pathStart
                      while end < document.getTextLength &&
                        !document.getCharsSequence.charAt(end).isWhitespace &&
                        document.getCharsSequence.charAt(end) != '#'
                      do end += 1
                      document.replaceString(location.pathStart, end, name)
                      insertion.getEditor.getCaretModel
                        .moveToOffset(location.pathStart + name.length)
                      if folder then
                        insertion.setLaterRunnable(() =>
                          AutoPopupController
                            .getInstance(insertion.getProject)
                            .scheduleAutoPopup(insertion.getEditor)
                        )
                  )
              )
          }
        true

  private def site(source: String, offset: Int): Option[Site] =
    if offset < 0 || offset > source.length then None
    else
      val start = source.lastIndexOf('\n', offset - 1) + 1
      val endIndex = source.indexOf('\n', offset)
      val end = if endIndex < 0 then source.length else endIndex
      val line = source.substring(start, end)
      val Import = "^(\\s*import[ \\t]+)([^\\s#\"']*)(.*)$".r
      line match
        case Import(head, path, tail)
            if offset >= start + head.length && offset <= start + head.length + path.length &&
              tail.matches(
                "(?:[ \\t]+as[ \\t]+[A-Za-z_][A-Za-z0-9_]*)?[ \\t]*(?:#.*)?"
              ) &&
              BendImportLines
                .parse(source)
                .exists(_.offset == start + head.indexOf("import")) =>
          Some(
            Site(
              start + head.length,
              source.substring(start + head.length, offset)
            )
          )
        case _ => None
