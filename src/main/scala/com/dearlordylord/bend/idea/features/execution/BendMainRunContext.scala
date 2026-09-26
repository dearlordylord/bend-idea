package com.dearlordylord.bend.idea.features.execution

import com.dearlordylord.bend.idea.syntax.BendLanguage
import com.dearlordylord.bend.idea.syntax.psi.{BendDefinition, BendName}
import com.intellij.execution.actions.{
  ConfigurationContext,
  LazyRunConfigurationProducer
}
import com.intellij.execution.configurations.{
  ConfigurationFactory,
  ConfigurationTypeUtil
}
import com.intellij.execution.lineMarker.{
  ExecutorAction,
  RunLineMarkerContributor
}
import com.intellij.icons.AllIcons
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.Ref
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil

/** A source entry point, independent of compiler availability. The existing
  * Bend Run configuration checks the toolchain and saves documents at launch.
  */
private[execution] object BendMainRunContext:
  def definitionAt(element: PsiElement): Option[BendDefinition] =
    Option(element)
      .flatMap(value =>
        Option(
          PsiTreeUtil.getParentOfType(value, classOf[BendDefinition], false)
        )
      )
      .filter { definition =>
        val file = definition.getContainingFile
        file != null && definition.getParent == file &&
        file.getLanguage == BendLanguage.instance &&
        file.getVirtualFile != null &&
        definition.getName == "main" &&
        definition.headerText.matches(
          "(?s)^def\\s+main\\s*\\(\\s*\\)\\s*(?:->.*)?\\s*:$"
        )
      }

/** Lets IDEA create and reuse Bend Run configurations from a main definition.
  */
final class BendMainRunConfigurationProducer
    extends LazyRunConfigurationProducer[BendRunConfiguration]
    with DumbAware:
  override def getConfigurationFactory: ConfigurationFactory =
    ConfigurationTypeUtil
      .findConfigurationType(classOf[BendRunConfigurationType])
      .getConfigurationFactories
      .find(_.getId == "Bend Run")
      .getOrElse(throw new IllegalStateException("Bend Run factory is missing"))

  override protected def setupConfigurationFromContext(
      configuration: BendRunConfiguration,
      context: ConfigurationContext,
      sourceElement: Ref[PsiElement]
  ): Boolean =
    BendMainRunContext.definitionAt(context.getPsiLocation) match
      case Some(definition) =>
        val file = definition.getContainingFile
        configuration.rootPath = file.getVirtualFile.getPath
        configuration.setName(s"Bend: ${file.getName}")
        sourceElement.set(definition.getNameIdentifier)
        true
      case None => false

  override def isConfigurationFromContext(
      configuration: BendRunConfiguration,
      context: ConfigurationContext
  ): Boolean =
    BendMainRunContext.definitionAt(context.getPsiLocation).exists {
      definition =>
        BendExecutionConfigurationParsing
          .absolute(
            configuration.rootPath,
            BendExecutionConfigurationParsing.projectBase(context.getProject)
          )
          .toOption
          .exists(
            _.toString == definition.getContainingFile.getVirtualFile.getPath
          )
    }

/** The standard Run popup at the name leaf of a runnable main definition. */
final class BendMainRunLineMarkerContributor
    extends RunLineMarkerContributor
    with DumbAware:
  override def getInfo(element: PsiElement): RunLineMarkerContributor.Info =
    element.getParent match
      case name: BendName if name.getFirstChild == element =>
        BendMainRunContext
          .definitionAt(name)
          .filter(_.getNameIdentifier == name)
          .map(_ =>
            new RunLineMarkerContributor.Info(
              AllIcons.Actions.Execute,
              ExecutorAction.getActions(),
              (_: PsiElement) => "Run Bend main"
            )
          )
          .orNull
      case _ => null
