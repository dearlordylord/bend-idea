package com.dearlordylord.bend.idea.features.documentation

import com.dearlordylord.bend.idea.analysis.api.{
  BendCheckService,
  BendExpressionTypeQuery
}
import com.dearlordylord.bend.idea.analysis.model.BendExpressionType
import com.dearlordylord.bend.idea.symbols.api.*
import com.dearlordylord.bend.idea.syntax.psi.BendDeclaration
import com.intellij.lang.documentation.{
  AbstractDocumentationProvider,
  ExternalDocumentationHandler
}
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.editor.Editor
import com.intellij.psi.{PsiDocumentManager, PsiElement, PsiFile, PsiManager}
import com.intellij.psi.util.PsiTreeUtil
import java.net.{URLDecoder, URLEncoder}
import java.nio.charset.StandardCharsets

/** IntelliJ Quick Documentation over current source declarations and native
  * references.
  */
class BendDocumentationProvider
    extends AbstractDocumentationProvider
    with ExternalDocumentationHandler:
  override def getCustomDocumentationElement(
      editor: Editor,
      file: PsiFile,
      contextElement: PsiElement,
      targetOffset: Int
  ): PsiElement =
    val leaf = Option(file)
      .filter(
        _.getLanguage == com.dearlordylord.bend.idea.syntax.BendLanguage.instance
      )
      .flatMap(f => Option(f.findElementAt(targetOffset)))
    if leaf.flatMap(syntaxEntry).nonEmpty then return leaf.orNull
    Option(file.findReferenceAt(targetOffset))
      .flatMap(r => Option(r.resolve()))
      .orElse(
        Option(file.findElementAt(targetOffset)).flatMap(e =>
          Option(PsiTreeUtil.getParentOfType(e, classOf[BendDeclaration]))
            .flatMap(d =>
              Option(d.getNameIdentifier)
                .filter(n => n.getTextRange.containsOffset(targetOffset))
            )
        )
      )
      .orElse(
        Option
          .when(currentExpressionType(file, targetOffset).nonEmpty)(())
          .flatMap(_ => Option(file.findElementAt(targetOffset)))
      )
      .orNull

  override def getQuickNavigateInfo(
      element: PsiElement,
      originalElement: PsiElement
  ): String =
    val syntax = syntaxEntry(element)
    if syntax.nonEmpty then
      val entry = syntax.get
      return s"${entry.heading}\n${entry.explanation}"
    val signature = target(element, originalElement).map(_._2.sourceSignature)
    val actual = typeAt(originalElement, element)
      .map(value => s"Expression type (Bend): ${value.typeText}")
    (signature.toList ++ actual.toList).mkString("\n") match
      case ""   => null
      case text => text

  override def generateDoc(
      element: PsiElement,
      originalElement: PsiElement
  ): String =
    val syntax = syntaxEntry(element)
    if syntax.nonEmpty then
      val entry = syntax.get
      return "<div class='definition'><pre>" + escape(entry.heading) +
        "</pre></div><div class='content'><p>" + escape(
          entry.explanation
        ) + "</p></div>"
    val sourceDoc = target(element, originalElement).map { case (file, site) =>
      val symbol = site.symbol
      val html = new StringBuilder
      def append(value: String): Unit =
        val _ = html.append(value)
      append("<div class='definition'>")
      if symbol.category == BendSymbolCategory.Law then
        append("<p>Law specification</p>")
      append("<pre>")
      append(escape(site.sourceSignature))
      append("</pre></div>")
      append("<div class='content'><p>")
      append(label(symbol.category))
      append(" in ")
      append(escape(file.getName))
      append("</p>")
      if symbol.comments.nonEmpty then
        append("<p>")
        append(escape(symbol.comments).replace("\n", "<br/>"))
        append("</p>")
      site.law.foreach { law =>
        append("<p>Law specification in ")
        append(escape(sourceName(law)))
        append("</p><pre>")
        append(escape(law.signature.source))
        append("</pre>")
        if law.comments.nonEmpty then
          append("<p>")
          append(escape(law.comments).replace("\n", "<br/>"))
          append("</p>")
        append(link("Declaration", law))
      }
      if site.law.isEmpty then append(link("Declaration", symbol))
      site.fills.foreach { fill =>
        append("<p>Implementation in ")
        append(escape(sourceName(fill)))
        append("</p><pre>")
        append(escape(fill.signature.source))
        append("</pre>")
        if fill.comments.nonEmpty then
          append("<p>")
          append(escape(fill.comments).replace("\n", "<br/>"))
          append("</p>")
        append(link("Implementation", fill))
      }
      if site.law.nonEmpty then
        append(" ")
        append(link("Implementation", symbol))
      append("</div>")
      html.toString
    }
    val actual = typeAt(originalElement, element).map { value =>
      "<div class='content'><p>Expression type (Bend compiler)</p><pre>" +
        escape(value.typeText) + "</pre></div>"
    }
    (sourceDoc.toList ++ actual.toList).mkString match
      case ""   => null
      case html => html

  /** Source links are editor navigation, not another documentation page.
    * Consume stale owned links too, so they never fall through to the OS
    * browser.
    */
  override def handleExternalLink(
      psiManager: PsiManager,
      url: String,
      context: PsiElement
  ): Boolean =
    if !url.startsWith("bend-doc:") then false
    else
      Option(getDocumentationElementForLink(psiManager, url, context))
        .foreach { target =>
          Option(target.getContainingFile.getVirtualFile).foreach { file =>
            new OpenFileDescriptor(
              psiManager.getProject,
              file,
              target.getTextOffset
            ).navigate(true)
          }
        }
      true

  override def getDocumentationElementForLink(
      psiManager: PsiManager,
      link: String,
      context: PsiElement
  ): PsiElement =
    if !link.startsWith("bend-doc:") || context == null then null
    else
      val parts = link.stripPrefix("bend-doc:").split(":", 4)
      if parts.length != 4 then null
      else
        val path = URLDecoder.decode(parts(0), StandardCharsets.UTF_8)
        val category = parts(1)
        val offset = parts(2).toIntOption
        val name = URLDecoder.decode(parts(3), StandardCharsets.UTF_8)
        val current = context.getContainingFile
        if current == null then null
        else
          val file = if BendSourceSymbols.fileId(current).value == path then
            Some(current)
          else
            com.dearlordylord.bend.idea.symbols.api.BendPhysicalTargets
              .file(
                context.getProject,
                new com.dearlordylord.bend.idea.model.FileId(path, true)
              )
          file
            .flatMap(f =>
              BendSourceSymbols
                .declarations(f)
                .find(s =>
                  s.category.toString == category && offset
                    .contains(s.handle.nameOffset) &&
                    s.name == name
                )
                .map(_.declaration.getNameIdentifier)
            )
            .orNull

  private def syntaxEntry(element: PsiElement): Option[BendSyntaxEntry] =
    Option(element)
      .filter(e =>
        e.isValid && e.getLanguage == com.dearlordylord.bend.idea.syntax.BendLanguage.instance &&
          e.getFirstChild == null
      )
      .flatMap(e =>
        BendSyntaxDocumentation.entry(e.getNode.getElementType, e.getText)
      )

  private def target(
      element: PsiElement,
      originalElement: PsiElement
  ): Option[(PsiFile, BendDocumentationSite)] =
    Option(element).flatMap(e =>
      Option(e.getContainingFile).flatMap { file =>
        val declaration =
          Option(PsiTreeUtil.getParentOfType(e, classOf[BendDeclaration]))
        declaration
          .flatMap(d =>
            BendSourceSymbols.declarations(file).find(_.declaration eq d)
          )
          .map { s =>
            val requestFile = Option(originalElement)
              .flatMap(o => Option(o.getContainingFile))
              .filter(_.getLanguage == file.getLanguage)
              .getOrElse(file)
            (file, BendSourceDocumentation.site(requestFile, s))
          }
      }
    )

  private def typeAt(
      originalElement: PsiElement,
      fallback: PsiElement
  ): Option[BendExpressionType] =
    Option(originalElement).orElse(Option(fallback)).flatMap { element =>
      Option(element.getContainingFile)
        .flatMap(file =>
          currentExpressionType(file, element.getTextRange.getStartOffset)
        )
    }

  protected def currentExpressionType(
      file: PsiFile,
      offset: Int
  ): Option[BendExpressionType] =
    val document = PsiDocumentManager
      .getInstance(file.getProject)
      .getDocument(file)
    if document == null then None
    else
      val source = BendSourceSymbols.fileId(file)
      val service = file.getProject.getService(classOf[BendCheckService])
      val current = service.resultsFor(source).filter(service.isCurrent)
      if current.size != 1 then None
      else
        BendExpressionTypeQuery.at(
          current.head,
          source,
          document.getText,
          document.getModificationStamp,
          offset
        )

  private def link(label: String, symbol: BendSourceSymbol): String =
    val path =
      URLEncoder.encode(symbol.handle.file.value, StandardCharsets.UTF_8)
    val name = URLEncoder.encode(symbol.name, StandardCharsets.UTF_8)
    s"<a href='bend-doc:$path:${symbol.category}:${symbol.handle.nameOffset}:$name'>$label</a>"

  private def sourceName(symbol: BendSourceSymbol): String =
    symbol.handle.file.value
      .replace('\\', '/')
      .split("/")
      .lastOption
      .getOrElse(symbol.handle.file.value)

  private def label(category: BendSymbolCategory): String = category match
    case BendSymbolCategory.Definition  => "Definition"
    case BendSymbolCategory.Law         => "Law"
    case BendSymbolCategory.Datatype    => "Datatype"
    case BendSymbolCategory.Constructor => "Constructor"
    case BendSymbolCategory.Binder      => "Binder"

  private def escape(value: String): String =
    value
      .replace("&", "&amp;")
      .replace("<", "&lt;")
      .replace(">", "&gt;")
      .replace("\"", "&quot;")
      .replace("'", "&#39;")
