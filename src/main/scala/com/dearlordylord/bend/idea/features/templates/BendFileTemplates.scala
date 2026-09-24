package com.dearlordylord.bend.idea.features.templates

import com.intellij.ide.fileTemplates.FileTemplateManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.psi.{
  PsiDirectory,
  PsiDocumentManager,
  PsiFile,
  PsiFileFactory
}
import com.intellij.util.ThrowableRunnable
import com.dearlordylord.bend.idea.syntax.BendLanguage
import com.dearlordylord.bend.idea.syntax.lexer.BendWords
import scala.util.control.NonFatal

/** Creates source files from editable IDE file templates. */
object BendFileTemplates:
  private val ModuleTemplate = "Bend Module"
  private val LawsTemplate = "Bend Laws"
  private val ProofTemplate = "Bend Proof"

  def createModule(
      directory: PsiDirectory,
      requestedName: String
  ): Either[String, PsiFile] =
    val moduleName = requestedName.stripSuffix(".bend")
    if !moduleName.matches("[A-Za-z_][A-Za-z0-9_]*") ||
      BendWords.reserved(moduleName)
    then return Left("Use a nonreserved Bend identifier for the module name.")
    val fileName = moduleName + ".bend"
    if exists(directory, fileName) then
      return Left(s"$fileName already exists in ${directory.getName}.")
    templateText(directory, ModuleTemplate, "NAME", moduleName).map { text =>
      var created: PsiFile = null
      WriteCommandAction
        .writeCommandAction(directory.getProject)
        .withName("Create Bend Module")
        .run(
          new ThrowableRunnable[RuntimeException]:
            override def run(): Unit =
              val source = PsiFileFactory
                .getInstance(directory.getProject)
                .createFileFromText(fileName, BendLanguage.instance, text)
              created = directory.add(source).asInstanceOf[PsiFile]
        )
      created
    }

  def createLawProofPair(
      directory: PsiDirectory
  ): Either[String, (PsiFile, PsiFile)] =
    val lawsName = "LAWS.bend"
    val proofName = "PROOF.bend"
    if exists(directory, lawsName) || exists(directory, proofName) then
      return Left(
        s"Cannot create the pair because LAWS.bend or PROOF.bend already exists in ${directory.getName}."
      )
    for
      lawText <- templateText(directory, LawsTemplate, "NAME", "claim")
      proofText <- templateText(directory, ProofTemplate, "NAME", "claim")
    yield
      var createdLaws: PsiFile = null
      var createdProof: PsiFile = null
      WriteCommandAction
        .writeCommandAction(directory.getProject)
        .withName("Create Bend Law and Proof Pair")
        .run(
          new ThrowableRunnable[RuntimeException]:
            override def run(): Unit =
              val factory = PsiFileFactory.getInstance(directory.getProject)
              createdLaws = directory
                .add(
                  factory.createFileFromText(
                    lawsName,
                    BendLanguage.instance,
                    lawText
                  )
                )
                .asInstanceOf[PsiFile]
              createdProof = directory
                .add(
                  factory.createFileFromText(
                    proofName,
                    BendLanguage.instance,
                    proofText
                  )
                )
                .asInstanceOf[PsiFile]
        )
      (createdLaws, createdProof)

  def openAt(file: PsiFile, offset: Int): Unit =
    PsiDocumentManager.getInstance(file.getProject).commitAllDocuments()
    Option(file.getVirtualFile).foreach(virtual =>
      new OpenFileDescriptor(
        file.getProject,
        virtual,
        math.max(0, math.min(offset, file.getTextLength))
      ).navigate(true)
    )

  private def exists(directory: PsiDirectory, fileName: String): Boolean =
    directory.findFile(fileName) != null ||
      directory.findSubdirectory(fileName) != null

  private def templateText(
      directory: PsiDirectory,
      templateName: String,
      variable: String,
      value: String
  ): Either[String, String] =
    val manager = FileTemplateManager.getInstance(directory.getProject)
    val template = manager.findInternalTemplate(templateName)
    if template == null then
      Left(s"The $templateName file template is unavailable.")
    else
      val properties = manager.getDefaultProperties
      val _ = properties.setProperty(variable, value)
      try Right(template.getText(properties))
      catch
        case NonFatal(_) =>
          Left(s"The $templateName file template could not be rendered.")
