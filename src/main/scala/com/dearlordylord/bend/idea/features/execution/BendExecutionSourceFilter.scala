package com.dearlordylord.bend.idea.features.execution

import com.intellij.execution.filters.{Filter, OpenFileHyperlinkInfo}
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import java.nio.file.Path
import scala.util.control.NonFatal

/** Links only explicit absolute source locations. The pinned CLI's current
  * textual errors often omit a path, so those remain ordinary console text.
  */
private[execution] final class BendExecutionSourceFilter(project: Project)
    extends Filter:
  private val Location = "^(.+\\.bend):(\\d+):(\\d+)(?::.*)?$".r

  override def applyFilter(line: String, entireLength: Int): Filter.Result =
    line match
      case Location(pathText, lineText, columnText) =>
        try
          val path = Path.of(pathText)
          val lineNumber = lineText.toInt
          val columnNumber = columnText.toInt
          if !path.isAbsolute || lineNumber < 1 || columnNumber < 1 then
            return null
          val start = math.max(0, entireLength - line.length)
          Option(LocalFileSystem.getInstance().findFileByNioFile(path))
            .filter(_.isValid)
            .map(file =>
              new Filter.Result(
                start,
                start + line.length,
                new OpenFileHyperlinkInfo(
                  project,
                  file,
                  lineNumber - 1,
                  columnNumber - 1
                )
              )
            )
            .orNull
        catch case NonFatal(_) => null
      case _ => null
