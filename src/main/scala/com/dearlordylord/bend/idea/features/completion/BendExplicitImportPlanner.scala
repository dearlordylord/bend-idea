package com.dearlordylord.bend.idea.features.completion

import com.dearlordylord.bend.idea.symbols.api.{
  BendImportedSymbolCatalog,
  BendSourceSymbols,
  BendWorkspaceSymbolCandidate
}
import com.dearlordylord.bend.idea.syntax.lexer.BendWords
import com.dearlordylord.bend.idea.syntax.psi.BendReferenceElement
import com.dearlordylord.bend.idea.workspace.api.{
  BendBaseState,
  BendImportLines,
  BendLibrarySource,
  BendLoadingConfiguration,
  BendSourcePathEdits,
  BendWorkspaceGraph
}
import com.dearlordylord.bend.idea.workspace.model.BendSourceRecord
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.util.TextRange
import com.intellij.psi.{PsiDocumentManager, PsiFile}
import com.intellij.psi.util.PsiTreeUtil
import java.nio.file.Path
import scala.util.control.NonFatal

final case class BendExplicitImportPlan(
    sourceRevision: Long,
    expectedText: String,
    referenceRange: TextRange,
    replacementName: String,
    insertionOffset: Int,
    insertionText: String
)

final case class BendExplicitImportResult(
    referenceStart: Int,
    referenceEnd: Int
)

/** Plans one explicit, undoable source import and the chosen reference
  * spelling.
  */
