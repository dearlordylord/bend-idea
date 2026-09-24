package com.dearlordylord.bend.idea.features.editing

import com.dearlordylord.bend.idea.features.templates.api.BendSnippets
import com.dearlordylord.bend.idea.symbols.api.*
import com.dearlordylord.bend.idea.syntax.lexer.{
  BendLexer,
  BendTokens,
  BendWords
}
import com.dearlordylord.bend.idea.syntax.psi.*
import com.dearlordylord.bend.idea.workspace.api.BendLoadingConfiguration
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.{PsiDocumentManager, PsiFile}
import com.intellij.psi.util.PsiTreeUtil
import scala.collection.mutable
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

final case class BendMatchSourceRevision(
    text: String,
    documentStamp: Long,
    virtualFileStamp: Long
)

final case class BendMatchSkeletonPlan private[editing] (
    file: PsiFile,
    sourceText: String,
    sourceRevision: BendMatchSourceRevision,
    datatype: BendSourceHandle,
    datatypeFile: PsiFile,
    datatypeRevision: BendMatchSourceRevision,
    loadingPaths: (String, String),
    matchOffset: Int,
    insertOffset: Int,
    insertion: String,
    placeholderStart: Int,
    placeholderEnd: Int
)

final case class BendMatchSkeletonInsertion(
    file: VirtualFile,
    placeholderStart: Int,
    placeholderEnd: Int
)

/** Source-only match skeleton planning. It relies on explicit annotations and
  * the shared resolver; it does not infer expression types or dependent goals.
  */
