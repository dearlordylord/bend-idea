package com.dearlordylord.bend.idea.symbols.references

import com.dearlordylord.bend.idea.symbols.api.*
import com.dearlordylord.bend.idea.syntax.psi.{
  BendAlias,
  BendDeclaration,
  BendForeignPaths,
  BendName,
  BendReferenceElement
}
import com.dearlordylord.bend.idea.workspace.api.BendLoadingConfiguration
import com.intellij.openapi.util.TextRange
import com.intellij.patterns.PlatformPatterns.psiElement
import com.intellij.psi.*
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.ProcessingContext

/** Native references retain independent alias and member ranges on one dotted
  * token.
  */
final class BendReferenceContributor extends PsiReferenceContributor:
  override def registerReferenceProviders(
      registrar: PsiReferenceRegistrar
  ): Unit =
    registrar.registerReferenceProvider(
      psiElement(classOf[com.dearlordylord.bend.idea.syntax.BendFile]),
      new PsiReferenceProvider:
        override def getReferencesByElement(
            element: PsiElement,
            context: ProcessingContext
        ): Array[PsiReference] =
          val file = element match
            case psiFile: PsiFile => psiFile
            case _                => return PsiReference.EMPTY_ARRAY
          val source = file.getText
          val modulePaths =
            com.dearlordylord.bend.idea.workspace.api.BendImportLines
              .parse(source)
              .flatMap { imp =>
                com.dearlordylord.bend.idea.workspace.api.BendImportLines
                  .pathRange(source, imp)
                  .map { case (start, end) =>
                    new BendModulePathReference(
                      file,
                      new TextRange(start, end),
                      imp.offset
                    ): PsiReference
                  }
              }
          val foreignPaths = BendForeignPaths
            .in(file)
            .map(path => new BendForeignPathReference(file, path): PsiReference)
          (modulePaths.map(reference =>
            reference: PsiReference
          ) ++ foreignPaths).toArray
    )
    val provider = new PsiReferenceProvider:
      override def getReferencesByElement(
          element: PsiElement,
          context: ProcessingContext
      ): Array[PsiReference] =
        if !element.isInstanceOf[BendReferenceElement] && !element
            .isInstanceOf[BendName]
        then return PsiReference.EMPTY_ARRAY
        val file = element.getContainingFile
        if file == null || file.getLanguage != com.dearlordylord.bend.idea.syntax.BendLanguage.instance
        then return PsiReference.EMPTY_ARRAY
        val word = element.getText
        if !word.matches("[A-Za-z_][A-Za-z0-9_.]*") then
          return PsiReference.EMPTY_ARRAY
        val offset = element.getTextOffset
        val source = file.getText
        val lineStart = source.lastIndexOf('\n', offset - 1) + 1
        val before = source.substring(lineStart, offset).trim
        if before.startsWith("import ") || before.endsWith(" as") then
          return PsiReference.EMPTY_ARRAY
        val full = new BendNameReference(
          element,
          new TextRange(0, word.length),
          word,
          None
        )
        if element.isInstanceOf[BendName] then return Array(full)
        val dot = word.indexOf('.')
        if dot < 0 then Array(full)
        else
          val (basePath, packageCache) =
            file.getProject.getService(classOf[BendLoadingConfiguration]).paths
          val catalog =
            file.getProject.getService(classOf[BendImportedSymbolCatalog])
          val snapshot = catalog.navigationSnapshot(
            file,
            basePath,
            packageCache
          )
          file.getProject
            .getService(classOf[BendSnapshotAwareReferenceFactory])
            .referencesFor(element, snapshot)
            .map(reference => reference: PsiReference)
            .toArray
    registrar.registerReferenceProvider(
      psiElement(classOf[BendReferenceElement]),
      provider
    )
    registrar.registerReferenceProvider(psiElement(classOf[BendName]), provider)

/** `eligible` distinguishes a forward source location from an in-scope
  * reference.
  */
