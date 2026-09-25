package com.dearlordylord.bend.idea.symbols.api

import com.dearlordylord.bend.idea.symbols.declarations.BendLawDeclarations
import com.dearlordylord.bend.idea.workspace.api.BendLoadingConfiguration
import com.dearlordylord.bend.idea.workspace.model.BendLoadedGraph
import com.intellij.psi.PsiFile

/** Root-relative source facts; law and fill retain separate source handles. */
final case class BendDocumentationSite(
    symbol: BendSourceSymbol,
    law: Option[BendSourceSymbol],
    fills: List[BendSourceSymbol]
):
  def sourceSignature: String = symbol.signature.source
  def lawSpecification: Option[String] = law.map(_.signature.source)

object BendSourceDocumentation:
  /** Use the request buffer as root, including unsaved imports and source text.
    */
  def site(
      requestFile: PsiFile,
      symbol: BendSourceSymbol
  ): BendDocumentationSite =
    val project = requestFile.getProject
    val (basePath, packageCache) =
      project.getService(classOf[BendLoadingConfiguration]).paths
    val graph = project
      .getService(classOf[BendImportedSymbolCatalog])
      .loaded(requestFile, basePath, packageCache)
    site(requestFile, symbol, graph)

  /** Join source declarations against an already loaded root graph. Callers
    * that captured the graph off the read thread avoid repeating I/O here.
    */
  def site(
      requestFile: PsiFile,
      symbol: BendSourceSymbol,
      graph: BendLoadedGraph
  ): BendDocumentationSite =
    val project = requestFile.getProject
    val rootDeclarations = BendSourceSymbols.declarations(requestFile)
    // Pair declarations throughout this root's loaded graph. A proof root can
    // import an index module which imports laws, while a nested proof module
    // imports those same laws under its own alias. Only loaded, namespaced
    // sources participate; unqualified Base declarations do not become laws.
    val relevant =
      graph.files.filter(f => f.source.id == graph.root || f.namespace.nonEmpty)
    val loaded = relevant.flatMap { loadedFile =>
      val source = loadedFile.source
      val declarations = if source.id == graph.root then rootDeclarations
      else BendSourceSymbols.sourceDeclarations(project, source.id, source.text)
      declarations.map(s => (source.id, s))
    }
    val laws = loaded.collect {
      case (id, law) if law.category == BendSymbolCategory.Law =>
        (id, law)
    }
    val definitions = loaded.collect {
      case (id, fill) if fill.category == BendSymbolCategory.Definition =>
        (id, fill)
    }
    val validImports = graph.edges
      .filter(_.importLine.alias.nonEmpty)
      .reverse
      .distinctBy(edge => edge.from -> edge.importLine.alias)
      .reverse
      .filter(edge =>
        edge.target.exists(target =>
          relevant.exists(file =>
            file.source.id == target && file.namespace == edge.namespace
          )
        )
      )
    val links = laws.flatMap { case (lawId, law) =>
      val matchingAliases = validImports
        .filter(_.target.contains(lawId))
        .flatMap(edge => edge.importLine.alias.map(alias => edge.from -> alias))
      val matches = definitions
        .collect {
          case (fillId, fill)
              if (fillId == lawId && fill.name == law.name) ||
                matchingAliases.exists { case (from, alias) =>
                  from == fillId && fill.name == s"$alias.${law.name}"
                } =>
            val comparable = fill.copy(name = law.name)
            // Within one source, declaration order matters. Across imported
            // sources the root graph establishes the relationship.
            Option.when(
              BendLawDeclarations.isFill(
                BendSourceSymbols.site(law),
                BendSourceSymbols.site(comparable),
                requireOrder = fillId == lawId
              )
            )(fill)
        }
        .flatten
        .distinctBy(_.handle)
      Option.when(matches.nonEmpty)(law -> matches)
    }
    val paired = links.find { case (law, matches) =>
      law.handle == symbol.handle || matches.exists(_.handle == symbol.handle)
    }
    BendDocumentationSite(
      symbol,
      paired.flatMap { case (law, _) =>
        Option.when(law.handle != symbol.handle)(law)
      },
      paired.toList.flatMap(_._2).filterNot(_.handle == symbol.handle)
    )
