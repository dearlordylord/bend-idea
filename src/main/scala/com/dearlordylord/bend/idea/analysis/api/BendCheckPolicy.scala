package com.dearlordylord.bend.idea.analysis.api

import com.dearlordylord.bend.idea.analysis.model.*
import com.dearlordylord.bend.idea.workspace.api.BendImportLines

/** This slice accepts exactly a single source and its matching built-in Base. */
object BendCheckPolicy:
  def unsupportedImport(snapshot: BendCheckSnapshot): Option[String] =
    BendImportLines.parse(snapshot.text).find(imp =>
      imp.syntaxProblem.nonEmpty || imp.spelling != "Base" || imp.alias.nonEmpty)
      .map(imp => s"Checking imports other than 'import Base' needs complete graph snapshots: ${imp.spelling}")