final class BendNameReference(
    element: PsiElement,
    range: TextRange,
    spelling: String,
    alias: Option[Boolean]
) extends PsiPolyVariantReferenceBase[PsiElement](element, range, true)
    with BendSnapshotAwareReference:
  override def semanticSpelling: String = spelling

  override def isReferenceTo(target: PsiElement): Boolean =
    val ownName = getElement match
      case name: BendName => Some(name)
      case _              => None
    val targetName = target match
      case declaration: BendDeclaration => Option(declaration.getNameIdentifier)
      case name: BendName               => Some(name)
      case _                            => None
    if ownName.nonEmpty && targetName.nonEmpty &&
      ownName.get.getTextOffset == targetName.get.getTextOffset &&
      BendSourceSymbols.fileId(ownName.get.getContainingFile) ==
        BendSourceSymbols.fileId(targetName.get.getContainingFile)
    then false
    else super.isReferenceTo(target)

  def eligible: Boolean =
    if alias.nonEmpty then true
    else navigationResolution.eligible

  override def multiResolve(incompleteCode: Boolean): Array[ResolveResult] =
    val targets = alias match
      case Some(_) => aliasTarget.toList
      case None    =>
        navigationResolution.target match
          case BendSourceResolution.Resolved(symbol) =>
            physicalDeclaration(symbol).toList
          case BendSourceResolution.ResolvedBinder(binding) =>
            Option(
              getElement.getContainingFile.findElementAt(
                binding.handle.nameOffset
              )
            )
              .flatMap(token =>
                Option(
                  PsiTreeUtil.getParentOfType(
                    token,
                    classOf[BendReferenceElement],
                    false
                  )
                )
              )
              .toList
          case BendSourceResolution.Ambiguous(candidates) =>
            candidates.flatMap(physicalDeclaration)
          case _ => Nil
    targets
      .map(target => new PsiElementResolveResult(target): ResolveResult)
      .toArray

  /** Resolve a lexical application using a captured source snapshot. Dependency
    * inspection uses this same native reference implementation so it does not
    * invent a second call-resolution policy or rebuild the import graph for
    * every call site.
    */
  override def resolveAgainst(
      snapshot: BendSourceNavigationSnapshot
  ): BendNavigationResolution =
    val element = getElement
    val file = element.getContainingFile
    val offset = element.getTextOffset
    val own = PsiTreeUtil.getParentOfType(element, classOf[BendName], false)
    val ownDeclaration = Option(own)
      .filter(name =>
        PsiTreeUtil.getParentOfType(name, classOf[BendDeclaration]) != null
      )
      .flatMap(name =>
        snapshot.declarations.find(symbol =>
          symbol.handle.nameOffset == name.getTextOffset &&
            symbol.name == spelling
        )
      )
    ownDeclaration match
      case Some(symbol) =>
        BendNavigationResolution(
          BendSourceResolution.Resolved(symbol),
          true
        )
      case None =>
        val category = BendSourceSymbols.referenceCategory(
          file,
          offset,
          spelling,
          snapshot.declarations,
          snapshot.logicalLaws
        )
        file.getProject
          .getService(classOf[BendImportedSymbolCatalog])
          .resolveForNavigation(snapshot, offset, spelling, category)

  override def handleElementRename(newElementName: String): PsiElement =
    val element = getElement
    val current = element.getText
    val range = getRangeInElement
    val updated = current.substring(0, range.getStartOffset) + newElementName +
      current.substring(range.getEndOffset)
    element match
      case reference: BendReferenceElement =>
        val sample = PsiFileFactory
          .getInstance(element.getProject)
          .createFileFromText(
            "rename.bend",
            com.dearlordylord.bend.idea.syntax.BendLanguage.instance,
            s"def rename():\n  $updated\n"
          )
        val replacement = PsiTreeUtil
          .findChildrenOfType(sample, classOf[BendReferenceElement])
          .stream()
          .filter(_.getText == updated)
          .findFirst()
          .orElse(null)
        if replacement == null then
          throw new com.intellij.util.IncorrectOperationException(
            "Cannot rename Bend reference"
          )
        element.replace(replacement)
      case name: BendName =>
        if PsiTreeUtil.getParentOfType(name, classOf[BendDeclaration]) == null
        then name
        else name.setName(newElementName)
      case _ =>
        throw new com.intellij.util.IncorrectOperationException(
          "Cannot rename Bend reference"
        )

  private def navigationResolution: BendNavigationResolution =
    val file = getElement.getContainingFile
    // A declaration name is an IntelliJ navigation origin in its own right.
    val own = PsiTreeUtil.getParentOfType(getElement, classOf[BendName], false)
    if own != null then
      val declaration =
        PsiTreeUtil.getParentOfType(own, classOf[BendDeclaration])
      if declaration != null then
        val symbol = BendSourceSymbols
          .declarations(file)
          .find(s =>
            s.handle.nameOffset == own.getTextOffset && s.name == spelling
          )
        if symbol.nonEmpty then
          return BendNavigationResolution(
            BendSourceResolution.Resolved(symbol.get),
            true
          )
    val (basePath, packageCache) =
      file.getProject.getService(classOf[BendLoadingConfiguration]).paths
    val catalog =
      file.getProject.getService(classOf[BendImportedSymbolCatalog])
    val snapshot = catalog.navigationSnapshot(file, basePath, packageCache)
    resolveAgainst(snapshot)

  private def aliasTarget: Option[PsiElement] =
    val file = getElement.getContainingFile
    val (basePath, packageCache) =
      file.getProject.getService(classOf[BendLoadingConfiguration]).paths
    val graph = file.getProject
      .getService(classOf[BendImportedSymbolCatalog])
      .loaded(file, basePath, packageCache)
    graph.edges.reverse
      .find(e => e.from == graph.root && e.importLine.alias.contains(spelling))
      .flatMap { edge =>
        val line = file.getText
          .substring(edge.importLine.offset)
          .takeWhile(c => c != '\n' && c != '#')
        val at = "\\bas\\s+([A-Za-z_][A-Za-z0-9_]*)".r
          .findAllMatchIn(line)
          .find(_.group(1) == spelling)
          .map(_.start(1))
        at.flatMap(index =>
          Option(file.findElementAt(edge.importLine.offset + index))
        ).flatMap(token =>
          Option(PsiTreeUtil.getParentOfType(token, classOf[BendAlias], false))
        )
      }

  private def physicalDeclaration(
      symbol: BendSourceSymbol
  ): Option[PsiElement] =
    val current = getElement.getContainingFile
    val targetFile =
      if symbol.handle.file == BendSourceSymbols.fileId(current) then
        Some(current)
      else BendPhysicalTargets.file(current.getProject, symbol.handle.file)
    targetFile.flatMap(file => BendPhysicalTargets.declaration(file, symbol))
