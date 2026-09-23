package com.dearlordylord.bend.idea.features.editing

import com.dearlordylord.bend.idea.syntax.psi.{BendConstructor, BendDatatype, BendDeclaration, BendDefinition, BendLaw}
import com.intellij.ide.structureView.{StructureViewBuilder, StructureViewModel, StructureViewModelBase, StructureViewTreeElement, TreeBasedStructureViewBuilder}
import com.intellij.ide.structureView.impl.common.PsiTreeElementBase
import com.intellij.lang.PsiStructureViewFactory
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.psi.{PsiElement, PsiFile}
import com.intellij.psi.util.PsiTreeUtil
import java.util.Collection
import scala.jdk.CollectionConverters.*

final class BendStructureViewFactory extends PsiStructureViewFactory:
  override def getStructureViewBuilder(file: PsiFile): StructureViewBuilder =
    new TreeBasedStructureViewBuilder:
      override def createStructureViewModel(editor: Editor): StructureViewModel =
        new StructureViewModelBase(file, editor, new BendStructureElement(file))
          .withSuitableClasses(classOf[BendDeclaration])

private[editing] final class BendStructureElement(element: PsiElement)
    extends PsiTreeElementBase[PsiElement](element):
  override def getPresentableText: String =
    getElement match
      case declaration: BendDeclaration =>
        val full = declaration.headerText.replaceAll("\\s+", " ").stripSuffix(":").trim
        val header = if full.length > 120 then full.take(117) + "..." else full
        val prefix = declaration match
          case _: BendDefinition => "def "
          case _: BendLaw => "law "
          case _: BendDatatype => "type "
          case _: BendConstructor => ""
        if header.startsWith(prefix) then header else prefix + header
      case file: PsiFile => file.getName
      case _ => ""

  override def getChildrenBase: Collection[StructureViewTreeElement] =
    val children = getElement match
      case file: PsiFile => PsiTreeUtil.getChildrenOfTypeAsList(file, classOf[BendDeclaration]).asScala.toList
      case datatype: BendDatatype => PsiTreeUtil.getChildrenOfTypeAsList(datatype, classOf[BendConstructor]).asScala.toList
      case _ => Nil
    children.filter(_.getNameIdentifier != null).map(item =>
      new BendStructureElement(item): StructureViewTreeElement).asJava

  override def navigate(requestFocus: Boolean): Unit =
    getElement match
      case declaration: BendDeclaration if declaration.getNameIdentifier != null &&
          declaration.getContainingFile.getVirtualFile != null =>
        new OpenFileDescriptor(declaration.getProject, declaration.getContainingFile.getVirtualFile,
          declaration.getNameIdentifier.getTextRange.getStartOffset).navigate(requestFocus)
      case _ => super.navigate(requestFocus)
