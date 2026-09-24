package com.dearlordylord.bend.idea.symbols.index

import com.dearlordylord.bend.idea.syntax.psi.{
  BendDeclaration,
  BendStubIndexKeys
}
import com.intellij.psi.stubs.{StringStubIndexExtension, StubIndexKey}

/** Full declaration spellings used by workspace symbol discovery. */
object BendSymbolNameIndex:
  val Key: StubIndexKey[String, BendDeclaration] = BendStubIndexKeys.Names

final class BendSymbolNameIndex
    extends StringStubIndexExtension[BendDeclaration]:
  override def getKey: StubIndexKey[String, BendDeclaration] =
    BendSymbolNameIndex.Key
  override def getVersion: Int = 1

/** Dotted suffix and segment keys support future alias-aware reference
  * searches.
  */
object BendDottedComponentIndex:
  val Key: StubIndexKey[String, BendDeclaration] =
    BendStubIndexKeys.DottedComponents

final class BendDottedComponentIndex
    extends StringStubIndexExtension[BendDeclaration]:
  override def getKey: StubIndexKey[String, BendDeclaration] =
    BendDottedComponentIndex.Key
  override def getVersion: Int = 1
