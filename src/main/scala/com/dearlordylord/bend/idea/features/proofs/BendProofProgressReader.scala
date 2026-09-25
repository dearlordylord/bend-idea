package com.dearlordylord.bend.idea.features.proofs

import com.dearlordylord.bend.idea.analysis.api.BendCheckService
import com.dearlordylord.bend.idea.symbols.api.{
  BendPhysicalTargets,
  BendSourceDocumentation,
  BendSourceSymbols,
  BendSymbolCategory
}
import com.dearlordylord.bend.idea.syntax.psi.{BendProofForm, BendProofSurface}
import com.dearlordylord.bend.idea.workspace.api.{
  BendLoadingConfiguration,
  BendSourceCatalog,
  BendWorkspaceGraph,
  BendWorkspacePaths
}
import com.intellij.openapi.project.Project
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.FileDocumentManager

/** Reads a root and its current imports through the shared workspace loader. */
class BendProofProgressReader(project: Project):
  private val RootLimit = 128
  private val EntryLimit = 2048
  private val SourceFileLimit = 256
  private val SourceCharacterLimit = 250000
  private val TokenLimitPerFile = 25000

  def roots(): BendProofRootInventory =
    val saved = project.getService(classOf[BendProofRootStore]).selectedPaths
    val discovered = project
      .getService(classOf[BendWorkspacePaths])
      .filesNamed("PROOF.bend", RootLimit)
    BendProofRootSelection.inventory(saved, discovered, RootLimit)

  def read(
      rootPath: String,
      canceled: () => Boolean
  ): Option[BendProofProgressSnapshot] =
    if canceled() then return None
    val catalog = project.getService(classOf[BendSourceCatalog])
    catalog.source(rootPath) match
      case None =>
        Some(
          BendProofProgressSnapshot(
            rootPath,
            "Root source unavailable",
            Nil
          )
        )
      case Some(root) =>
        val loadingConfiguration = project
          .getService(classOf[BendLoadingConfiguration])
          .snapshot
        val graph = project
          .getService(classOf[BendWorkspaceGraph])
          .load(
            root,
            loadingConfiguration.baseSource,
            loadingConfiguration.packageCache,
            canceled
          )
        if canceled() ||
          project
            .getService(classOf[BendLoadingConfiguration])
            .configurationRevision != loadingConfiguration.configurationRevision
        then return None
        val checkService = project.getService(classOf[BendCheckService])
        val checked = BendProofProgressModel.checkedStatus(
          checkService.status(root.id),
          checkService.result(root.id)
        )
        val sourceFiles = graph.files.take(SourceFileLimit)
        var limited =
          graph.sourceInventoryCapped || graph.files.size > SourceFileLimit
        val accepted =
          Set(BendSymbolCategory.Law, BendSymbolCategory.Definition)
        val declarations = scala.collection.mutable.ListBuffer.empty[
          com.dearlordylord.bend.idea.symbols.api.BendSourceDeclarationFact
        ]
        val sourceIterator = sourceFiles.iterator
        while sourceIterator.hasNext && declarations.size < EntryLimit &&
          !canceled()
        do
          val loaded = sourceIterator.next()
          val remaining = EntryLimit - declarations.size
          val scan = ReadAction.compute(() =>
            BendSourceSymbols
              .sourceDeclarationsBounded(
                project,
                loaded.source.id,
                loaded.source.text,
                remaining,
                SourceCharacterLimit,
                accepted,
                canceled
              )
              .map(result =>
                (
                  result.symbols.map(BendSourceSymbols.declarationFact),
                  result.truncated
                )
              )
          )
          scan.foreach { case (facts, truncated) =>
            declarations ++= facts
            if truncated then limited = true
          }
        if declarations.size == EntryLimit then limited = true
        if canceled() then return None

        val linkedFills = BendSourceDocumentation.linkedFills(
          graph,
          declarations.toList
        )
        val sourcesById =
          graph.files.map(file => file.source.id -> file.source).toMap
        val declarationEntries = declarations.toList.map { declaration =>
          val kind = if declaration.category == BendSymbolCategory.Law then
            BendProofInventoryKind.Law
          else if linkedFills.contains(declaration.handle) then
            BendProofInventoryKind.CandidateFill
          else BendProofInventoryKind.Definition
          val label = linkedFills.get(declaration.handle) match
            case Some(law) => s"${declaration.name} → ${law.name}"
            case None      => declaration.name
          val source = sourcesById.get(declaration.handle.file)
          BendProofInventoryEntry(
            kind,
            label,
            source.fold(rootPath)(_.path),
            declaration.handle.nameOffset,
            declaration.handle.file,
            source.fold(root.revision)(_.revision),
            loadingConfiguration.configurationRevision
          )
        }

        val holes = scala.collection.mutable.ListBuffer.empty[
          BendProofInventoryEntry
        ]
        val fileIterator = sourceFiles.iterator
        while fileIterator.hasNext && declarationEntries.size + holes.size < EntryLimit &&
          !canceled()
        do
          val loaded = fileIterator.next()
          val remaining = EntryLimit - declarationEntries.size - holes.size
          BendProofSurface.holesBounded(
            loaded.source.text,
            0,
            TokenLimitPerFile,
            remaining,
            canceled
          ) match
            case None       => ()
            case Some(scan) =>
              if scan.truncated then limited = true
              scan.forms
                .collect { case hole: BendProofForm.Hole => hole }
                .foreach { hole =>
                  if holes.size < remaining then
                    holes += BendProofInventoryEntry(
                      BendProofInventoryKind.Hole,
                      if hole.name.isEmpty then "?" else s"?${hole.name}",
                      loaded.source.path,
                      hole.from,
                      loaded.source.id,
                      loaded.source.revision,
                      loadingConfiguration.configurationRevision
                    )
                }
        if declarationEntries.size + holes.size == EntryLimit then
          limited = true
        if canceled() ||
          project
            .getService(classOf[BendLoadingConfiguration])
            .configurationRevision != loadingConfiguration.configurationRevision
        then return None
        val entries = (declarationEntries ++ holes)
          .sortBy(entry => (entry.path, entry.offset, entry.kind.ordinal))
          .take(EntryLimit)
        val inventoryLimited = limited
        val graphState =
          if graph.problems.isEmpty then ""
          else s"; ${graph.problems.size} loading issue(s)"
        Some(
          BendProofProgressSnapshot(
            rootPath,
            checked + graphState +
              (if inventoryLimited then "; inventory capped" else ""),
            entries
          )
        )

  /** Revalidate a displayed row against the current source before opening its
    * snapshot-local offset.
    */
  def navigationTarget(
      entry: BendProofInventoryEntry
  ): Option[BendNavigationTarget] =
    val configuration = project.getService(classOf[BendLoadingConfiguration])
    if configuration.configurationRevision != entry.loadingConfigurationRevision
    then None
    else
      project
        .getService(classOf[BendSourceCatalog])
        .source(entry.path)
        .filter(source =>
          source.id == entry.sourceId &&
            source.revision == entry.sourceRevision &&
            entry.offset >= 0 && entry.offset <= source.text.length
        )
        .flatMap(source =>
          ReadAction.compute(() =>
            if configuration.configurationRevision != entry.loadingConfigurationRevision
            then None
            else
              BendPhysicalTargets
                .file(project, source.id)
                .filter(_.isValid)
                .flatMap(file =>
                  Option(file.getVirtualFile).flatMap { virtual =>
                    val document = FileDocumentManager
                      .getInstance()
                      .getDocument(virtual)
                    val currentText =
                      if document == null then file.getText
                      else document.getText
                    Option.when(currentText == source.text)(
                      BendNavigationTarget(virtual, entry.offset)
                    )
                  }
                )
          )
        )
