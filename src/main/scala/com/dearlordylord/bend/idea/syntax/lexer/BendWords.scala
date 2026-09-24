package com.dearlordylord.bend.idea.syntax.lexer

/** Words reserved by the pinned Bend 2 parser. `as` is only contextual in
  * imports.
  */
object BendWords:
  val declaration: Set[String] = Set("def", "type", "law")
  val control: Set[String] =
    Set("match", "case", "do", "return", "for", "exs", "where", "is", "import")
  val universes: Set[String] = Set("Type", "Data", "Kind", "Quant")
  val reserved: Set[String] = declaration ++ control ++ universes
