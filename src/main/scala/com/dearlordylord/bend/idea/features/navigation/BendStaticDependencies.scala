package com.dearlordylord.bend.idea.features.navigation

import com.dearlordylord.bend.idea.symbols.api.{
  BendImportedSymbolCatalog,
  BendNavigationResolution,
  BendPhysicalTargets,
  BendSourceApplications,
  BendSourceResolution,
  BendSourceSymbols,
  BendSnapshotAwareReferenceFactory
}
import com.dearlordylord.bend.idea.syntax.BendLanguage
import com.dearlordylord.bend.idea.syntax.psi.{
  BendDeclaration,
  BendDefinition,
  BendLaw,
  BendReferenceElement
}
import com.dearlordylord.bend.idea.workspace.api.{
  BendImportLines,
  BendLoadingConfiguration,
  BendWorkspacePaths
}
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.application.ReadAction
import com.intellij.psi.{
  PsiElement,
  PsiDocumentManager,
  PsiFile,
  PsiManager,
  SmartPsiElementPointer,
  SmartPointerManager
}
import com.intellij.psi.util.{PsiModificationTracker, PsiTreeUtil}

enum BendDependencyKind:
  case Import, ImportedBy, Call, CalledBy, UnknownCall, UnknownImport

final case class BendStaticDependency(
    kind: BendDependencyKind,
    label: String,
    navigation: SmartPsiElementPointer[PsiElement]
):
  override def toString: String = label

final case class BendStaticDependencyReport(
    dependencies: List[BendStaticDependency],
    unresolvedNamedCalls: Int,
    skippedLargeSources: Int,
    sourceModificationCount: Long,
    loadingConfigurationRevision: Long
):
  val coverage: String =
    s"Static source view: explicit imports and statically resolved named calls. " +
      s"$unresolvedNamedCalls named calls are unresolved or dynamic; " +
      s"$skippedLargeSources large sources were skipped. Higher-order calls " +
      "and implicit compiler sugar may be absent. This is not a complete runtime call graph."

/** One-hop dependency inspection based on current PSI references and source
  * identities. The bounded inventory is scanned once; cycles are never
  * recursively expanded.
  */
