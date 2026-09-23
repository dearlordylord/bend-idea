package com.dearlordylord.bend.idea.features.documentation

import com.dearlordylord.bend.idea.symbols.api.*
import com.dearlordylord.bend.idea.syntax.psi.BendDeclaration
import com.intellij.lang.documentation.AbstractDocumentationProvider
import com.intellij.openapi.editor.Editor
import com.intellij.psi.{PsiElement, PsiFile, PsiManager}
import com.intellij.psi.util.PsiTreeUtil
import java.net.{URLDecoder, URLEncoder}
import java.nio.charset.StandardCharsets

/** IntelliJ Quick Documentation over current source declarations and native references. */
final class BendDocumentationProvider extends AbstractDocumentationProvider:
  override def getCustomDocumentationElement(editor: Editor, file: PsiFile,
      contextElement: PsiElement, targetOffset: Int): PsiElement =
    Option(file.findReferenceAt(targetOffset)).flatMap(r => Option(r.resolve()))
      .orElse(Option(file.findElementAt(targetOffset)).flatMap(e =>
        Option(PsiTreeUtil.getParentOfType(e, classOf[BendDeclaration]))
          .flatMap(d => Option(d.getNameIdentifier).filter(n => n.getTextRange.containsOffset(targetOffset)))))
      .orNull

  override def getQuickNavigateInfo(element: PsiElement, originalElement: PsiElement): String =
    target(element, originalElement).map { case (_, site) => site.sourceSignature }.orNull

  override def generateDoc(element: PsiElement, originalElement: PsiElement): String =
    target(element, originalElement).map { case (file, site) =>
      val symbol = site.symbol
      val html = new StringBuilder
      html.append("<div class='definition'>")
      if symbol.category == BendSymbolCategory.Law then html.append("<p>Law specification</p>")
      html.append("<pre>").append(escape(site.sourceSignature))
        .append("</pre></div>")
      html.append("<div class='content'><p>").append(label(symbol.category)).append(" in ")
        .append(escape(file.getName)).append("</p>")
      if symbol.comments.nonEmpty then html.append("<p>").append(escape(symbol.comments).replace("\n", "<br/>"))
        .append("</p>")
      site.law.foreach { law =>
        html.append("<p>Law specification in ").append(escape(sourceName(law)))
          .append("</p><pre>").append(escape(law.signature.source)).append("</pre>")
        if law.comments.nonEmpty then html.append("<p>")
          .append(escape(law.comments).replace("\n", "<br/>"))
          .append("</p>")
        html.append(link("Declaration", law))
      }
      if site.law.isEmpty then html.append(link("Declaration", symbol))
      site.fills.foreach { fill =>
        html.append("<p>Implementation in ").append(escape(sourceName(fill))).append("</p><pre>")
          .append(escape(fill.signature.source)).append("</pre>")
        if fill.comments.nonEmpty then html.append("<p>")
          .append(escape(fill.comments).replace("\n", "<br/>"))
          .append("</p>")
        html.append(link("Implementation", fill))
      }
      if site.law.nonEmpty then html.append(" ").append(link("Implementation", symbol))
      html.append("</div>").toString
    }.orNull

  override def getDocumentationElementForLink(psiManager: PsiManager, link: String,
      context: PsiElement): PsiElement =
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
          val file = if BendSourceSymbols.fileId(current).value == path then Some(current)
            else com.dearlordylord.bend.idea.symbols.references.BendPhysicalTargets.file(
              context.getProject, new com.dearlordylord.bend.idea.model.FileId(path, true))
          file.flatMap(f => BendSourceSymbols.declarations(f).find(s =>
            s.category.toString == category && offset.contains(s.handle.nameOffset) &&
              s.name == name)
            .map(_.declaration.getNameIdentifier)).orNull

  private def target(element: PsiElement, originalElement: PsiElement): Option[(PsiFile, BendDocumentationSite)] =
    Option(element).flatMap(e => Option(e.getContainingFile).flatMap { file =>
      val declaration = Option(PsiTreeUtil.getParentOfType(e, classOf[BendDeclaration]))
      declaration.flatMap(d => BendSourceSymbols.declarations(file).find(_.declaration eq d))
        .map { s =>
          val requestFile = Option(originalElement).flatMap(o => Option(o.getContainingFile))
            .filter(_.getLanguage == file.getLanguage).getOrElse(file)
          (file, BendSourceDocumentation.site(requestFile, s))
        }
    })

  private def link(label: String, symbol: BendSourceSymbol): String =
    val path = URLEncoder.encode(symbol.handle.file.value, StandardCharsets.UTF_8)
    val name = URLEncoder.encode(symbol.name, StandardCharsets.UTF_8)
    s"<a href='bend-doc:$path:${symbol.category}:${symbol.handle.nameOffset}:$name'>$label</a>"

  private def sourceName(symbol: BendSourceSymbol): String =
    symbol.handle.file.value.replace('\\', '/').split("/").lastOption.getOrElse(symbol.handle.file.value)

  private def label(category: BendSymbolCategory): String = category match
    case BendSymbolCategory.Definition => "Definition"
    case BendSymbolCategory.Law => "Law"
    case BendSymbolCategory.Datatype => "Datatype"
    case BendSymbolCategory.Constructor => "Constructor"
    case BendSymbolCategory.Binder => "Binder"

  private def escape(value: String): String =
    value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
      .replace("\"", "&quot;").replace("'", "&#39;")
