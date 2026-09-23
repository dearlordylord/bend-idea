package com.dearlordylord.bend.idea.symbols.references

import com.dearlordylord.bend.idea.symbols.api.*
import com.dearlordylord.bend.idea.syntax.psi.{BendAlias, BendDeclaration, BendName, BendReferenceElement}
import com.dearlordylord.bend.idea.workspace.api.BendLoadingConfiguration
import com.intellij.openapi.util.TextRange
import com.intellij.patterns.PlatformPatterns.psiElement
import com.intellij.psi.*
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.ProcessingContext

/** Native references retain independent alias and member ranges on one dotted token. */
final class BendReferenceContributor extends PsiReferenceContributor:
  override def registerReferenceProviders(registrar: PsiReferenceRegistrar): Unit =
    registrar.registerReferenceProvider(psiElement(classOf[com.dearlordylord.bend.idea.syntax.BendFile]),
      new PsiReferenceProvider:
        override def getReferencesByElement(element: PsiElement, context: ProcessingContext): Array[PsiReference] =
          val file = element.asInstanceOf[PsiFile]
          val source = file.getText
          com.dearlordylord.bend.idea.workspace.api.BendImportLines.parse(source).flatMap { imp =>
            com.dearlordylord.bend.idea.workspace.api.BendImportLines.pathRange(source, imp)
              .map { case (start, end) =>
                new BendModulePathReference(file, new TextRange(start, end), imp.offset): PsiReference
              }
          }.toArray)
    val provider = new PsiReferenceProvider:
      override def getReferencesByElement(element: PsiElement, context: ProcessingContext): Array[PsiReference] =
        if !element.isInstanceOf[BendReferenceElement] && !element.isInstanceOf[BendName] then
          return PsiReference.EMPTY_ARRAY
        val file = element.getContainingFile
        if file == null || file.getLanguage != com.dearlordylord.bend.idea.syntax.BendLanguage.instance then
          return PsiReference.EMPTY_ARRAY
        val word = element.getText
        if !word.matches("[A-Za-z_][A-Za-z0-9_.]*") then
          return PsiReference.EMPTY_ARRAY
        val offset = element.getTextOffset
        val source = file.getText
        val lineStart = source.lastIndexOf('\n', offset - 1) + 1
        val before = source.substring(lineStart, offset).trim
        if before.startsWith("import ") || before.endsWith(" as") then return PsiReference.EMPTY_ARRAY
        val full = new BendNameReference(element, new TextRange(0, word.length), word, None)
        if element.isInstanceOf[BendName] then return Array(full)
        val dot = word.indexOf('.')
        if dot < 0 then Array(full)
        else
          val alias = word.substring(0, dot)
          val (basePath, packageCache) = file.getProject.getService(classOf[BendLoadingConfiguration]).paths
          val catalog = file.getProject.getService(classOf[BendImportedSymbolCatalog])
          val resolution = catalog.resolveForNavigation(file, offset, word,
            basePath, packageCache)
          val aliases = catalog.loaded(file, basePath, packageCache).edges
            .filter(e => e.from == BendSourceSymbols.fileId(file) && e.importLine.alias.contains(alias))
          def aliasOwns(symbol: BendSourceSymbol): Boolean =
            aliases.exists(_.target.contains(symbol.handle.file))
          val resolvedThroughAlias = resolution.target match
            case BendSourceResolution.Resolved(symbol) => aliasOwns(symbol)
            case BendSourceResolution.Ambiguous(symbols) => symbols.nonEmpty && symbols.forall(aliasOwns)
            case _ => false
          if aliases.nonEmpty && (resolvedThroughAlias || resolution.target == BendSourceResolution.Unresolved) then
            val prefix = new BendNameReference(element, new TextRange(0, dot), alias, Some(true))
            if dot == word.length - 1 then Array(prefix)
            else Array(prefix,
              new BendNameReference(element, new TextRange(dot + 1, word.length), word, None))
          else Array(full)
    registrar.registerReferenceProvider(psiElement(classOf[BendReferenceElement]), provider)
    registrar.registerReferenceProvider(psiElement(classOf[BendName]), provider)

