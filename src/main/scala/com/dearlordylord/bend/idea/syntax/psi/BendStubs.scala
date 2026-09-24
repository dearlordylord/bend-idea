package com.dearlordylord.bend.idea.syntax.psi

import com.dearlordylord.bend.idea.syntax.BendLanguage
import com.intellij.psi.PsiElement
import com.intellij.psi.stubs.*
import com.intellij.psi.tree.IStubFileElementType
import scala.annotation.static

/** Stable keys emitted from file-local declaration syntax; query policy lives
  * in symbols.index.
  */
object BendStubIndexKeys:
  val Names: StubIndexKey[String, BendDeclaration] =
    StubIndexKey.createIndexKey("com.dearlordylord.bend.symbol.name")
  val DottedComponents: StubIndexKey[String, BendDeclaration] =
    StubIndexKey.createIndexKey("com.dearlordylord.bend.symbol.dottedComponent")

  def dottedComponents(name: String): List[String] =
    val suffixes = name.indices
      .filter(name(_) == '.')
      .map(offset => name.substring(offset + 1))
    val segments = name.split("\\.").filter(_.nonEmpty).toList
    (suffixes.toList ++ segments).distinct.filter(component =>
      component.nonEmpty && component != name
    )

/** Declaration facts stored in a stub are derived solely from that
  * declaration's file.
  */
final class BendDeclarationStub(
    parent: StubElement[? <: PsiElement],
    val name: String,
    elementType: IStubElementType[?, ?]
) extends StubBase[BendDeclaration](parent, elementType)

private final class BendDeclarationElementType(
    debugName: String,
    create: BendDeclarationStub => BendDeclaration
) extends IStubElementType[BendDeclarationStub, BendDeclaration](
      debugName,
      BendLanguage.instance
    ):
  override def createPsi(stub: BendDeclarationStub): BendDeclaration = create(
    stub
  )

  override def createStub(
      psi: BendDeclaration,
      parent: StubElement[? <: PsiElement]
  ): BendDeclarationStub =
    val name = Option(psi.getNameIdentifier).map(_.getText).getOrElse("")
    new BendDeclarationStub(parent, name, this)

  override def serialize(
      stub: BendDeclarationStub,
      data: StubOutputStream
  ): Unit =
    data.writeName(stub.name)

  override def deserialize(
      data: StubInputStream,
      parent: StubElement[? <: PsiElement]
  ): BendDeclarationStub =
    new BendDeclarationStub(parent, data.readNameString(), this)

  override def getExternalId: String = s"bend.$debugName"

  override def indexStub(stub: BendDeclarationStub, sink: IndexSink): Unit =
    if stub.name.nonEmpty then
      sink.occurrence(BendStubIndexKeys.Names, stub.name)
      BendStubIndexKeys.dottedComponents(stub.name).foreach { component =>
        sink.occurrence(BendStubIndexKeys.DottedComponents, component)
      }

/** Static element type fields are discovered by IntelliJ's stub holder
  * extension.
  */
trait BendStubElementTypes

object BendStubElementTypes:
  @static val Definition
      : IStubElementType[BendDeclarationStub, BendDeclaration] =
    new BendDeclarationElementType(
      "Definition",
      stub => new BendDefinition(stub)
    )
  @static val Datatype: IStubElementType[BendDeclarationStub, BendDeclaration] =
    new BendDeclarationElementType("Datatype", stub => new BendDatatype(stub))
  @static val Law: IStubElementType[BendDeclarationStub, BendDeclaration] =
    new BendDeclarationElementType("Law", stub => new BendLaw(stub))
  @static val Constructor
      : IStubElementType[BendDeclarationStub, BendDeclaration] =
    new BendDeclarationElementType(
      "Constructor",
      stub => new BendConstructor(stub)
    )

final class BendFileElementType
    extends IStubFileElementType[PsiFileStub[?]](
      "BEND_FILE",
      BendLanguage.instance
    ):
  override def getExternalId: String = "bend.FILE"
  override def getStubVersion: Int = 1
  override def indexStub(stub: PsiFileStub[?], sink: IndexSink): Unit = ()

object BendFileElementType:
  val instance: IStubFileElementType[PsiFileStub[?]] =
    new BendFileElementType
