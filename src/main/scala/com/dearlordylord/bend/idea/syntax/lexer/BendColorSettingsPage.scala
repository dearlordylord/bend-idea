package com.dearlordylord.bend.idea.syntax.lexer

import com.dearlordylord.bend.idea.syntax.BendFileType
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.options.colors.{AttributesDescriptor, ColorDescriptor, ColorSettingsPage}
import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

final class BendColorSettingsPage extends ColorSettingsPage:
  override def getDisplayName: String = "Bend"
  override def getAdditionalHighlightingTagToDescriptorMap: java.util.Map[String, TextAttributesKey] = java.util.Collections.emptyMap()
  override def getIcon: Icon = IconLoader.getIcon("/icons/bend.svg", classOf[BendFileType])
  override def getHighlighter: SyntaxHighlighter = new BendSyntaxHighlighter()
  override def getAttributeDescriptors: Array[AttributesDescriptor] = Array(
    AttributesDescriptor("Keyword", BendColors.Keyword),
    AttributesDescriptor("Declaration keyword", BendColors.Declaration),
    AttributesDescriptor("Function or law name", BendColors.Function),
    AttributesDescriptor("Type name", BendColors.Type),
    AttributesDescriptor("Import alias", BendColors.Namespace),
    AttributesDescriptor("Built-in type", BendColors.Builtin),
    AttributesDescriptor("Identifier", BendColors.Identifier),
    AttributesDescriptor("Number", BendColors.Number),
    AttributesDescriptor("Quantity", BendColors.Quantity),
    AttributesDescriptor("String and import path", BendColors.String),
    AttributesDescriptor("Escape", BendColors.Escape),
    AttributesDescriptor("Invalid escape", BendColors.InvalidEscape),
    AttributesDescriptor("Comment", BendColors.Comment),
    AttributesDescriptor("Hole", BendColors.Hole),
    AttributesDescriptor("Rewrite and wildcard", BendColors.Rewrite),
    AttributesDescriptor("Operator", BendColors.Operator),
    AttributesDescriptor("Bracket", BendColors.Bracket),
    AttributesDescriptor("Separator", BendColors.Separator),
    AttributesDescriptor("Invalid character", BendColors.Bad)
  )
  override def getColorDescriptors: Array[ColorDescriptor] = ColorDescriptor.EMPTY_ARRAY
  override def getDemoText: String =
    """import Base
      |import ./Numbers.bend as Numbers
      |# A proof obligation and a small program
      |type Shape is Data:
      |  Circle{radius: U32}
      |law identity:
      |  for x: Nat
      |  {x == x : Nat}
      |@unsafe def area(&2 shape: Shape) -> U32:
      |  match shape:
      |    case Circle{+radius}: 3.14 * radius
      |def identity(x): {==}
      |def main() -> IO(Unit):
      |  do IO<Unit>:
      |    IO.print("value\\n")
      |    ?TODO
      |""".stripMargin
