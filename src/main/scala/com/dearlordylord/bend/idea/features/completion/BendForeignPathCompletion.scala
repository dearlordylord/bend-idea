package com.dearlordylord.bend.idea.features.completion

import com.dearlordylord.bend.idea.syntax.psi.BendForeignPaths
import com.dearlordylord.bend.idea.workspace.api.BendWorkspacePaths
import com.intellij.codeInsight.completion.{
  CompletionParameters,
  CompletionResultSet,
  InsertionContext
}
import com.intellij.codeInsight.lookup.{LookupElement, LookupElementBuilder}

/** Local, source-relative C/JavaScript candidates for a foreign def body. */
private[completion] object BendForeignPathCompletion:
  def add(
      parameters: CompletionParameters,
      result: CompletionResultSet,
      source: String,
      offset: Int
  ): Boolean =
    val file = parameters.getOriginalFile
    BendForeignPaths.completionSiteInSource(
      file.getProject,
      source,
      offset
    ) match
      case None       => false
      case Some(site) =>
        if site.prefix.contains('\\') then return true
        val sourcePath = Option(file.getVirtualFile).map(_.getPath)
        val prefixSlash = site.prefix.lastIndexOf('/')
        val directoryPrefix =
          if prefixSlash < 0 then "" else site.prefix.take(prefixSlash + 1)
        val namePrefix =
          if prefixSlash < 0 then site.prefix
          else site.prefix.drop(prefixSlash + 1)
        val directory = sourcePath.flatMap(
          BendForeignPaths.resolve(_, directoryPrefix)
        )
        val children = directory.toList.flatMap { path =>
          file.getProject
            .getService(classOf[BendWorkspacePaths])
            .children(path.toString, 256)
            .filter(entry =>
              !entry.directory &&
                (entry.name.endsWith(".c") || entry.name.endsWith(".js")) &&
                entry.name.startsWith(namePrefix)
            )
            .sortBy(_.name)
        }
        val matcher = result.withPrefixMatcher("")
        children.foreach { child =>
          val spelling = directoryPrefix + child.name
          val fileKind =
            if child.name.endsWith(".c") then "C source"
            else "JavaScript source"
          matcher.addElement(
            LookupElementBuilder
              .create(spelling)
              .withPresentableText(child.name)
              .withTypeText(fileKind)
              .withInsertHandler(
                (insertion: InsertionContext, _: LookupElement) =>
                  val escaped = escape(spelling, site.quote)
                  insertion.getDocument.replaceString(
                    site.range.getStartOffset,
                    site.range.getEndOffset + spelling.length,
                    escaped
                  )
                  insertion.getEditor.getCaretModel.moveToOffset(
                    site.range.getStartOffset + escaped.length
                  )
                  insertion.commitDocument()
              )
          )
        }
        true

  private def escape(value: String, quote: Char): String =
    value.replace("\\", "\\\\").replace(quote.toString, "\\" + quote)