object BendStaticDependencies:
  private val MaxFiles = 512
  private val MaxSourceLength = 1024 * 1024

  private final case class DeclarationKey(path: String, nameOffset: Int)
  private final case class CallEdge(
      from: PsiFile,
      owner: Option[BendDeclaration],
      usage: BendReferenceElement,
      target: BendDeclaration,
      targetKey: DeclarationKey,
      name: String
  )
  private final case class SourceEdges(
      calls: List[
        Either[
          (PsiFile, String, BendReferenceElement, Option[BendDeclaration]),
          CallEdge
        ]
      ],
      imports: List[ImportEdge]
  )
  private final case class ImportEdge(
      from: PsiFile,
      target: Option[PsiFile],
      aliases: Set[String],
      navigation: PsiElement,
      targetPath: Option[String],
      spelling: String
  )
  private final case class DependencyCandidate(
      kind: BendDependencyKind,
      label: String,
      navigation: PsiElement
  )

  def inspect(
      selected: PsiElement
  ): Either[String, BendStaticDependencyReport] =
    val selection = ReadAction.compute(() => {
      val selectedFile = selected match
        case file: PsiFile => file
        case element       => element.getContainingFile
      if selectedFile == null ||
        selectedFile.getLanguage != BendLanguage.instance
      then Left("Choose a Bend source file or declaration.")
      else
        val selectedDeclaration = selected match
          case declaration: BendDeclaration => Some(declaration)
          case other                        =>
            Option(
              PsiTreeUtil.getParentOfType(
                other,
                classOf[BendDeclaration],
                false
              )
            )
        val selectedRange = selectedDeclaration.map { declaration =>
          val range = declaration.getTextRange
          (range.getStartOffset, range.getEndOffset)
        }
        Right(
          (
            selectedFile.getProject,
            selectedFile,
            pathIdentity(selectedFile),
            selectedRange,
            selectedDeclaration.map(declarationKey),
            PsiModificationTracker
              .getInstance(selectedFile.getProject)
              .getModificationCount
          )
        )
    })
    selection.flatMap {
      case (
            project,
            selectedFile,
            selectedPath,
            selectedRange,
            selectedKey,
            sourceModificationCount
          ) =>
        val inventory = project
          .getService(classOf[BendWorkspacePaths])
          .filesWithExtension("bend", MaxFiles)
        inventory.flatMap { paths =>
          val (sources, skipped, bounded) = ReadAction.compute(() => {
            val roots =
              ProjectRootManager.getInstance(project).getContentRoots.toList
            val indexed = paths
              .flatMap { path =>
                ProgressManager.checkCanceled()
                virtualFile(path, roots)
              }
              .flatMap(vf =>
                Option(PsiManager.getInstance(project).findFile(vf))
              )
            val all = (List(selectedFile) ++ indexed)
              .filter(file =>
                file.isValid && file.getLanguage == BendLanguage.instance
              )
              .distinctBy(pathIdentity)
            val tooLarge = all.filter(_.getTextLength > MaxSourceLength)
            (all, tooLarge.size, all.filter(_.getTextLength <= MaxSourceLength))
          })
          val configuration = project.getService(
            classOf[BendLoadingConfiguration]
          )
          val catalog =
            project.getService(classOf[BendImportedSymbolCatalog])
          val referenceFactory =
            project.getService(classOf[BendSnapshotAwareReferenceFactory])
          val loadingConfiguration = configuration.snapshot
          val edgeResults = bounded.map { file =>
            ProgressManager.checkCanceled()
            val snapshot =
              catalog.navigationSnapshot(
                file,
                loadingConfiguration.baseSource,
                loadingConfiguration.packageCache,
                () => isProgressCanceled
              )
            ProgressManager.checkCanceled()
            ReadAction.compute(() =>
              if !file.isValid ||
                file.getModificationStamp != snapshot.revision
              then None
              else
                Some(
                  SourceEdges(
                    callEdges(file, snapshot, referenceFactory),
                    importEdges(file, snapshot.graph)
                  )
                )
            )
          }
          if edgeResults.exists(_.isEmpty) then
            Left("A Bend source changed during inspection. Please retry.")
          else
            val analyses = edgeResults.flatten
            val calls = analyses.flatMap(_.calls)
            val imports = analyses.flatMap(_.imports)
            val unresolvedCalls = calls.collect { case Left(_) => () }.size
            ReadAction.compute(() =>
              if PsiDocumentManager.getInstance(project).hasUncommitedDocuments
              then
                Left(
                  "Open documents changed during inspection. Please retry."
                )
              else if PsiModificationTracker
                  .getInstance(project)
                  .getModificationCount != sourceModificationCount
              then Left("Bend sources changed during inspection. Please retry.")
              else if configuration.configurationRevision != loadingConfiguration.configurationRevision
              then
                Left(
                  "Bend loading settings changed during inspection. Please retry."
                )
              else
                val resolvedCalls = calls.collect { case Right(edge) => edge }
                val outgoingCalls = resolvedCalls.filter(edge =>
                  pathIdentity(edge.from) == selectedPath &&
                    selectedRange.forall { case (start, end) =>
                      val offset = edge.usage.getTextOffset
                      offset >= start && offset < end
                    }
                )
                val incomingCalls = selectedKey.toList.flatMap(key =>
                  resolvedCalls.filter(_.targetKey == key)
                ) ++ (if selectedKey.isEmpty then
                        resolvedCalls.filter(_.targetKey.path == selectedPath)
                      else Nil)
                val unresolvedOutgoing = calls.collect {
                  case Left((file, name, reference, owner))
                      if pathIdentity(file) == selectedPath &&
                        selectedRange.forall { case (start, end) =>
                          val offset = reference.getTextOffset
                          offset >= start && offset < end
                        } =>
                    val context =
                      owner.map(_.getName + " calls ").getOrElse("Call ")
                    DependencyCandidate(
                      BendDependencyKind.UnknownCall,
                      context + name + " (unresolved or dynamic)",
                      reference
                    )
                }

                val outgoingImports = imports
                  .filter(edge =>
                    pathIdentity(edge.from) == selectedPath &&
                      edge.target.nonEmpty
                  )
                  .groupBy(_.targetPath.get)
                  .values
                  .map { edges =>
                    val first = edges.head
                    val aliases = edges.flatMap(_.aliases).distinct.sorted
                    val via =
                      if aliases.isEmpty then ""
                      else s" via ${aliases.mkString(", ")}"
                    DependencyCandidate(
                      BendDependencyKind.Import,
                      s"Imports ${fileName(first.target.get)}$via",
                      first.target.get
                    )
                  }
                  .toList
                val incomingImports = imports
                  .filter(_.targetPath.contains(selectedPath))
                  .groupBy(edge => pathIdentity(edge.from))
                  .values
                  .map { edges =>
                    val first = edges.head
                    val aliases = edges.flatMap(_.aliases).distinct.sorted
                    val via =
                      if aliases.isEmpty then ""
                      else s" via ${aliases.mkString(", ")}"
                    DependencyCandidate(
                      BendDependencyKind.ImportedBy,
                      s"Imported by ${fileName(first.from)}$via",
                      first.navigation
                    )
                  }
                  .toList
                val unresolvedOutgoingImports = imports
                  .filter(edge =>
                    pathIdentity(
                      edge.from
                    ) == selectedPath && edge.target.isEmpty
                  )
                  .map(edge =>
                    DependencyCandidate(
                      BendDependencyKind.UnknownImport,
                      s"Unresolved import ${edge.spelling}",
                      edge.navigation
                    )
                  )

                val callDependencies = outgoingCalls.map { edge =>
                  DependencyCandidate(
                    BendDependencyKind.Call,
                    s"${ownerName(edge.owner)} calls ${edge.target.getName}",
                    edge.target.getNameIdentifier
                  )
                }
                val callerDependencies = incomingCalls.map { edge =>
                  DependencyCandidate(
                    BendDependencyKind.CalledBy,
                    s"${ownerName(edge.owner)} calls ${edge.target.getName}",
                    edge.usage
                  )
                }
                val candidates =
                  (outgoingImports ++ incomingImports ++ callDependencies ++
                    callerDependencies ++ unresolvedOutgoing ++
                    unresolvedOutgoingImports)
                    .distinctBy(candidate =>
                      (
                        candidate.kind,
                        candidate.label,
                        pathIdentity(candidate.navigation.getContainingFile),
                        candidate.navigation.getTextOffset
                      )
                    )
                    .sortBy(candidate =>
                      (candidate.kind.ordinal, candidate.label)
                    )
                if configuration.configurationRevision != loadingConfiguration.configurationRevision
                then
                  Left(
                    "Bend loading settings changed during inspection. Please retry."
                  )
                else
                  val pointers = SmartPointerManager.getInstance(project)
                  Right(
                    BendStaticDependencyReport(
                      candidates.map(candidate =>
                        BendStaticDependency(
                          candidate.kind,
                          candidate.label,
                          pointers.createSmartPsiElementPointer(
                            candidate.navigation
                          )
                        )
                      ),
                      unresolvedCalls,
                      skipped,
                      sourceModificationCount,
                      loadingConfiguration.configurationRevision
                    )
                  )
            )
        }
    }

  private def callEdges(
      file: PsiFile,
      snapshot: com.dearlordylord.bend.idea.symbols.api.BendSourceNavigationSnapshot,
      referenceFactory: BendSnapshotAwareReferenceFactory
  ): List[
    Either[
      (PsiFile, String, BendReferenceElement, Option[BendDeclaration]),
      CallEdge
    ]
  ] =
    val applications = BendSourceApplications.namedCalls(file)
    if applications.isEmpty then Nil
    else
      applications.flatMap { application =>
        ProgressManager.checkCanceled()
        val leaf = file.findElementAt(application.calleeFrom)
        val reference =
          if leaf == null then null
          else
            PsiTreeUtil.getParentOfType(
              leaf,
              classOf[BendReferenceElement],
              false
            )
        if reference == null ||
          reference.getTextOffset != application.calleeFrom
        then Nil
        else
          val owner = Option(
            PsiTreeUtil.getParentOfType(
              reference,
              classOf[BendDeclaration],
              false
            )
          )
          val resolution = referenceFactory
            .referencesFor(reference, snapshot)
            .find(_.semanticSpelling == application.callee)
            .map(_.resolveAgainst(snapshot))
            .getOrElse(
              BendNavigationResolution(BendSourceResolution.Unresolved, false)
            )
          val resolved = resolution.target match
            case BendSourceResolution.Resolved(symbol) =>
              physicalDeclaration(file, symbol).toList
            case BendSourceResolution.Ambiguous(symbols) =>
              symbols.flatMap(symbol => physicalDeclaration(file, symbol))
            case _ => Nil
          val targets = resolved
            .flatMap(declaration)
            .filter(target =>
              target.isInstanceOf[BendDefinition] || target
                .isInstanceOf[BendLaw]
            )
            .distinctBy(declarationKey)
          targets match
            case target :: Nil =>
              Right(
                CallEdge(
                  file,
                  owner,
                  reference,
                  target,
                  declarationKey(target),
                  application.callee
                )
              ) :: Nil
            case _ =>
              Left((file, application.callee, reference, owner)) :: Nil
      }

  private def importEdges(
      file: PsiFile,
      graph: com.dearlordylord.bend.idea.workspace.model.BendLoadedGraph
  ): List[ImportEdge] =
    val source = file.getText
    val sourceId = BendSourceSymbols.fileId(file)
    val lines = graph
      .source(sourceId)
      .map(_.imports)
      .getOrElse(BendImportLines.parse(source))
    val edges = graph.edges.filter(_.from == sourceId)
    lines.flatMap { line =>
      ProgressManager.checkCanceled()
      BendImportLines.pathRange(source, line).toList.map { case (start, _) =>
        val edge = edges.find(_.importLine.offset == line.offset)
        val target = edge
          .flatMap(_.target)
          .flatMap(BendPhysicalTargets.file(file.getProject, _))
        val navigation = Option(file.findElementAt(start)).getOrElse(file)
        ImportEdge(
          file,
          target,
          line.alias.toSet,
          navigation,
          target.map(pathIdentity).orElse(edge.map(_.requestedPath)),
          line.spelling
        )
      }
    }

  private def physicalDeclaration(
      source: PsiFile,
      symbol: com.dearlordylord.bend.idea.symbols.api.BendSourceSymbol
  ): Option[PsiElement] =
    val targetFile =
      if BendSourceSymbols.fileId(source) == symbol.handle.file then
        Some(source)
      else BendPhysicalTargets.file(source.getProject, symbol.handle.file)
    targetFile.flatMap(file => BendPhysicalTargets.declaration(file, symbol))

  private def isProgressCanceled: Boolean =
    Option(ProgressManager.getInstance().getProgressIndicator)
      .exists(_.isCanceled)

  private def declaration(element: PsiElement): Option[BendDeclaration] =
    element match
      case value: BendDeclaration => Some(value)
      case other                  =>
        Option(
          PsiTreeUtil.getParentOfType(other, classOf[BendDeclaration], false)
        )

  private def declarationKey(declaration: BendDeclaration): DeclarationKey =
    DeclarationKey(
      pathIdentity(declaration.getContainingFile),
      declaration.getNameIdentifier.getTextOffset
    )

  private def ownerName(owner: Option[BendDeclaration]): String =
    owner.map(_.getName).getOrElse("Top-level source")

  private def fileName(file: PsiFile): String =
    Option(file.getVirtualFile).map(_.getName).getOrElse(file.getName)

  private def pathIdentity(file: PsiFile): String =
    Option(file)
      .flatMap(value => Option(value.getVirtualFile))
      .map(virtual =>
        Option(virtual.getCanonicalPath).getOrElse(virtual.getPath)
      )
      .getOrElse(file.getName)

  private def virtualFile(
      path: String,
      roots: List[com.intellij.openapi.vfs.VirtualFile]
  ): Option[com.intellij.openapi.vfs.VirtualFile] =
    Option(LocalFileSystem.getInstance().findFileByPath(path))
      .filter(_.isValid)
      .orElse {
        roots.iterator
          .flatMap { root =>
            val base = root.getPath.stripSuffix("/")
            if path == base then Some(root)
            else if path.startsWith(base + "/") then
              Option(root.findFileByRelativePath(path.drop(base.length + 1)))
            else None
          }
          .find(_.isValid)
      }