object BendMatchSkeletonGenerator:
  private val MatchHeader =
    """match[ \t]+([A-Za-z_][A-Za-z0-9_]*)[ \t]*:[ \t]*(?:#.*)?""".r
  private val CaseHead = """case[ \t]+([A-Za-z_][A-Za-z0-9_.]*)""".r
  private val Name = "[A-Za-z_][A-Za-z0-9_]*"
  private val TypeName = "[A-Za-z_][A-Za-z0-9_.]*"

  private final case class SourceLine(
      start: Int,
      contentEnd: Int,
      end: Int,
      indent: Int,
      code: String
  ):
    def isBlank: Boolean = code.isEmpty || code.startsWith("#")

  private final case class MatchContext(
      line: SourceLine,
      scrutinee: String,
      bodyEnd: Int,
      insertionOffset: Int,
      caseIndent: Int,
      existingConstructors: Set[String]
  )

  def plan(file: PsiFile, caretOffset: Int): Option[BendMatchSkeletonPlan] =
    if file == null || !file.isValid then return None
    val source = file.getText
    val lines = sourceLines(source)
    val keywords = activeKeywords(source)
    val contexts = lines.flatMap { line =>
      MatchHeader
        .findFirstMatchIn(line.code)
        .filter(_ => keywords(line.start + line.indent))
        .flatMap(matched =>
          matchContext(lines, line, matched.group(1), keywords)
        )
    }
    contexts
      .filter(context =>
        context.line.start <= caretOffset && caretOffset <= context.bodyEnd
      )
      .sortBy(context => context.bodyEnd - context.line.start)
      .headOption
      .flatMap(context => planContext(file, source, context))

  def insert(
      project: Project,
      planned: BendMatchSkeletonPlan
  ): Either[String, BendMatchSkeletonInsertion] =
    val refreshed = plan(planned.file, planned.matchOffset)
    if !refreshed.exists(samePlan(_, planned)) then
      return Left(
        "The match source or datatype changed; generate the cases again"
      )

    WriteCommandAction
      .writeCommandAction(project)
      .compute[Either[String, BendMatchSkeletonInsertion], RuntimeException](
        () =>
          val file = planned.file
          Option(file.getVirtualFile) match
            case None => Left("The Bend source has no writable file")
            case Some(virtualFile)
                if !file.isValid || !file.isWritable || !virtualFile.isWritable =>
              Left("The Bend source is read-only")
            case Some(virtualFile)
                if file.getText != planned.sourceText ||
                  !revisionMatches(project, file, planned.sourceRevision) ||
                  !planned.datatypeFile.isValid ||
                  !revisionMatches(
                    project,
                    planned.datatypeFile,
                    planned.datatypeRevision
                  ) ||
                  project
                    .getService(classOf[BendLoadingConfiguration])
                    .paths != planned.loadingPaths =>
              Left("Source changed after planning; generate the cases again")
            case Some(virtualFile) =>
              Option(
                PsiDocumentManager.getInstance(project).getDocument(file)
              ) match
                case None => Left("Could not open the Bend source document")
                case Some(document) =>
                  val before =
                    if planned.insertOffset > 0 &&
                      planned.sourceText
                        .charAt(planned.insertOffset - 1) != '\n'
                    then "\n"
                    else ""
                  val rendered = before + planned.insertion
                  val start = planned.insertOffset + before.length +
                    planned.insertion.indexOf("?TODO")
                  document.insertString(planned.insertOffset, rendered)
                  PsiDocumentManager
                    .getInstance(project)
                    .commitDocument(document)
                  Right(
                    BendMatchSkeletonInsertion(
                      virtualFile,
                      start,
                      start + "?TODO".length
                    )
                  )
      )

  private def planContext(
      file: PsiFile,
      source: String,
      context: MatchContext
  ): Option[BendMatchSkeletonPlan] =
    val project = file.getProject
    val offset = context.line.start + context.line.indent
    val binding = BendSourceSymbols
      .visibleBindings(file, offset)
      .find(_.name == context.scrutinee)
      .filter(binding =>
        binding.origin == BendBindingKind.Parameter ||
          binding.origin == BendBindingKind.Pattern
      )
    val loading = project.getService(classOf[BendLoadingConfiguration])
    val (basePath, cachePath) = loading.paths
    val symbols = project.getService(classOf[BendImportedSymbolCatalog])
    try
      val (_, current, imported) = symbols.visibleWithCurrent(
        file,
        offset,
        basePath,
        cachePath
      )
      val visible = current.map(symbol => symbol.name -> symbol) ++ imported
      val bindingType =
        binding.flatMap(value => bindingTypeName(file, value, source, visible))
      val dataType = bindingType
        .flatMap(name => unique(visible, name, BendSymbolCategory.Datatype))
        .filter(isPlainDataType)
      dataType.flatMap { datatype =>
        val constructors = directConstructors(datatype, visible)
        val existing = context.existingConstructors
        // An ordinary binder arm is a catch-all in Bend's ordered matches.
        // Appending constructors after it would create unreachable cases.
        val existingCasesAreConstructors =
          existing.forall(name => constructors.count(_._1 == name) == 1)
        val missing = constructors.filterNot(c => existing(c._1))
        if constructors.isEmpty || missing.isEmpty ||
          !existingCasesAreConstructors
        then None
        else
          val occupied = BendSourceSymbols
            .visibleBindings(file, offset)
            .map(_.name)
            .toSet + context.scrutinee
          val arms = missing.map { case (visibleName, constructor) =>
            val fresh =
              freshPatternFields(constructor.signature.parameters, occupied)
            BendSnippets.MatchArm(visibleName, fresh)
          }
          val indent = " " * context.caseIndent
          val insertion = BendSnippets.renderMatchArms(arms, indent)
          if insertion.isEmpty then None
          else
            val datatypeFile = datatype.declaration.getContainingFile
            val revision = sourceRevision(project, file)
            val datatypeRevision = sourceRevision(project, datatypeFile)
            Some(
              BendMatchSkeletonPlan(
                file,
                source,
                revision,
                datatype.handle,
                datatypeFile,
                datatypeRevision,
                loading.paths,
                offset,
                context.insertionOffset,
                insertion,
                -1,
                -1
              )
            )
      }
    catch case NonFatal(_) => None

  private def bindingTypeName(
      file: PsiFile,
      binding: BendSourceBinding,
      source: String,
      visible: List[(String, BendSourceSymbol)]
  ): Option[String] =
    val annotation = binding.origin match
      case BendBindingKind.Parameter => annotationType(binding.source)
      case BendBindingKind.Pattern   =>
        patternFieldType(file, binding, source, visible)
      case _ => None
    annotation.filter(name =>
      unique(visible, name, BendSymbolCategory.Datatype).nonEmpty
    )

  private def patternFieldType(
      file: PsiFile,
      binding: BendSourceBinding,
      source: String,
      visible: List[(String, BendSourceSymbol)]
  ): Option[String] =
    val line = sourceLines(source).find(l =>
      l.start <= binding.handle.nameOffset && binding.handle.nameOffset < l.contentEnd
    )
    line.flatMap { row =>
      CaseHead
        .findFirstMatchIn(row.code)
        .filter(_ => activeKeywords(source)(row.start + row.indent))
        .flatMap { matched =>
          val code = row.code.drop(matched.end).trim
          val opening = code.indexOf('{')
          val closing = if opening < 0 then -1 else matchingBrace(code, opening)
          if opening < 0 || closing < 0 then None
          else
            val constructorName = matched.group(1)
            val patternFields =
              splitTopLevel(code.substring(opening + 1, closing))
            val selectedIndex = patternFields
              .indexWhere(field => patternBinder(field).contains(binding.name))
            if selectedIndex < 0 then None
            else
              unique(visible, constructorName, BendSymbolCategory.Constructor)
                .flatMap(_.signature.parameters.lift(selectedIndex))
                .flatMap(parameter => annotationType(parameter.source))
        }
    }

  private def annotationType(source: String): Option[String] =
    val content = source.dropWhile(c => c == '+' || c == '-' || c == '~').trim
    val colon = content.indexOf(':')
    if colon < 0 then None
    else
      val name = content.substring(colon + 1).trim
      Option.when(name.matches(TypeName))(name)

  private def isPlainDataType(symbol: BendSourceSymbol): Boolean =
    symbol.declaration match
      case datatype: BendDatatype =>
        datatype.headerText.matches("(?s).*\\bis\\s+Data\\b.*") &&
        datatype.parameters.isEmpty &&
        PsiTreeUtil
          .getChildrenOfTypeAsList(datatype, classOf[BendConstructor])
          .size() > 0
      case _ => false

  private def directConstructors(
      datatype: BendSourceSymbol,
      visible: List[(String, BendSourceSymbol)]
  ): List[(String, BendSourceSymbol)] =
    val declaration = datatype.declaration.asInstanceOf[BendDatatype]
    PsiTreeUtil
      .getChildrenOfTypeAsList(declaration, classOf[BendConstructor])
      .asScala
      .toList
      .flatMap { constructor =>
        val offset = constructor.getNameIdentifier.getTextOffset
        val choices = visible.collect {
          case (name, symbol)
              if symbol.category == BendSymbolCategory.Constructor &&
                symbol.handle.file == datatype.handle.file &&
                symbol.handle.nameOffset == offset =>
            name -> symbol
        }
        Option.when(choices.size == 1)(choices.head)
      }

  private def unique(
      visible: List[(String, BendSourceSymbol)],
      name: String,
      category: BendSymbolCategory
  ): Option[BendSourceSymbol] =
    val found = visible
      .collect {
        case (`name`, symbol) if symbol.category == category => symbol
      }
      .distinctBy(_.handle)
    Option.when(found.size == 1)(found.head)

  private def freshPatternFields(
      parameters: List[BendSourceParameter],
      outerNames: Set[String]
  ): List[String] =
    val used = mutable.Set.from(outerNames)
    parameters.map { parameter =>
      val base = Option
        .when(
          parameter.name.matches(Name) &&
            !BendWords.reserved(parameter.name)
        )(parameter.name)
        .getOrElse("field")
      var name = base
      var suffix = 2
      while used(name) do
        name = s"$base$suffix"
        suffix += 1
      used += name
      // Bend patterns accept the reusable (+) quantity marker. The erased
      // (-) marker is declaration-only and cannot prefix a pattern binder.
      val quantity = parameter.quantity.filter(_ == '+').getOrElse(' ')
      (if quantity == ' ' then "" else quantity.toString) + name
    }

  private def matchContext(
      lines: List[SourceLine],
      header: SourceLine,
      scrutinee: String,
      active: Set[Int]
  ): Option[MatchContext] =
    val index = lines.indexWhere(_.start == header.start)
    if index < 0 then None
    else
      val following = lines.drop(index + 1)
      val candidates = following.filterNot(_.isBlank)
      val firstCase = candidates.find(line =>
        CaseHead.findFirstMatchIn(line.code).nonEmpty &&
          active(line.start + line.indent)
      )
      val caseIndent = firstCase.map(_.indent).getOrElse(header.indent + 2)
      var lastIncluded = header.end
      var stopped = false
      var foundCase = false
      val caseNames = Set.newBuilder[String]
      following.foreach { line =>
        if !stopped && !line.isBlank then
          if line.indent <= header.indent then stopped = true
          else if line.indent == caseIndent then
            CaseHead.findFirstMatchIn(line.code) match
              case Some(matched) if active(line.start + line.indent) =>
                foundCase = true
                caseNames += matched.group(1)
                lastIncluded = line.end
              case _ => stopped = true
          else if line.indent < caseIndent then stopped = true
          else lastIncluded = line.end
      }
      val bodyEnd = if foundCase then lastIncluded else header.end
      Some(
        MatchContext(
          header,
          scrutinee,
          bodyEnd,
          if foundCase then lastIncluded else header.end,
          caseIndent,
          caseNames.result()
        )
      )

  private def sourceLines(source: String): List[SourceLine] =
    val out = List.newBuilder[SourceLine]
    var start = 0
    while start < source.length do
      val newline = source.indexOf('\n', start)
      val contentEnd = if newline < 0 then source.length else newline
      val end = if newline < 0 then source.length else newline + 1
      val raw = source.substring(start, contentEnd).stripSuffix("\r")
      val indent = raw.takeWhile(c => c == ' ' || c == '\t').length
      out += SourceLine(start, contentEnd, end, indent, raw.drop(indent).trim)
      start = end
    if source.isEmpty then Nil else out.result()

  private def activeKeywords(source: String): Set[Int] =
    val lexer = new BendLexer()
    lexer.start(source, 0, source.length, 0)
    val out = Set.newBuilder[Int]
    while lexer.getTokenType != null do
      if lexer.getTokenType == BendTokens.Keyword &&
        Set("match", "case").contains(
          source.substring(lexer.getTokenStart, lexer.getTokenEnd)
        )
      then out += lexer.getTokenStart
      lexer.advance()
    out.result()

  private def patternBinder(source: String): Option[String] =
    val trimmed = source.trim.dropWhile(c => c == '+' || c == '-')
    Option.when(trimmed.matches(Name))(trimmed)

  private def matchingBrace(source: String, open: Int): Int =
    var depth = 0
    var index = open
    while index < source.length do
      source.charAt(index) match
        case '{' => depth += 1
        case '}' =>
          depth -= 1
          if depth == 0 then return index
        case _ => ()
      index += 1
    -1

  private def splitTopLevel(source: String): List[String] =
    val out = List.newBuilder[String]
    var start = 0
    var depth = 0
    source.indices.foreach { index =>
      source.charAt(index) match
        case '(' | '[' | '{' | '<' => depth += 1
        case ')' | ']' | '}' | '>' => depth = math.max(0, depth - 1)
        case ',' if depth == 0     =>
          out += source.substring(start, index)
          start = index + 1
        case _ => ()
    }
    if source.nonEmpty then out += source.substring(start)
    out.result()

  private def samePlan(
      refreshed: BendMatchSkeletonPlan,
      planned: BendMatchSkeletonPlan
  ): Boolean =
    refreshed.file == planned.file && refreshed.datatype == planned.datatype &&
      refreshed.sourceText == planned.sourceText &&
      refreshed.insertOffset == planned.insertOffset &&
      refreshed.insertion == planned.insertion &&
      refreshed.loadingPaths == planned.loadingPaths

  private def sourceRevision(
      project: Project,
      file: PsiFile
  ): BendMatchSourceRevision =
    val document = PsiDocumentManager.getInstance(project).getDocument(file)
    val sourceText = Option(document).map(_.getText).getOrElse(file.getText)
    val documentStamp = Option(document)
      .map(_.getModificationStamp)
      .getOrElse(file.getModificationStamp)
    val virtualStamp = Option(file.getVirtualFile)
      .map(_.getModificationStamp)
      .getOrElse(-1L)
    BendMatchSourceRevision(sourceText, documentStamp, virtualStamp)

  private def revisionMatches(
      project: Project,
      file: PsiFile,
      expected: BendMatchSourceRevision
  ): Boolean =
    file.isValid && sourceRevision(project, file) == expected
