package com.dearlordylord.bend.idea.syntax.psi

import com.dearlordylord.bend.idea.syntax.BendLanguage
import com.intellij.psi.tree.IElementType

object BendElements:
  private def element(name: String): IElementType =
    new IElementType(name, BendLanguage.instance)
  val Name = element("BEND_NAME")
  val Reference = element("BEND_REFERENCE")
  val Alias = element("BEND_ALIAS")
  val Header = element("BEND_HEADER")
  val Definition = BendStubElementTypes.Definition
  val Datatype = BendStubElementTypes.Datatype
  val Law = BendStubElementTypes.Law
  val Constructor = BendStubElementTypes.Constructor
