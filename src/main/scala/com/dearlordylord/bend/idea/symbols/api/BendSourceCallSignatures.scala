package com.dearlordylord.bend.idea.symbols.api

import com.dearlordylord.bend.idea.syntax.psi.BendSourceParameter
import com.dearlordylord.bend.idea.workspace.api.BendLoadingConfiguration
import com.intellij.psi.PsiFile
import com.intellij.openapi.progress.ProgressManager
import scala.collection.mutable

final case class BendRenderedCallSignature(
    application: BendSourceApplication,
    text: String,
    parameters: List[BendSourceParameter],
    parameterRanges: List[(Int, Int)],
    activeParameter: Int
)

/** Root-relative, source-only signature resolution shared by P3 consumers. */
object BendSourceCallSignatures:
  def resolve(
      file: PsiFile,
      application: BendSourceApplication
  ): Option[BendRenderedCallSignature] =
    val project = file.getProject
    val (basePath, packageCache) =
      project.getService(classOf[BendLoadingConfiguration]).paths
    val catalog = project.getService(classOf[BendImportedSymbolCatalog])
    val category = application.kind match
      case BendApplicationKind.Function    => BendSymbolCategory.Definition
      case BendApplicationKind.Datatype    => BendSymbolCategory.Datatype
      case BendApplicationKind.Constructor => BendSymbolCategory.Constructor
    val resolution = catalog.resolve(
      file,
      application.calleeFrom,
      application.callee,
      basePath,
      packageCache,
      Some(category)
    )
    val symbol = resolution match
      case BendSourceResolution.Resolved(value)                  => Some(value)
      case _ if application.kind == BendApplicationKind.Function =>
        catalog
          .resolve(
            file,
            application.calleeFrom,
            application.callee,
            basePath,
            packageCache,
            Some(BendSymbolCategory.Law)
          ) match
          case BendSourceResolution.Resolved(value) => Some(value)
          case _                                    => None
      case _ => None
    symbol.map(selected => renderResolved(file, application, selected))

  private def selectedSymbol(
      snapshot: BendSourceNavigationSnapshot,
      application: BendSourceApplication
  ): Option[BendSourceSymbol] =
    val catalog = snapshot.file.getProject.getService(
      classOf[BendImportedSymbolCatalog]
    )
    val category = application.kind match
      case BendApplicationKind.Function    => BendSymbolCategory.Definition
      case BendApplicationKind.Datatype    => BendSymbolCategory.Datatype
      case BendApplicationKind.Constructor => BendSymbolCategory.Constructor
    def selected(forCategory: BendSymbolCategory): Option[BendSourceSymbol] =
      val resolution = catalog.resolveForNavigation(
        snapshot,
        application.calleeFrom,
        application.callee,
        Some(forCategory)
      )
      if !resolution.eligible then None
      else
        resolution.target match
          case BendSourceResolution.Resolved(value) => Some(value)
          case _                                    => None
    selected(category)
      .orElse(
        Option
          .when(application.kind == BendApplicationKind.Function)(())
          .flatMap(_ => selected(BendSymbolCategory.Law))
      )

  private def renderResolved(
      file: PsiFile,
      application: BendSourceApplication,
      selected: BendSourceSymbol
  ): BendRenderedCallSignature =
    val signatureSource =
      if application.kind != BendApplicationKind.Function then selected
      else
        val documentation = BendSourceDocumentation.site(file, selected)
        documentation.law.getOrElse(selected)
    renderFromSource(application, signatureSource)

  private def renderFromSource(
      application: BendSourceApplication,
      signatureSource: BendSourceSymbol
  ): BendRenderedCallSignature =
    val allParameters = signatureSource.signature.parameters
    val omitImplicitQuantity =
      application.kind == BendApplicationKind.Datatype &&
        allParameters.headOption.exists(_.implicitQuantity) &&
        !application.arguments.headOption.exists(argument =>
          argument.source.trim.matches("&[012]")
        )
    val parameters =
      if omitImplicitQuantity then allParameters.drop(1) else allParameters
    render(application, parameters, application.activeArgument)

  private def render(
      application: BendSourceApplication,
      parameters: List[BendSourceParameter],
      activeParameter: Int
  ): BendRenderedCallSignature =
    val (open, close) = application.kind match
      case BendApplicationKind.Function    => ("(", ")")
      case BendApplicationKind.Datatype    => ("<", ">")
      case BendApplicationKind.Constructor => ("{", "}")
    val text = new StringBuilder(application.callee + open)
    val ranges = List.newBuilder[(Int, Int)]
    parameters.zipWithIndex.foreach { case (parameter, index) =>
      if index > 0 then text.append(", ")
      val start = text.length
      text.append(parameter.source)
      ranges += ((start, text.length))
    }
    text.append(close)
    BendRenderedCallSignature(
      application,
      text.toString,
      parameters,
      ranges.result(),
      activeParameter
    )

  def hints(
      file: PsiFile,
      application: BendSourceApplication
  ): List[(String, Int)] =
    hintsFrom(resolve(file, application), application)

  /** Reuse a qualified import's source signature throughout one root pass. */
  def hintsForCallees(
      snapshot: BendSourceNavigationSnapshot,
      applications: Iterable[BendSourceApplication]
  ): Map[Int, List[(String, Int)]] =
    val qualified = mutable.Map.empty[
      (String, BendApplicationKind),
      Option[BendSourceSymbol]
    ]
    val signatureSources = mutable.Map.empty[BendSourceHandle, BendSourceSymbol]
    applications.iterator.map { application =>
      ProgressManager.checkCanceled()
      val selected =
        if application.callee.contains('.') then
          qualified.getOrElseUpdate(
            (application.callee, application.kind),
            selectedSymbol(snapshot, application)
          )
        else selectedSymbol(snapshot, application)
      val rendered = selected.map { symbol =>
        val source = signatureSources.getOrElseUpdate(
          symbol.handle,
          if application.kind == BendApplicationKind.Function then
            BendSourceDocumentation
              .site(snapshot.file, symbol, snapshot.graph)
              .law
              .getOrElse(symbol)
          else symbol
        )
        renderFromSource(application, source)
      }
      application.calleeFrom -> hintsFrom(rendered, application)
    }.toMap

  private def hintsFrom(
      resolved: Option[BendRenderedCallSignature],
      application: BendSourceApplication
  ): List[(String, Int)] =
    resolved.toList.flatMap { signature =>
      application.arguments.zipWithIndex.flatMap { case (argument, index) =>
        val parameterIndex = index +
          (signature.activeParameter - application.activeArgument)
        signature.parameters.lift(parameterIndex).flatMap { parameter =>
          val value = argument.source.trim
          val suppliedName = value.stripPrefix("~")
          val obvious = suppliedName.matches("[A-Za-z_][A-Za-z0-9_.]*") &&
            suppliedName == parameter.name
          val alreadyLabeled =
            application.kind == BendApplicationKind.Constructor &&
              hasExplicitFieldLabel(value)
          Option.when(value.nonEmpty && !obvious && !alreadyLabeled)(
            (parameter.name, argument.from)
          )
        }
      }
    }

  private def hasExplicitFieldLabel(value: String): Boolean =
    val colon = value.indexOf(':')
    colon > 0 &&
    value.substring(0, colon).trim.matches("[A-Za-z_][A-Za-z0-9_]*")
