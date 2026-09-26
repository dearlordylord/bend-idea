package com.dearlordylord.bend.idea.features.proofs

import com.dearlordylord.bend.idea.features.templates.api.BendSnippets
import com.dearlordylord.bend.idea.model.FileId
import com.dearlordylord.bend.idea.symbols.api.*
import com.dearlordylord.bend.idea.syntax.BendLanguage
import com.dearlordylord.bend.idea.syntax.psi.{
  BendDeclaration,
  BendLaw,
  BendSourceParameter
}
import com.dearlordylord.bend.idea.workspace.api.{
  BendImportLines,
  BendImportPaths,
  BendLoadingConfiguration
}
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.{
  PsiDocumentManager,
  PsiFile,
  PsiFileFactory,
  SmartPsiElementPointer,
  SmartPointerManager
}
import com.intellij.psi.util.PsiModificationTracker

final case class BendProofFillTarget(
    path: String,
    name: String,
    root: SmartPsiElementPointer[PsiFile],
    lawHandle: BendSourceHandle,
    lawName: String,
    parameters: List[BendSourceParameter],
    lawSource: SmartPsiElementPointer[PsiFile],
    rootRevision: BendProofSourceRevision,
    lawRevision: BendProofSourceRevision,
    sourceModificationCount: Long,
    loadingConfigurationRevision: Long,
    loadingPaths: (String, String)
):
  override def toString: String = s"def $name — $path"

final case class BendProofSourceRevision(
    text: String,
    documentStamp: Long,
    virtualFileStamp: Long
)

final case class BendProofFillInsertion(
    file: VirtualFile,
    placeholderStart: Int,
    placeholderEnd: Int
)

final case class BendProofFillSearch(
    targets: List[BendProofFillTarget],
    cappedRootPaths: List[String],
    existing: List[BendProofExistingFill] = Nil
):
  def sourceInventoryCapped: Boolean = cappedRootPaths.nonEmpty

final case class BendProofExistingFill(
    path: String,
    name: String,
    declaration: SmartPsiElementPointer[BendDeclaration]
):
  override def toString: String = s"def $name — $path"

