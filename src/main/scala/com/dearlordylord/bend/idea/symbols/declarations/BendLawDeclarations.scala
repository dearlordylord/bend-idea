package com.dearlordylord.bend.idea.symbols.declarations

/** Minimal source facts for linking same-file law and fill sites. */
final case class BendDeclarationSite(name: String, offset: Int, law: Boolean,
    definition: Boolean, hasReturnType: Boolean, bareParameters: Boolean)

/** Source inventory only: even a filled law has no compiler verdict here. */
final case class BendLogicalLaw[A](law: A, fills: List[A]):
  def isOpen: Boolean = fills.isEmpty

object BendLawDeclarations:
  def relationships[A](declarations: List[A])(site: A => BendDeclarationSite): List[BendLogicalLaw[A]] =
    declarations.filter(a => site(a).law).map { law =>
      val lawSite = site(law)
      val fills = declarations.filter { candidate =>
        val value = site(candidate)
        value.definition && value.name == lawSite.name &&
          value.offset > lawSite.offset && !value.hasReturnType && value.bareParameters
      }
      BendLogicalLaw(law, fills)
    }

  /** Completion presents one logical name, while declarations() retains both source sites. */
  def completionCandidates[A](declarations: List[A])(site: A => BendDeclarationSite): List[A] =
    val fills = relationships(declarations)(site).flatMap(_.fills).toSet
    declarations.filterNot(fills.contains)
