package com.dearlordylord.bend.idea.workspace.api

import com.dearlordylord.bend.idea.workspace.model.BendImport

/** Loader-compatible recognition of the leading import block. The pinned loader
  * scans trimmed source lines before parsing declarations.
  */
object BendImportLines:
  private val AnyImport = "^import(\\s.*|)$".r

  /** Half-open path range in the original source; excludes the alias and
    * comment.
    */
  def pathRange(
      source: String,
      imp: com.dearlordylord.bend.idea.workspace.model.BendImport
  ): Option[(Int, Int)] =
    if imp.syntaxProblem.nonEmpty || imp.spelling.isEmpty then None
    else
      val start =
        source.indexWhere(c => !c.isWhitespace, imp.offset + "import".length)
      Option.when(start >= 0 && source.startsWith(imp.spelling, start))(
        (start, start + imp.spelling.length)
      )

  /** Leading import block only, with original spellings and UTF-16 offsets. */
  def parse(
      source: String
  ): List[BendImport] =
    val Import =
      "^import\\s+(\\S+)(?:\\s+as\\s+([A-Za-z_][A-Za-z0-9_]*))?\\s*(?:#.*)?$".r
    val lines = source.split("\n", -1).toList
    val offsets = lines.scanLeft(0)((offset, line) => offset + line.length + 1)
    val imports = offsets
      .dropRight(1)
      .zip(lines)
      .foldLeft(
        (true, List.empty[BendImport])
      ) { case ((leading, found), (offset, line)) =>
        val trimmed = line.trim
        if !leading then (false, found)
        else if trimmed.isEmpty || trimmed.startsWith("#") then (true, found)
        else if AnyImport.matches(trimmed) then
          val imp = trimmed match
            case Import(path, alias) =>
              BendImport(
                path,
                Option(alias),
                offset + line.indexOf("import")
              )
            case _ =>
              BendImport(
                trimmed.stripPrefix("import").trim,
                None,
                offset + line.indexOf("import"),
                Some("an import ('import Base', or 'import <path> as <Name>')")
              )
          (true, imp :: found)
        else (false, found)
      }
    imports._2.reverse
