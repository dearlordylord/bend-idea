package com.dearlordylord.bend.idea.workspace.api

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
  ): List[com.dearlordylord.bend.idea.workspace.model.BendImport] =
    val result =
      List.newBuilder[com.dearlordylord.bend.idea.workspace.model.BendImport]
    val Import =
      "^import\\s+(\\S+)(?:\\s+as\\s+([A-Za-z_][A-Za-z0-9_]*))?\\s*(?:#.*)?$".r
    var offset = 0
    var leading = true
    source.split("\n", -1).foreach { line =>
      val trimmed = line.trim
      if leading then
        if trimmed.isEmpty || trimmed.startsWith("#") then ()
        else if AnyImport.matches(trimmed) then
          trimmed match
            case Import(path, alias) =>
              result += com.dearlordylord.bend.idea.workspace.model.BendImport(
                path,
                Option(alias),
                offset + line.indexOf("import")
              )
            case _ =>
              result += com.dearlordylord.bend.idea.workspace.model.BendImport(
                trimmed.stripPrefix("import").trim,
                None,
                offset + line.indexOf("import"),
                Some("an import ('import Base', or 'import <path> as <Name>')")
              )
        else leading = false
      offset += line.length + 1
    }
    result.result()
