package com.dearlordylord.bend.idea.features.templates.api

import com.intellij.codeInsight.template.{Template, TemplateManager}
import com.intellij.codeInsight.template.impl.ConstantNode
import com.intellij.openapi.project.Project

/** The six source skeletons shared by completion and later explicit template
  * actions.
  */
object BendSnippets:
  final case class Snippet(
      trigger: String,
      description: String,
      parts: List[Part]
  )
  enum Part:
    case Text(value: String)
    case Field(name: String, default: String)
    case End

  import Part.*

  val all: List[Snippet] = List(
    Snippet(
      "def",
      "Function definition",
      List(
        Text("def "),
        Field("NAME", "name"),
        Text("("),
        Field("PARAM", "x: U32"),
        Text(") -> "),
        Field("RESULT", "U32"),
        Text(":\n  "),
        Field("BODY", "x"),
        End
      )
    ),
    Snippet(
      "type",
      "Datatype declaration",
      List(
        Text("type "),
        Field("NAME", "Name"),
        Text(" is "),
        Field("KIND", "Data"),
        Text(":\n  "),
        Field("CONSTRUCTOR", "Constructor"),
        Text("{"),
        Field("FIELD", "value: U32"),
        Text("}"),
        End
      )
    ),
    Snippet(
      "law",
      "Law specification",
      List(
        Text("law "),
        Field("NAME", "name"),
        Text(":\n  for "),
        Field("PARAM", "x"),
        Text(": "),
        Field("TYPE", "Nat"),
        Text("\n  {"),
        Field("PARAM", "x"),
        Text(" == "),
        Field("PARAM", "x"),
        Text(" : "),
        Field("TYPE", "Nat"),
        Text("}"),
        End
      )
    ),
    Snippet(
      "match",
      "Pattern match",
      List(
        Text("match "),
        Field("VALUE", "value"),
        Text(":\n  case "),
        Field("SOME", "Some"),
        Text("{"),
        Field("BINDER", "x"),
        Text("}:\n    "),
        Field("BINDER", "x"),
        Text("\n  case "),
        Field("NONE", "None"),
        Text("{}:\n    "),
        Field("FALLBACK", "0"),
        End
      )
    ),
    Snippet(
      "do",
      "IO block",
      List(
        Text("do IO<"),
        Field("TYPE", "Unit"),
        Text(">:\n  "),
        Field("BODY", "IO.print(\"Hello, Bend!\")"),
        End
      )
    ),
    Snippet(
      "import",
      "Aliased module import",
      List(
        Text("import "),
        Field("PATH", "./module.bend"),
        Text(" as "),
        Field("ALIAS", "M"),
        End
      )
    )
  )

  def template(project: Project, snippet: Snippet): Template =
    val template =
      TemplateManager.getInstance(project).createTemplate("", "Bend")
    val seen = scala.collection.mutable.Set.empty[String]
    snippet.parts.foreach {
      case Text(value)          => template.addTextSegment(value)
      case Field(name, default) =>
        if seen.add(name) then
          template.addVariable(name, null, new ConstantNode(default), true)
        else template.addVariableSegment(name)
      case End => template.addEndVariable()
    }
    template
