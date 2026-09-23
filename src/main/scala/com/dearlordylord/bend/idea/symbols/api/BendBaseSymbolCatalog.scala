package com.dearlordylord.bend.idea.symbols.api

import com.dearlordylord.bend.idea.workspace.api.BendBaseSource
import com.intellij.openapi.project.Project

/** One project-local parsed Base revision; text equality protects against stamp collisions. */
final class BendBaseSymbolCatalog(project: Project):
  private var cached: Option[(BendBaseSource, List[BendSourceSymbol])] = None

  def declarations(source: BendBaseSource): List[BendSourceSymbol] = synchronized {
    cached match
      case Some((previous, symbols)) if previous == source => symbols
      case _ =>
        val symbols = BendSourceSymbols.baseDeclarations(project, source)
        cached = Some((source, symbols))
        symbols
  }
