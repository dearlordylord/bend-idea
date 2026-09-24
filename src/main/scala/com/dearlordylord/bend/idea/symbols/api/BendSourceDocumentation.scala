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
    val aliases = project
      .getService(classOf[BendImportedSymbolCatalog])
      .effectiveDirectEdges(graph)
      .flatMap(edge =>
        edge.target.toList.flatMap(target =>
          edge.importLine.alias.toList.map(alias => target -> alias)
        )
      )
    val directSources = aliases.map(_._1).toSet + graph.root
    // Base contributes unqualified symbols even when reached transitively.
    val relevant = graph.files.filter(f =>
      directSources.contains(f.source.id) || f.namespace.isEmpty
    )
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
    val links = laws.flatMap { case (id, law) =>
      val names =
        if id == graph.root || relevant.exists(f =>
            f.source.id == id && f.namespace.isEmpty
          )
        then List(law.name)
        else aliases.collect { case (`id`, alias) => alias + "." + law.name }
      val matches = definitions
        .collect {
          case (fillId, fill)
              if (fillId == id || fillId == graph.root) &&
                (if fillId == id then List(law.name) else names).exists {
                  name =>
                    val qualifiedLaw = law.copy(name = name)
                    // Within one source, declaration order matters; imported laws
                    // precede the root's definitions through the loader graph.
                    BendLawDeclarations.isFill(
                      BendSourceSymbols.site(qualifiedLaw),
                      BendSourceSymbols.site(fill),
                      requireOrder = fillId == id
                    )
                } =>
            fill
        }
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
