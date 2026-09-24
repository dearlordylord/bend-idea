package com.dearlordylord.bend.idea.symbols.api

import com.dearlordylord.bend.idea.symbols.references.BendProjectPathReferences
import com.intellij.openapi.project.Project
import com.intellij.psi.{PsiFile, PsiReference}

enum BendSourcePathKind:
  case Module, Foreign

/** Public reference contract used by refactoring consumers. */
trait BendSourcePathReference extends PsiReference:
  def pathKind: BendSourcePathKind
  def spelling: String

/** Stable symbols API for locating and classifying path references. */
object BendSourcePathReferences:
  def to(target: PsiFile): Either[String, List[PsiReference]] =
    BendProjectPathReferences.to(target)

  def refersTo(
      reference: PsiReference,
      target: PsiFile,
      project: Project
  ): Boolean = BendProjectPathReferences.refersTo(reference, target, project)

  def spelling(reference: PsiReference): Option[String] =
    reference match
      case path: BendSourcePathReference => Some(path.spelling)
      case _                             => None
