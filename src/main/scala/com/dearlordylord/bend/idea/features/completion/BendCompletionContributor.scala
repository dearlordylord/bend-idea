package com.dearlordylord.bend.idea.features.completion

import com.dearlordylord.bend.idea.features.templates.api.BendSnippets
import com.dearlordylord.bend.idea.symbols.api.BendSourceSymbols
import com.dearlordylord.bend.idea.syntax.lexer.{BendLexer, BendTokens}
import com.intellij.codeInsight.completion.{CompletionContributor, CompletionParameters, CompletionProvider, CompletionResultSet, CompletionType, InsertionContext}
import com.intellij.codeInsight.lookup.{LookupElement, LookupElementBuilder}
import com.intellij.codeInsight.template.TemplateManager
import com.intellij.patterns.PlatformPatterns.psiElement
import com.intellij.util.ProcessingContext

/** Bend 2 keywords, snippets and current-file source symbols. */
final class BendCompletionContributor extends CompletionContributor:
  extend(CompletionType.BASIC, psiElement(), new CompletionProvider[CompletionParameters]:
    override def addCompletions(parameters: CompletionParameters, context: ProcessingContext, result: CompletionResultSet): Unit =
      if !parameters.getOriginalFile.getLanguage.isKindOf(com.dearlordylord.bend.idea.syntax.BendLanguage.instance) then return
      val source = parameters.getEditor.getDocument.getText
      val offset = parameters.getEditor.getCaretModel.getOffset
      BendCompletionContributor.context(source, offset).foreach { site =>
        val words = site match
          case BendCompletionContributor.Site.TopLevel => BendCompletionContributor.declarations
          case BendCompletionContributor.Site.Body => BendCompletionContributor.expressions
          case BendCompletionContributor.Site.Do => BendCompletionContributor.doWords
          case BendCompletionContributor.Site.Match => BendCompletionContributor.matchWords
          case BendCompletionContributor.Site.Law => BendCompletionContributor.lawWords
          case BendCompletionContributor.Site.TypeHeader => BendCompletionContributor.typeHeaderWords
          case BendCompletionContributor.Site.Type => BendCompletionContributor.typeWords
          case BendCompletionContributor.Site.Qualified => Nil
        words.foreach { word =>
          result.addElement(LookupElementBuilder.create(word).withTypeText("Bend 2 keyword"))
        }
        BendSourceSymbols.visibleCandidates(parameters.getOriginalFile, offset).foreach { symbol =>
          val signature = symbol.signature.source.replaceAll("\\s+", " ").trim
          val comments = symbol.comments.replaceAll("\\s+", " ").trim
          val tail = "  " + signature + (if comments.isEmpty then "" else "  # " + comments)
          result.addElement(LookupElementBuilder.create(symbol.declaration, symbol.name)
            .withTailText(tail, true)
            .withTypeText(symbol.category.toString.toLowerCase))
        }
        val triggers = site match
          case BendCompletionContributor.Site.TopLevel => Set("def", "type", "law", "import")
          case BendCompletionContributor.Site.Body | BendCompletionContributor.Site.Do | BendCompletionContributor.Site.Match => Set("match", "do")
          case _ => Set.empty[String]
        BendSnippets.all.filter(s => triggers.contains(s.trigger)).foreach { snippet =>
          result.addElement(LookupElementBuilder.create(snippet.trigger)
            .withPresentableText(snippet.trigger + " (snippet)")
            .withTypeText(snippet.description)
            .withInsertHandler((insertion: InsertionContext, _: LookupElement) =>
              insertion.setAddCompletionChar(false)
              val editor = insertion.getEditor
              val document = editor.getDocument
              val start = insertion.getStartOffset
              document.deleteString(start, insertion.getTailOffset)
              editor.getCaretModel.moveToOffset(start)
              insertion.commitDocument()
              insertion.setLaterRunnable(() =>
                TemplateManager.getInstance(insertion.getProject).startTemplate(editor, BendSnippets.template(insertion.getProject, snippet))
              )
            ))
        }
      }
  )

