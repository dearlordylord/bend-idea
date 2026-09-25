package com.dearlordylord.bend.idea.symbols.api

import com.dearlordylord.bend.idea.symbols.declarations.BendLawDeclarations
import com.dearlordylord.bend.idea.model.FileId
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

final case class BendDocumentationLinks(
    law: Option[BendSourceDeclarationFact],
    fills: List[BendSourceDeclarationFact]
)

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
    // imports those same laws under its own alias. An explicitly loaded Base
    // source may pair its own law and fill; its unqualified names still do not
    // pair with declarations from another source.
    val importedBaseSources = graph.edges
      .filter(_.importLine.spelling == "Base")
      .flatMap(_.target)
      .toSet
    val relevant =
      graph.files.filter(f =>
        f.source.id == graph.root || f.namespace.nonEmpty || importedBaseSources
          .contains(f.source.id)
      )
    val loaded = relevant.flatMap { loadedFile =>
      val source = loadedFile.source
      val declarations = if source.id == graph.root then rootDeclarations
      else BendSourceSymbols.sourceDeclarations(project, source.id, source.text)
      declarations.map(s => (source.id, s))
    }
    val facts = loaded.map { case (_, declaration) =>
      BendSourceSymbols.declarationFact(declaration)
    }
    val symbolsByHandle = loaded.map { case (_, declaration) =>
      declaration.handle -> declaration
    }.toMap
    val links = relatedDeclarations(
      graph,
      BendSourceSymbols.declarationFact(symbol),
      facts
    )
    BendDocumentationSite(
      symbol,
      links.law
        .filter(_.handle != symbol.handle)
        .flatMap(fact => symbolsByHandle.get(fact.handle)),
      links.fills
        .filterNot(_.handle == symbol.handle)
        .flatMap(fact => symbolsByHandle.get(fact.handle))
    )

  /** Join one selected law/fill to the other declarations in the same root. */
  def relatedDeclarations(
      graph: BendLoadedGraph,
      selected: BendSourceDeclarationFact,
      declarations: List[BendSourceDeclarationFact]
  ): BendDocumentationLinks =
    val fillLaws = linkedFills(graph, declarations)
    val law =
      if selected.category == BendSymbolCategory.Law then
        declarations.find(_.handle == selected.handle)
      else fillLaws.get(selected.handle)
    val fills = law.toList.flatMap { selectedLaw =>
      fillLaws.collect {
        case (fillHandle, candidateLaw)
            if candidateLaw.handle == selectedLaw.handle =>
          declarations.find(_.handle == fillHandle)
      }.flatten
    }
    BendDocumentationLinks(law, fills)

  /** Resolve cross-file law/fill relationships from immutable declarations and
    * the selected root graph. The returned map contains no PSI objects.
    */
  def linkedFills(
      graph: BendLoadedGraph,
      declarations: List[BendSourceDeclarationFact]
  ): Map[BendSourceHandle, BendSourceDeclarationFact] =
    val relevantIds = relevantSourceIds(graph)
    val relevant =
      graph.files.filter(file => relevantIds.contains(file.source.id))
    val sourceFacts =
      declarations.filter(fact => relevantIds.contains(fact.handle.file))
    val laws = sourceFacts.filter(_.category == BendSymbolCategory.Law)
    val definitions = sourceFacts.filter(
      _.category == BendSymbolCategory.Definition
    )
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
    laws.foldLeft(Map.empty[BendSourceHandle, BendSourceDeclarationFact]) {
      case (linked, law) =>
        val matchingAliases = validImports
          .filter(_.target.contains(law.handle.file))
          .flatMap(edge =>
            edge.importLine.alias.map(alias => edge.from -> alias)
          )
        val matches = definitions.filter { fill =>
          val sameSource = fill.handle.file == law.handle.file &&
            fill.name == law.name
          val importedAlias = matchingAliases.exists { case (from, alias) =>
            from == fill.handle.file && fill.name == s"$alias.${law.name}"
          }
          val comparable = fill.site.copy(name = law.name)
          (sameSource || importedAlias) &&
          BendLawDeclarations.isFill(
            law.site,
            comparable,
            requireOrder = fill.handle.file == law.handle.file
          )
        }
        linked ++ matches.map(_.handle -> law)
    }

  /** Graph files that can contribute root-visible law/fill relationships. */
  def relevantSourceIds(graph: BendLoadedGraph): Set[FileId] =
    val importedBaseSources = graph.edges
      .filter(_.importLine.spelling == "Base")
      .flatMap(_.target)
      .toSet
    graph.files
      .filter(file =>
        file.source.id == graph.root || file.namespace.nonEmpty ||
          importedBaseSources.contains(file.source.id)
      )
      .map(_.source.id)
      .toSet