object BendExplicitImportPlanner:
  def plan(
      file: PsiFile,
      reference: BendReferenceElement,
      candidate: BendWorkspaceSymbolCandidate
  ): Either[String, BendExplicitImportPlan] =
    val source = file.getText
    val name = reference.getText
    if !file.getName.endsWith(".bend") || file.getVirtualFile == null then
      return Left("Choose an editable Bend source file.")
    if !candidate.symbol.declaration.isValid || candidate.symbol.name != name
    then return Left("The selected workspace declaration is no longer current.")
    if candidate.isBase && candidate.path.isEmpty then
      return Left("The configured Bend Base source is unavailable.")
    if !candidate.isBase &&
      candidate.symbol.handle.file == BendSourceSymbols.fileId(file)
    then return Left("That declaration is already in this file.")
    val declaration = PsiTreeUtil.getParentOfType(
      reference,
      classOf[com.dearlordylord.bend.idea.syntax.psi.BendDeclaration]
    )
    if declaration != null && declaration.getNameIdentifier == reference
    then return Left("Place the caret on a Bend reference, not a declaration.")
    if reference.getReferences.exists(_.resolve() != null) then
      return Left("This Bend reference already resolves.")

    val project = file.getProject
    val catalog = project.getService(classOf[BendImportedSymbolCatalog])
    val (basePath, packageCache) =
      project.getService(classOf[BendLoadingConfiguration]).paths
    val graph = catalog.loaded(file, basePath, packageCache)
    val baseImported =
      graph.edges.exists(edge =>
        edge.from == graph.root && edge.importLine.spelling == "Base" &&
          graph.importTarget(graph.root, edge.importLine.offset).nonEmpty
      )
    val existingAlias =
      if candidate.isBase then None
      else catalog.aliasForTarget(graph, candidate.symbol.handle.file)
    val alias =
      if candidate.isBase then None
      else
        Some(
          existingAlias.getOrElse(
            BendExplicitImportPlanner.freshAlias(candidate, source)
          )
        )
    val addImport =
      if candidate.isBase then !baseImported
      else existingAlias.isEmpty
    val importLine =
      if candidate.isBase then "import Base"
      else
        candidate.path
          .flatMap(path =>
            BendExplicitImportPlanner.importSpelling(
              file.getVirtualFile.getPath,
              path,
              packageCache
            )
          )
          .map(path => "import " + path + " as " + alias.get)
          .getOrElse("")
    if importLine.isEmpty then
      return Left("The selected source has no safe import path.")
    val inserted = if addImport then
      val newline = if source.contains("\r\n") then "\r\n" else "\n"
      val insertionOffset =
        BendExplicitImportPlanner.importInsertionOffset(source)
      val prefix =
        if insertionOffset > 0 && source.charAt(insertionOffset - 1) != '\n'
        then newline
        else ""
      (insertionOffset, prefix + importLine + newline)
    else (0, "")
    val replacementName = alias.fold(name)(value => value + "." + name)
    val futureText =
      source.substring(0, reference.getTextOffset) +
        replacementName +
        source.substring(reference.getTextOffset + reference.getTextLength)
    val afterImport =
      futureText.substring(0, inserted._1) + inserted._2 +
        futureText.substring(inserted._1)
    if addImport then
      val record = BendSourceRecord(
        BendSourceSymbols.fileId(file),
        file.getVirtualFile.getPath,
        afterImport,
        file.getModificationStamp,
        BendImportLines.parse(afterImport)
      )
      val checked = project
        .getService(classOf[BendWorkspaceGraph])
        .load(
          record,
          basePath,
          packageCache
        )
      val expectedTarget =
        if candidate.isBase then
          project
            .getService(classOf[BendLibrarySource])
            .base(
              basePath,
              com.intellij.openapi.application.ApplicationManager.getApplication
                .getService(
                  classOf[
                    com.dearlordylord.bend.idea.toolchain.api.BendToolchainSettings
                  ]
                )
                .selection
                .configurationRevision
            ) match
            case BendBaseState.Available(base) =>
              Some(
                new com.dearlordylord.bend.idea.model.FileId(
                  base.identity,
                  true
                )
              )
            case BendBaseState.Missing(_) => None
        else Some(candidate.symbol.handle.file)
      val addedLine =
        BendImportLines
          .parse(afterImport)
          .find(
            _.spelling == (
              if candidate.isBase then "Base"
              else importLine.stripPrefix("import ").takeWhile(_ != ' ')
            )
          )
      val resolves = for
        line <- addedLine
        target <- checked.importTarget(checked.root, line.offset)
        expected <- expectedTarget
        if target == expected
      yield ()
      if resolves.isEmpty then
        return Left(
          "The selected source cannot be imported without a loading conflict."
        )
    Right(
      BendExplicitImportPlan(
        file.getModificationStamp,
        source,
        reference.getTextRange,
        replacementName,
        inserted._1,
        inserted._2
      )
    )

  def insert(
      project: com.intellij.openapi.project.Project,
      file: PsiFile,
      plan: BendExplicitImportPlan
  ): Either[String, BendExplicitImportResult] =
    val manager = PsiDocumentManager.getInstance(project)
    val document = manager.getDocument(file)
    if document == null then Left("The Bend source document is unavailable.")
    else if document.getText != plan.expectedText then
      Left("The Bend source changed while the import choice was open.")
    else if file.getModificationStamp != plan.sourceRevision then
      Left("The Bend source revision changed; choose the import again.")
    else
      var start = plan.referenceRange.getStartOffset
      var end = plan.referenceRange.getEndOffset
      WriteCommandAction
        .writeCommandAction(project, file)
        .withName("Import Bend symbol")
        .run(
          new com.intellij.util.ThrowableRunnable[RuntimeException]:
            override def run(): Unit =
              document.replaceString(start, end, plan.replacementName)
              end = start + plan.replacementName.length
              if plan.insertionText.nonEmpty then
                document.insertString(plan.insertionOffset, plan.insertionText)
                if plan.insertionOffset <= start then
                  start += plan.insertionText.length
                  end += plan.insertionText.length
              manager.commitDocument(document)
        )
      Right(BendExplicitImportResult(start, end))

  private def importInsertionOffset(source: String): Int =
    val imports = BendImportLines.parse(source)
    imports.lastOption match
      case None       => 0
      case Some(last) =>
        val lineEnd = source.indexOf('\n', last.offset)
        if lineEnd < 0 then source.length else lineEnd + 1

  private def freshAlias(
      candidate: BendWorkspaceSymbolCandidate,
      source: String
  ): String =
    val imported = BendImportLines.parse(source).flatMap(_.alias).toSet
    val raw = candidate.path
      .flatMap(path => Option(Path.of(path).getFileName))
      .map(_.toString.stripSuffix(".bend"))
      .getOrElse("Module")
    val cleaned = raw
      .split("[^A-Za-z0-9_]+")
      .filter(_.nonEmpty)
      .map(part => part.head.toUpper.toString + part.drop(1))
      .mkString
    val base =
      if cleaned.isEmpty then "Module"
      else if cleaned.head.isDigit then "Module" + cleaned
      else cleaned
    val safe =
      if BendWords.reserved(base) then base + "Module" else base
    Iterator
      .from(1)
      .map(index => if index == 1 then safe else safe + index)
      .find(alias => !imported(alias))
      .get

  private def importSpelling(
      sourcePath: String,
      targetPath: String,
      packageCache: String
  ): Option[String] =
    try
      val target = Path.of(targetPath).toAbsolutePath.normalize()
      val cache = Path.of(packageCache).toAbsolutePath.normalize()
      if target.startsWith(cache) then
        val spelling = cache.relativize(target).toString.replace('\\', '/')
        Option.when(spelling.matches("0x[0-9a-f]+/.+"))(spelling)
      else
        BendSourcePathEdits
          .relativeSpelling(sourcePath, targetPath)
          .filter(_.endsWith(".bend"))
    catch case NonFatal(_) => None
