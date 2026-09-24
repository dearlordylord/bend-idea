package com.dearlordylord.bend.idea.symbols.api

import com.intellij.openapi.project.Project

/** Contextual workspace results for explicit import choices. Discovery does not
  * change lexical visibility or ordinary completion.
  */
final case class BendWorkspaceSymbolCandidate(
    symbol: BendSourceSymbol,
    path: Option[String],
    context: String,
    isBase: Boolean
):
  override def toString: String =
    val category = symbol.category.toString.toLowerCase
    symbol.name + " — " + category + " — " + context

/** Public symbol query facade; index mechanics remain inside symbols.index. */
object BendWorkspaceSymbolSearch:
  def named(
      project: Project,
      name: String
  ): Either[String, List[BendWorkspaceSymbolCandidate]] =
    com.dearlordylord.bend.idea.symbols.index.BendWorkspaceSymbolIndexSearch
      .named(project, name)