/** Root-aware fill planning and one-command source insertion. */
object BendProofFillGenerator:
  private val ValidQualifiedName =
    "[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*".r

  /** Complete the conventional sibling from a law source. This path is used
    * only when root-aware discovery found no imported target or existing fill.
    * It never treats a copied law as the selected source law.
    */
  private[proofs] def insertConventional(
      project: Project,
      law: SmartPsiElementPointer[BendLaw],
      expectedModificationCount: Long
  ): Either[String, BendProofFillInsertion] =
    WriteCommandAction
      .writeCommandAction(project)
      .withName("Generate Bend Law Fill")
      .compute[Either[String, BendProofFillInsertion], RuntimeException](() =>
        Option
          .when(
            PsiModificationTracker
              .getInstance(project)
              .getModificationCount == expectedModificationCount
          )(law.getElement)
          .flatMap(Option(_))
          .filter(_.isValid) match
          case None =>
            Left("The selected law changed; generate the fill again")
          case Some(selectedLaw) =>
            val source = selectedLaw.getContainingFile
            val sourceVirtual = Option(source.getVirtualFile)
            val directory = Option(source.getContainingDirectory)
            if source.getName != "LAWS.bend" || sourceVirtual.isEmpty ||
              directory.isEmpty
            then Left("Select a proof root that imports this law source")
            else if !sourceVirtual.get.isValid ||
              !directory.get.isWritable
            then Left("The sibling proof directory is read-only")
            else
              val manager = PsiDocumentManager.getInstance(project)
              val sourceDocument = manager.getDocument(source)
              if sourceDocument != null && !manager
                  .isCommitted(sourceDocument)
              then
                Left(
                  "The law source is still changing; generate the fill again"
                )
              else
                BendSourceSymbols
                  .declarations(source)
                  .find(symbol =>
                    symbol.category == BendSymbolCategory.Law &&
                      symbol.handle.nameOffset ==
                      selectedLaw.getNameIdentifier.getTextOffset
                  ) match
                  case None =>
                    Left("The selected law changed; generate the fill again")
                  case Some(symbol) =>
                    val root = Option(directory.get.findFile("PROOF.bend"))
                    val rootPath =
                      sourceVirtual.get.getParent.getPath + "/PROOF.bend"
                    if root.exists(file => !writable(file)) then
                      Left("The sibling PROOF.bend is read-only")
                    else if root
                        .exists(_.getLanguage != BendLanguage.instance)
                    then Left("The sibling PROOF.bend is not a Bend source")
                    else
                      val document =
                        root
                          .flatMap(file => Option(manager.getDocument(file)))
                      if root.nonEmpty && document.isEmpty then
                        Left("Could not open the sibling PROOF.bend document")
                      else if document
                          .exists(doc => !manager.isCommitted(doc))
                      then
                        Left(
                          "The proof root is still changing; generate the fill again"
                        )
                      else
                        val existingText = document.fold("")(_.getText)
                        val imports = BendImportLines.parse(existingText)
                        val sourcePath = sourceVirtual.get.getPath
                        val packageCache = project
                          .getService(classOf[BendLoadingConfiguration])
                          .paths
                          ._2
                        val alreadyImported = imports.exists(imp =>
                          BendImportPaths.target(
                            rootPath,
                            packageCache,
                            imp.spelling
                          ) == sourcePath
                        )
                        if alreadyImported then
                          Left(
                            "This proof root already imports LAWS.bend, but the selected law is not a new fill target; check its import and existing definitions"
                          )
                        else
                          val definitions = root.toList.flatMap(file =>
                            BendSourceSymbols
                              .declarations(file)
                              .filter(
                                _.category == BendSymbolCategory.Definition
                              )
                          )
                          val orphanFills = definitions.filter(entry =>
                            entry.name.matches(
                              s"Laws[0-9]*\\.${java.util.regex.Pattern.quote(symbol.name)}"
                            ) &&
                              !imports
                                .flatMap(_.alias)
                                .contains(
                                  entry.name.takeWhile(_ != '.')
                                )
                          )
                          if orphanFills.nonEmpty then
                            Left(
                              "PROOF.bend contains a similarly named definition without an import of LAWS.bend; link the intended source explicitly before generating another fill"
                            )
                          else
                            val used = imports.flatMap(_.alias).toSet ++
                              root.toList.flatMap(file =>
                                BendSourceSymbols
                                  .declarations(file)
                                  .map(_.name.takeWhile(_ != '.'))
                              )
                            val alias = Iterator
                              .from(1)
                              .map(index =>
                                if index == 1 then "Laws"
                                else s"Laws$index"
                              )
                              .find(name => !used(name))
                              .get
                            val importText = s"import ./LAWS.bend as $alias\n"
                            val afterImport = importText + existingText
                            val rendered = BendSnippets.renderLawFill(
                              s"$alias.${symbol.name}",
                              symbol.signature.parameters
                            )
                            val separatorText =
                              if afterImport.endsWith("\n\n") then ""
                              else if afterImport.endsWith("\n") then "\n"
                              else "\n\n"
                            val fullText =
                              afterImport + separatorText + rendered
                            val placeholder =
                              afterImport.length + separatorText.length +
                                rendered.indexOf("?TODO")
                            val created = root match
                              case Some(existing) =>
                                document.get.insertString(0, importText)
                                document.get.insertString(
                                  document.get.getTextLength,
                                  separatorText + rendered
                                )
                                manager.commitDocument(document.get)
                                existing
                              case None =>
                                val pending = PsiFileFactory
                                  .getInstance(project)
                                  .createFileFromText(
                                    "PROOF.bend",
                                    BendLanguage.instance,
                                    fullText
                                  )
                                directory.get.add(pending) match
                                  case file: PsiFile => file
                                  case _             =>
                                    throw new IllegalStateException(
                                      "Creating PROOF.bend did not return a PSI file"
                                    )
                            Option(created.getVirtualFile) match
                              case None =>
                                Left("Could not open the created PROOF.bend")
                              case Some(file) =>
                                Right(
                                  BendProofFillInsertion(
                                    file,
                                    placeholder,
                                    placeholder + "?TODO".length
                                  )
                                )
      )

  private final case class LawCapture(
      project: Project,
      source: SmartPsiElementPointer[PsiFile],
      handle: BendSourceHandle,
      name: String,
      parameters: List[BendSourceParameter],
      sourceRevision: BendProofSourceRevision,
      sourceModificationCount: Long,
      loadingConfigurationRevision: Long,
      loadingPaths: (String, String)
  )

  private final case class LawInputRevision(
      sourceModificationCount: Long,
      loadingConfigurationRevision: Long
  )

  private[proofs] def candidates(
      law: BendLaw,
      rootPaths: List[String]
  ): Option[BendProofFillSearch] =
    candidates(law, rootPaths, () => false)

  private[proofs] def candidates(
      law: SmartPsiElementPointer[BendLaw],
      rootPaths: List[String],
      canceled: () => Boolean
  ): Option[BendProofFillSearch] =
    candidates(law, rootPaths, canceled, None)

  private def candidates(
      law: SmartPsiElementPointer[BendLaw],
      rootPaths: List[String],
      canceled: () => Boolean,
      expectedRevision: Option[LawInputRevision]
  ): Option[BendProofFillSearch] =
    ReadAction
      .compute(() => Option(law.getElement))
      .flatMap(candidates(_, rootPaths, canceled, expectedRevision))

  /** Discover candidate fills on a background task. Each PSI projection uses a
    * short read action; graph loading occurs between those actions and observes
    * cancellation.
    */
  private[proofs] def candidates(
      law: BendLaw,
      rootPaths: List[String],
      canceled: () => Boolean
  ): Option[BendProofFillSearch] =
    candidates(law, rootPaths, canceled, None)

  private def candidates(
      law: BendLaw,
      rootPaths: List[String],
      canceled: () => Boolean,
      expectedRevision: Option[LawInputRevision]
  ): Option[BendProofFillSearch] =
    captureLaw(law, expectedRevision).flatMap(captured =>
      discoverCandidates(captured, rootPaths, canceled)
    )

  private def discoverCandidates(
      captured: LawCapture,
      rootPaths: List[String],
      canceled: () => Boolean
  ): Option[BendProofFillSearch] =
    val catalog =
      captured.project.getService(classOf[BendImportedSymbolCatalog])
    val result = scala.collection.mutable.ListBuffer.empty[BendProofFillTarget]
    val existing =
      scala.collection.mutable.ListBuffer.empty[BendProofExistingFill]
    val cappedRoots = scala.collection.mutable.ListBuffer.empty[String]
    val paths = rootPaths.iterator
    while paths.hasNext && !canceled() do
      val path = paths.next()
      val root = ReadAction.compute(() =>
        if !inputsCurrent(captured) then None
        else rootFile(captured.project, captured.source, path).filter(writable)
      )
      root match
        case None           => ()
        case Some(rootFile) =>
          val snapshot = catalog.navigationSnapshot(
            rootFile,
            captured.loadingPaths._1,
            captured.loadingPaths._2,
            canceled
          )
          if canceled() then return None
          if snapshot.graph.sourceInventoryCapped then cappedRoots += path
          val targets = ReadAction.compute(() =>
            if !inputsCurrent(captured) || !rootFile.isValid ||
              rootFile.getModificationStamp != snapshot.revision
            then None
            else
              val (_, visible) = catalog.visibleWithNavigationSnapshot(
                snapshot,
                snapshot.sourceLength
              )
              val existingFills = BendSourceSymbols
                .declarations(rootFile)
                .filter(symbol =>
                  symbol.category == BendSymbolCategory.Definition &&
                    visible.exists { case (visibleName, lawSymbol) =>
                      visibleName == symbol.name &&
                      lawSymbol.category == BendSymbolCategory.Law &&
                      lawSymbol.handle == captured.handle
                    }
                )
              if existingFills.nonEmpty then
                existing ++= existingFills.map(symbol =>
                  BendProofExistingFill(
                    path,
                    symbol.name,
                    SmartPointerManager
                      .getInstance(captured.project)
                      .createSmartPsiElementPointer(symbol.declaration)
                  )
                )
                Some(Nil)
              else
                Some(
                  visible.collect {
                    case (visibleName, symbol)
                        if symbol.category == BendSymbolCategory.Law &&
                          symbol.handle == captured.handle &&
                          ValidQualifiedName.matches(visibleName) =>
                      BendProofFillTarget(
                        path,
                        visibleName,
                        SmartPointerManager
                          .getInstance(captured.project)
                          .createSmartPsiElementPointer(rootFile),
                        captured.handle,
                        captured.name,
                        captured.parameters,
                        captured.source,
                        revision(captured.project, rootFile),
                        captured.sourceRevision,
                        captured.sourceModificationCount,
                        captured.loadingConfigurationRevision,
                        captured.loadingPaths
                      )
                  }
                )
          )
          targets match
            case None         => return None
            case Some(values) => result ++= values
    if canceled() then None
    else
      Some(
        BendProofFillSearch(
          result.toList.distinctBy(target => (target.path, target.name)),
          cappedRoots.toList.distinct,
          existing.toList.distinctBy(fill => (fill.path, fill.name))
        )
      )

  /** Rebuild a selected candidate against current imports before insertion. The
    * final write command still checks source/configuration revisions to close
    * the race between this background validation and mutation.
    */
  private[proofs] def refreshCandidate(
      law: SmartPsiElementPointer[BendLaw],
      selected: BendProofFillTarget,
      canceled: () => Boolean
  ): Option[BendProofFillSearch] =
    candidates(
      law,
      List(selected.path),
      canceled,
      Some(
        LawInputRevision(
          selected.sourceModificationCount,
          selected.loadingConfigurationRevision
        )
      )
    )
      .map(search =>
        search.copy(
          targets = search.targets.filter(target =>
            target.path == selected.path && target.name == selected.name &&
              target.lawHandle == selected.lawHandle
          )
        )
      )

  /** Inserts at EOF as one undoable write command and returns the `?TODO` range
    * for the editor to select. This method performs only revision, writability
    * and collision checks; graph discovery belongs to the background planner.
    */
  def insert(
      project: Project,
      target: BendProofFillTarget
  ): Either[String, BendProofFillInsertion] =
    WriteCommandAction
      .writeCommandAction(project)
      .compute[Either[
        String,
        BendProofFillInsertion
      ], RuntimeException](() =>
        val root = Option(target.root.getElement)
        val source = Option(target.lawSource.getElement)
        (root, source) match
          case (None, _) => Left("The selected proof root is no longer open")
          case (_, None) => Left("The selected law source is no longer open")
          case (Some(rootFile), Some(sourceFile)) =>
            Option(rootFile.getVirtualFile) match
              case None => Left("The selected proof root has no writable file")
              case Some(file) if !writable(rootFile) =>
                Left("The selected proof root is read-only")
              case Some(file)
                  if !rootFile.isValid || file.getPath != target.path ||
                    !sourceFile.isValid ||
                    PsiModificationTracker
                      .getInstance(project)
                      .getModificationCount != target.sourceModificationCount ||
                    project
                      .getService(classOf[BendLoadingConfiguration])
                      .configurationRevision != target.loadingConfigurationRevision ||
                    !revisionMatches(project, rootFile, target.rootRevision) ||
                    !revisionMatches(project, sourceFile, target.lawRevision) ||
                    project
                      .getService(classOf[BendLoadingConfiguration])
                      .paths != target.loadingPaths =>
                Left("Source changed after planning; generate the fill again")
              case Some(file) if hasDefinition(rootFile, target.name) =>
                Left(
                  s"${target.name} already has a definition in this proof root"
                )
              case Some(file) =>
                Option(
                  PsiDocumentManager.getInstance(project).getDocument(rootFile)
                ) match
                  case None =>
                    Left("Could not open the selected proof root document")
                  case Some(document) =>
                    val rendered = BendSnippets.renderLawFill(
                      target.name,
                      target.parameters
                    )
                    val prefix = separator(document)
                    val placeholderStart =
                      document.getTextLength + prefix.length + rendered
                        .indexOf("?TODO")
                    document
                      .insertString(document.getTextLength, prefix + rendered)
                    PsiDocumentManager
                      .getInstance(project)
                      .commitDocument(document)
                    Right(
                      BendProofFillInsertion(
                        file,
                        placeholderStart,
                        placeholderStart + "?TODO".length
                      )
                    )
      )

  private def captureLaw(
      law: BendLaw,
      expectedRevision: Option[LawInputRevision]
  ): Option[LawCapture] =
    ReadAction.compute(() =>
      if law == null || !law.isValid then None
      else
        val sourceFile = law.getContainingFile
        val project = law.getProject
        val configuration = project
          .getService(classOf[BendLoadingConfiguration])
          .snapshot
        val sourceModificationCount = PsiModificationTracker
          .getInstance(project)
          .getModificationCount
        val currentRevision = LawInputRevision(
          sourceModificationCount,
          configuration.configurationRevision
        )
        val selected = BendSourceSymbols
          .declarations(sourceFile)
          .find(symbol =>
            symbol.category == BendSymbolCategory.Law &&
              symbol.handle.nameOffset == law.getNameIdentifier.getTextOffset
          )
        selected
          .filter(_ => expectedRevision.forall(_ == currentRevision))
          .map { symbol =>
            LawCapture(
              project,
              SmartPointerManager
                .getInstance(project)
                .createSmartPsiElementPointer(sourceFile),
              symbol.handle,
              symbol.name,
              symbol.signature.parameters,
              revision(project, sourceFile),
              sourceModificationCount,
              configuration.configurationRevision,
              configuration.paths
            )
          }
    )

  private def inputsCurrent(captured: LawCapture): Boolean =
    val project = captured.project
    !project.isDisposed &&
    PsiModificationTracker
      .getInstance(project)
      .getModificationCount == captured.sourceModificationCount &&
    project
      .getService(classOf[BendLoadingConfiguration])
      .configurationRevision == captured.loadingConfigurationRevision &&
    Option(captured.source.getElement).exists(source =>
      source.isValid && revisionMatches(
        project,
        source,
        captured.sourceRevision
      )
    )

  private def rootFile(
      project: Project,
      origin: SmartPsiElementPointer[PsiFile],
      path: String
  ): Option[PsiFile] =
    Option(origin.getElement)
      .filter(file => Option(file.getVirtualFile).exists(_.getPath == path))
      .orElse(
        BendPhysicalTargets.file(project, new FileId(path, canonical = false))
      )

  private def writable(file: PsiFile): Boolean =
    Option(file.getVirtualFile).exists(_.isWritable) && file.isWritable

  private def hasDefinition(root: PsiFile, name: String): Boolean =
    BendSourceSymbols
      .declarations(root)
      .exists(symbol =>
        symbol.category == BendSymbolCategory.Definition && symbol.name == name
      )

  private def revision(
      project: Project,
      file: PsiFile
  ): BendProofSourceRevision =
    val document = PsiDocumentManager.getInstance(project).getDocument(file)
    val sourceText = Option(document).map(_.getText).getOrElse(file.getText)
    val documentStamp = Option(document)
      .map(_.getModificationStamp)
      .getOrElse(file.getModificationStamp)
    val virtualStamp = Option(file.getVirtualFile)
      .map(_.getModificationStamp)
      .getOrElse(-1L)
    BendProofSourceRevision(sourceText, documentStamp, virtualStamp)

  private def revisionMatches(
      project: Project,
      file: PsiFile,
      expected: BendProofSourceRevision
  ): Boolean =
    file.isValid && revision(project, file) == expected

  private def separator(document: Document): String =
    val text = document.getText
    if text.isEmpty then ""
    else if text.endsWith("\n\n") then ""
    else if text.endsWith("\n") then "\n"
    else "\n\n"
