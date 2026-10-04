package com.dearlordylord.bend.idea.features.documentation

import com.dearlordylord.bend.idea.syntax.lexer.{BendTokens, BendWords}
import com.intellij.psi.tree.IElementType

private[documentation] final case class BendSyntaxEntry(
    spelling: String,
    explanation: String
):
  def heading: String = s"Bend syntax: $spelling"

/** Presentation data only; recognition belongs to the shared lexer vocabulary.
  */
private[documentation] object BendSyntaxDocumentation:
  private val explanations: Map[String, String] = Map(
    "def" -> "Defines a value or function. A definition can supply the implementation of a law.",
    "type" -> "Introduces a datatype and its constructors.",
    "law" -> "Specifies a type to be filled by a definition. Declaring a law does not establish that it has been checked or filled.",
    "import" -> "Introduces a module import or a foreign implementation import. Module aliases qualify names from the direct import; surrounding syntax selects the form.",
    "match" -> "Selects a body by matching the supplied values against cases.",
    "case" -> "Introduces one pattern and its body within a match.",
    "do" -> "Uses monadic sequencing notation with the selected monad. It also supports monads other than IO.",
    "return" -> "Supplies the result of a do block through its monad.",
    "for" -> "Introduces a parameter in a law specification.",
    "exs" -> "Requests a witness together with the remaining specification.",
    "where" -> "Adds evidence to a law parameter, pairing the value with evidence for its condition.",
    "is" -> "Specifies the kind of a declared datatype.",
    "Type" -> "Abbreviation for Kind(&1), for types with affine values.",
    "Data" -> "Abbreviation for Kind(&2), for types whose values may be reused.",
    "Kind" -> "Classifies a type by the quantity permitted for its values.",
    "Quant" -> "The type of quantities.",
    "&0" -> "Erased quantity: relevant to checking, absent from runtime values.",
    "&1" -> "Affine quantity: permits at most one use; discarding is allowed.",
    "&2" -> "Reusable quantity: permits repeated use.",
    "<&>" -> "Takes the smaller of two quantities.",
    "->" -> "Separates the domain and result of a function type, or introduces a definition's result type.",
    "=>" -> "Separates a lambda's parameters from its body.",
    "==" -> "Equality notation; the surrounding type and terms determine the equality being specified.",
    "!=" -> "Inequality notation; surrounding syntax supplies its operands and type.",
    "{==}" -> "A reflexivity witness. The compiler must still check it against the required equality."
  )

  def entry(kind: IElementType, spelling: String): Option[BendSyntaxEntry] =
    val recognized =
      ((kind == BendTokens.DeclarationKeyword || kind == BendTokens.Keyword ||
        kind == BendTokens.BuiltinType) && BendWords.reserved.contains(
        spelling
      )) ||
        (kind == BendTokens.Quantity && Set("&0", "&1", "&2").contains(
          spelling
        )) ||
        (kind == BendTokens.Operator && Set("<&>", "->", "=>", "==", "!=")
          .contains(spelling)) ||
        (kind == BendTokens.Rewrite && spelling == "{==}")
    Option
      .when(recognized)(spelling)
      .flatMap(value => explanations.get(value).map(BendSyntaxEntry(value, _)))