object BendCompletionContributor:
  private enum Site:
    case TopLevel, Body, Do, Match, Law, TypeHeader, Type, Qualified

  private val declarations = List("def", "type", "law", "import", "@unsafe")
  private val expressions = List("match", "do", "Type", "Data", "Kind", "Quant")
  private val doWords = "return" :: expressions
  private val matchWords = "case" :: expressions
  private val lawWords = List("for", "exs", "where", "Type", "Data", "Kind", "Quant")
  private val typeHeaderWords = List("is")
  private val typeWords = List("Type", "Data", "Kind", "Quant")

  private def context(source: String, offset: Int): Option[Site] =
    if offset < 0 || offset > source.length || inNonCode(source, offset) then None
    else
      val lineStart = source.lastIndexOf('\n', offset - 1) + 1
      val before = source.substring(lineStart, offset)
      // A declaration name, import path/alias, or qualified name is not a keyword site.
      if before.endsWith(".") then Some(Site.Qualified)
      else if before.matches("\\s*(?:for|exs)\\s+[A-Za-z_][A-Za-z0-9_.]*\\s*:\\s*[A-Za-z_]*") then Some(Site.Type)
      else if before.matches(".*[({][^)}]*:\\s*[A-Za-z_]*") then Some(Site.Type)
      else if before.matches("\\s*@unsafe\\s+[A-Za-z_]*") then Some(Site.TopLevel)
      else if before.matches("\\s*type\\s+[A-Za-z_][A-Za-z0-9_.]*\\s+is\\s+.*") then Some(Site.Type)
      else if before.matches("\\s*type\\s+[A-Za-z_][A-Za-z0-9_.]*\\s+") then Some(Site.TypeHeader)
      else if before.matches("\\s*(?:@unsafe\\s+)?def\\s+.*->\\s*[A-Za-z_]*") then Some(Site.Type)
      else if before.matches(".*\\b(?:def|type|law|import)\\s+[^:]*") then None
      else if before.matches("\\s*law\\s+.*") then Some(Site.Law)
      else if before.matches("\\s*type\\s+.*") then Some(Site.Type)
      else if before.forall(c => c == ' ' || c == '\t' || c.isLetterOrDigit || c == '_') && before.takeWhile(c => c == ' ' || c == '\t').isEmpty then Some(Site.TopLevel)
      else if before.trim.isEmpty || before.matches("\\s+[A-Za-z_][A-Za-z0-9_]*") || before.contains(':') || before.contains('=') || before.contains('(') then
        val preceding = source.substring(0, lineStart).linesIterator.toList.reverse.filter(_.trim.nonEmpty)
        val previous = preceding.headOption.getOrElse("")
        val indent = before.takeWhile(c => c == ' ' || c == '\t').length
        val owner = preceding.find(line => line.takeWhile(c => c == ' ' || c == '\t').length < indent).getOrElse("")
        if previous.trim.startsWith("law ") || previous.trim.startsWith("for ") || previous.trim.startsWith("exs ") then Some(Site.Law)
        else if owner.trim.startsWith("type ") then None
        else if previous.trim.startsWith("match ") || previous.trim.startsWith("case ") then Some(Site.Match)
        else if owner.trim.startsWith("do ") then Some(Site.Do)
        else Some(Site.Body)
      else Some(Site.Body)

  private def inNonCode(source: String, offset: Int): Boolean =
    val lexer = new BendLexer()
    lexer.start(source)
    while lexer.getTokenType != null && lexer.getTokenStart < offset do
      val token = lexer.getTokenType
      if token == BendTokens.Comment && offset <= lexer.getTokenEnd then return true
      lexer.advance()
    // State 1/2 is the shared lexer's unfinished quote mode. At a closing quote
    // boundary, advance() has already returned it to normal.
    val state = lexer.getState & 3
    if state != 0 then true
    else if lexer.getTokenType == BendTokens.StringContent || lexer.getTokenType == BendTokens.Escape || lexer.getTokenType == BendTokens.InvalidEscape then
      lexer.getTokenStart <= offset && offset < lexer.getTokenEnd
    else false