/** `eligible` distinguishes a forward source location from an in-scope reference. */
final class BendNameReference(element: PsiElement, range: TextRange, spelling: String,
    alias: Option[Boolean]) extends PsiPolyVariantReferenceBase[PsiElement](element, range, true):
  override def isReferenceTo(target: PsiElement): Boolean =
    val ownName = getElement match
      case name: BendName => Some(name)
      case _ => None
    val targetName = target match
      case declaration: BendDeclaration => Option(declaration.getNameIdentifier)
      case name: BendName => Some(name)
      case _ => None
    if ownName.nonEmpty && targetName.nonEmpty &&
        ownName.get.getTextOffset == targetName.get.getTextOffset &&
        BendSourceSymbols.fileId(ownName.get.getContainingFile) ==
          BendSourceSymbols.fileId(targetName.get.getContainingFile) then false
    else super.isReferenceTo(target)

  def eligible: Boolean =
    if alias.nonEmpty then true
    else navigationResolution.eligible

  override def multiResolve(incompleteCode: Boolean): Array[ResolveResult] =
    val targets = alias match
      case Some(_) => aliasTarget.toList
      case None => navigationResolution.target match
        case BendSourceResolution.Resolved(symbol) => physicalDeclaration(symbol).toList
        case BendSourceResolution.ResolvedBinder(binding) =>
          Option(getElement.getContainingFile.findElementAt(binding.handle.nameOffset))
            .flatMap(token => Option(PsiTreeUtil.getParentOfType(token, classOf[BendReferenceElement], false))).toList
        case BendSourceResolution.Ambiguous(candidates) => candidates.flatMap(physicalDeclaration)
        case _ => Nil
    targets.map(target => new PsiElementResolveResult(target): ResolveResult).toArray

  override def handleElementRename(newElementName: String): PsiElement =
    val element = getElement
    val current = element.getText
    val range = getRangeInElement
    val updated = current.substring(0, range.getStartOffset) + newElementName +
      current.substring(range.getEndOffset)
    element match
      case reference: BendReferenceElement =>
        val sample = PsiFileFactory.getInstance(element.getProject).createFileFromText(
          "rename.bend", com.dearlordylord.bend.idea.syntax.BendLanguage.instance,
          s"def rename():\n  $updated\n")
        val replacement = PsiTreeUtil.findChildrenOfType(sample, classOf[BendReferenceElement])
          .stream().filter(_.getText == updated).findFirst().orElse(null)
        if replacement == null then throw new com.intellij.util.IncorrectOperationException("Cannot rename Bend reference")
        element.replace(replacement)
      case name: BendName =>
        if PsiTreeUtil.getParentOfType(name, classOf[BendDeclaration]) == null then name
        else name.setName(newElementName)
      case _ => throw new com.intellij.util.IncorrectOperationException("Cannot rename Bend reference")

  private def navigationResolution: BendNavigationResolution =
    val file = getElement.getContainingFile
    val offset = getElement.getTextOffset
    // A declaration name is an IntelliJ navigation origin in its own right.
    val own = PsiTreeUtil.getParentOfType(getElement, classOf[BendName], false)
    if own != null then
      val declaration = PsiTreeUtil.getParentOfType(own, classOf[BendDeclaration])
      if declaration != null then
        val symbol = BendSourceSymbols.declarations(file).find(s =>
          s.handle.nameOffset == own.getTextOffset && s.name == spelling)
        if symbol.nonEmpty then return BendNavigationResolution(BendSourceResolution.Resolved(symbol.get), true)
    val (basePath, packageCache) = file.getProject.getService(classOf[BendLoadingConfiguration]).paths
    val category = BendSourceSymbols.referenceCategory(file, offset, spelling)
    file.getProject.getService(classOf[BendImportedSymbolCatalog])
      .resolveForNavigation(file, offset, spelling, basePath,
        packageCache, category)

  private def aliasTarget: Option[PsiElement] =
    val file = getElement.getContainingFile
    val (basePath, packageCache) = file.getProject.getService(classOf[BendLoadingConfiguration]).paths
    val graph = file.getProject.getService(classOf[BendImportedSymbolCatalog])
      .loaded(file, basePath, packageCache)
    graph.edges.reverse.find(e => e.from == graph.root && e.importLine.alias.contains(spelling))
      .flatMap { edge =>
        val line = file.getText.substring(edge.importLine.offset).takeWhile(c => c != '\n' && c != '#')
        val at = "\\bas\\s+([A-Za-z_][A-Za-z0-9_]*)".r.findAllMatchIn(line)
          .find(_.group(1) == spelling).map(_.start(1))
        at.flatMap(index => Option(file.findElementAt(edge.importLine.offset + index)))
          .flatMap(token => Option(PsiTreeUtil.getParentOfType(token, classOf[BendAlias], false)))
      }

  private def physicalDeclaration(symbol: BendSourceSymbol): Option[PsiElement] =
    val current = getElement.getContainingFile
    val targetFile = if symbol.handle.file == BendSourceSymbols.fileId(current) then Some(current)
    else BendPhysicalTargets.file(current.getProject, symbol.handle.file)
    targetFile.flatMap(file => BendPhysicalTargets.declaration(file, symbol))
