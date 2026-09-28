package com.dearlordylord.bend.idea.syntax.parser

/** Source-only layout decisions shared by editor actions and offline callers.
  */
object BendLayoutPolicy:
  val defaultSettings: Settings = Settings(2, false, 2)
  def fromEditorConfig(
      properties: Map[String, String],
      base: Settings
  ): Either[String, Settings] =
    def value(key: String): Option[String] =
      properties
        .get(key)
        .map(_.trim.toLowerCase(java.util.Locale.ROOT))
        .filterNot(_ == "unset")
    def positive(key: String): Option[Int] =
      value(key).flatMap(_.toIntOption).filter(_ > 0)
    val style = value("indent_style")
    if style.exists(raw => raw != "tab" && raw != "space") then
      return Left("Unsupported indent_style")
    val tabWidth = positive("tab_width")
    if value("tab_width").nonEmpty && tabWidth.isEmpty then
      return Left("Invalid tab_width")
    if value("indent_size").exists(_ != "tab") &&
      positive("indent_size").isEmpty
    then return Left("Invalid indent_size")
    val size = value("indent_size") match
      case Some("tab") => tabWidth.getOrElse(base.tabWidth)
      case Some(_)     => positive("indent_size").getOrElse(base.indentSize)
      case None        => tabWidth.getOrElse(base.indentSize)
    val width = tabWidth.getOrElse(
      if value("indent_size").nonEmpty then size else base.tabWidth
    )
    Right(Settings(size, style.fold(base.useTabs)(_ == "tab"), width))
  final case class Settings(indentSize: Int, useTabs: Boolean, tabWidth: Int):
    def valid: Boolean = indentSize > 0 && tabWidth > 0

    def columns(text: String): Int =
      text.foldLeft(0) { (column, char) =>
        if char == '\t' then column + tabWidth - column % tabWidth
        else column + 1
      }

    def indent(columns: Int): Option[String] =
      if !valid || columns < 0 then None
      else if !useTabs then Some(" " * columns)
      else if columns % tabWidth == 0 then Some("\t" * (columns / tabWidth))
      else None

  enum Outcome:
    case Formatted(text: String)
    case Unchanged
    case Unavailable(reason: String)

  final case class Edit(start: Int, end: Int, replacement: String)

  def edits(source: String, settings: Settings): Either[String, List[Edit]] =
    plan(source, settings)

  private final case class Token(text: String, start: Int, end: Int)

  def format(source: String, settings: Settings): Outcome =
    plan(source, settings) match
      case Left(reason)                      => Outcome.Unavailable(reason)
      case Right(changes) if changes.isEmpty => Outcome.Unchanged
      case Right(changes)                    =>
        val formatted =
          changes.sortBy(-_.start).foldLeft(source) { case (text, edit) =>
            text.substring(0, edit.start) + edit.replacement + text.substring(
              edit.end
            )
          }
        Outcome.Formatted(formatted)

  private def plan(
      source: String,
      settings: Settings
  ): Either[String, List[Edit]] =
    if !settings.valid then return Left("Invalid indentation settings")
    val significant = scan(source) match
      case Left(reason)  => return Left(reason)
      case Right(tokens) => tokens

    val incompleteHeader = source.linesIterator.exists { line =>
      val trimmed = line.trim
      (trimmed.startsWith("def ") || trimmed.startsWith("law ") ||
        trimmed.startsWith("type ")) && !trimmed.endsWith(":")
    }
    if incompleteHeader then return Left("Incomplete declaration header")

    val replacements = scala.collection.mutable.ArrayBuffer.empty[Edit]
    significant.sliding(2).foreach {
      case List(left, right) =>
        val gap = source.substring(left.end, right.start)
        if !gap.exists(c => c == '\n' || c == '\r') then
          if right.text == "," && gap.nonEmpty then
            replacements += Edit(left.end, right.start, "")
          else if left.text == "," &&
            !Set(")", "]", "}").contains(right.text) && gap != " "
          then replacements += Edit(left.end, right.start, " ")
      case _ => ()
    }

    // The existing formatter only adjusts one simple definition/law body.
    // Avoid changing indentation in nested or multiline syntax whose layout
    // cannot yet be proven from the tolerant surface parser.
    val lines = source.split("\n", -1).toIndexedSeq
    var unrepresentable = false
    if lines.length >= 2 then
      var offset = 0
      for index <- lines.indices do
        val header = lines(index).stripSuffix("\r")
        if header.matches("(?:def|law)\\s+[^\\n]*:\\s*") then
          val next = index + 1
          if next < lines.length then
            val body = lines(next).stripSuffix("\r")
            val leading = body.takeWhile(c => c == ' ' || c == '\t')
            val following = lines.drop(next + 1).takeWhile { line =>
              line.trim.nonEmpty && line.headOption
                .exists(c => c == ' ' || c == '\t')
            }
            if body.trim.nonEmpty && !body.trim.startsWith(
                "#"
              ) && following.isEmpty
            then
              if leading.contains('\t') then
                if !settings.useTabs || leading.contains(' ') ||
                  settings.columns(leading) != settings.indentSize
                then unrepresentable = true
              else if Set(2, 4, settings.indentSize).contains(leading.length)
              then
                settings.indent(settings.indentSize) match
                  case Some(target) if leading != target =>
                    replacements += Edit(
                      offset + lines(index).length + 1,
                      offset + lines(index).length + 1 + leading.length,
                      target
                    )
                  case None => unrepresentable = true
                  case _    => ()
        offset += lines(index).length + 1
    if unrepresentable then Left("Indentation cannot be safely represented")
    else Right(replacements.toList)

  private def scan(source: String): Either[String, List[Token]] =
    val tokens = List.newBuilder[Token]
    val stack = scala.collection.mutable.ArrayBuffer.empty[Char]
    var index = 0
    var unsafe = false
    while index < source.length do
      val start = index
      val char = source.charAt(index)
      if char.isWhitespace then index += 1
      else if char == '#' then
        while index < source.length && source.charAt(index) != '\n' do
          index += 1
        tokens += Token(source.substring(start, index), start, index)
      else if char == '"' || char == '\'' then
        index += 1
        var closed = false
        while index < source.length && !closed do
          val current = source.charAt(index)
          if current == '\\' then
            index += 1
            if index >= source.length then unsafe = true
            else if "ntr0\\'\"".contains(source.charAt(index)) then index += 1
            else if (source.charAt(index) == 'u' || source.charAt(
                index
              ) == 'U') &&
              index + 1 < source.length && source.charAt(index + 1) == '{'
            then
              index += 2
              val digitsStart = index
              while index < source.length && Character.digit(
                  source.charAt(index),
                  16
                ) >= 0
              do index += 1
              if index == digitsStart || index - digitsStart > 8 ||
                index >= source.length || source.charAt(index) != '}'
              then unsafe = true
              else index += 1
            else
              unsafe = true
              index += 1
          else if current == char then
            index += 1
            closed = true
          else index += 1
        if !closed then unsafe = true
        tokens += Token(source.substring(start, index), start, index)
      else
        char match
          case '('             => stack += ')'
          case '['             => stack += ']'
          case '{'             => stack += '}'
          case ')' | ']' | '}' =>
            if stack.lastOption.contains(char) then
              val _ = stack.remove(stack.size - 1)
            else unsafe = true
          case _ => ()
        index += 1
        tokens += Token(source.substring(start, index), start, index)
    if unsafe || stack.nonEmpty then Left("Incomplete or malformed source")
    else Right(tokens.result())
