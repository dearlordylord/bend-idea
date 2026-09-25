package com.dearlordylord.bend.idea.symbols.api

import com.intellij.psi.{PsiElement, PsiReference}

/** Native Bend reference shape and resolution behavior captured for one source
  * revision. Alias-prefix references and qualified-member references retain
  * distinct spellings while sharing the source snapshot.
  */
trait BendSnapshotAwareReference extends PsiReference:
  def semanticSpelling: String

  def resolveAgainst(
      snapshot: BendSourceNavigationSnapshot
  ): BendNavigationResolution

/** Shared factory used by the PSI provider and batched source consumers. */
trait BendSnapshotAwareReferenceFactory:
  def referencesFor(
      element: PsiElement,
      snapshot: BendSourceNavigationSnapshot
  ): List[BendSnapshotAwareReference]
