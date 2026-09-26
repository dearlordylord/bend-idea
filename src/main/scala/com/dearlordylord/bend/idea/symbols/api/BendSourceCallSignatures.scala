package com.dearlordylord.bend.idea.symbols.api

import com.dearlordylord.bend.idea.syntax.psi.BendSourceParameter
import com.dearlordylord.bend.idea.workspace.api.BendLoadingConfiguration
import com.intellij.psi.PsiFile

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
    symbol.map { selected =>
      val documentation = BendSourceDocumentation.site(file, selected)
      val signatureSource = documentation.law.getOrElse(selected)
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
    }

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
    resolve(file, application).toList.flatMap { signature =>
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
