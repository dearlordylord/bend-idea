package com.dearlordylord.bend.idea.symbols.references

import com.intellij.psi.{PsiDocumentManager, PsiElement, PsiReference}

private[references] object BendPathReferenceEditing:
  def replace(reference: PsiReference, spelling: String): PsiElement =
    val element = reference.getElement
    val document = PsiDocumentManager
      .getInstance(element.getProject)
      .getDocument(element.getContainingFile)
    if document == null then return element
    val range = reference.getRangeInElement
    document.replaceString(range.getStartOffset, range.getEndOffset, spelling)
    PsiDocumentManager.getInstance(element.getProject).commitDocument(document)
    element
