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
    // Standard EditorConfig properties ignore unsupported values independently.
    // The Bend-specific width property below deliberately reports invalid input.
    val style =
      value("indent_style").filter(raw => raw == "tab" || raw == "space")
    val tabWidth = positive("tab_width")
    val indentSize = value("indent_size").filter(raw =>
      raw == "tab" || raw.toIntOption.exists(_ > 0)
    )
    val size = indentSize match
      case Some("tab") => tabWidth.getOrElse(base.tabWidth)
      case Some(_)     => positive("indent_size").getOrElse(base.indentSize)
      case None        => base.indentSize
    val width = tabWidth.getOrElse(
      if indentSize.nonEmpty then size else base.tabWidth
    )
    val maximum = value("bend_max_line_length") match
      case None | Some("unset") => Some(100)
      case Some("off")          => None
      case Some(raw)            =>
        raw.toIntOption.filter(_ > 0) match
          case Some(number) => Some(number)
          case None         => return Left("Invalid bend_max_line_length")
    Right(Settings(size, style.fold(base.useTabs)(_ == "tab"), width, maximum))
  final case class Settings(
      indentSize: Int,
      useTabs: Boolean,
      tabWidth: Int,
      maxLineLength: Option[Int] = Some(100)
  ):
    def valid: Boolean =
      indentSize > 0 && tabWidth > 0 && maxLineLength.forall(_ > 0)

    def columns(text: String): Int =
      text.foldLeft(0) { (column, char) =>
        if char == '\t' then column + tabWidth - column % tabWidth
        else column + 1
      }

    def indent(columns: Int): Option[String] =
      if !valid || columns < 0 then None
      else if !useTabs then Some(" " * columns)
      else Some("\t" * (columns / tabWidth) + " " * (columns % tabWidth))

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

    val replacements = scala.collection.mutable.ArrayBuffer.empty[Edit]
    val structure = recognize(source, significant) match
      case Left(reason) => return Left(reason)
      case Right(value) => value
    val effectiveBodyIndent = scala.collection.mutable.Map.empty[Int, Int]
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
              val columns = settings.columns(leading)
              if leading.contains(' ') && leading.contains('\t') &&
                !settings.indent(columns).contains(leading)
              then unrepresentable = true
              else if Set(2, 4, settings.indentSize).contains(columns) then
                effectiveBodyIndent(offset + lines(index).length + 1) =
                  settings.indentSize
                settings.indent(settings.indentSize) match
                  case Some(target) if leading != target =>
                    replacements += Edit(
                      offset + lines(index).length + 1,
                      offset + lines(index).length + 1 + leading.length,
                      target
                    )
                  case None => unrepresentable = true
                  case _    => ()
              else if leading.contains('\t') then unrepresentable = true
        offset += lines(index).length + 1
    if unrepresentable then
      return Left("Indentation cannot be safely represented")
    val gaps = scala.collection.mutable.Map.empty[Int, String]
    significant.indices.drop(1).foreach { index =>
      val left = significant(index - 1)
      val right = significant(index)
      val gap = source.substring(left.end, right.start)
      if !gap.exists(c => c == '\n' || c == '\r') then
        if right.text == "," && gap.nonEmpty then gaps(index) = ""
        else if left.text == "," && !Set(")", "]", "}", ">").contains(
            right.text
          ) && gap != " "
        then gaps(index) = " "
    }
    def originalGap(index: Int): String =
      gaps.getOrElse(
        index,
        source.substring(significant(index - 1).end, significant(index).start)
      )
    def spelling(from: Int, until: Int): String =
      (from to until).map { index =>
        (if index == from then "" else originalGap(index)) + significant(
          index
        ).text
      }.mkString
    val newline = if source.contains("\r\n") then "\r\n" else "\n"
    def indentation(at: Int): Int =
      val beginning = source.lastIndexOf('\n', at - 1) + 1
      effectiveBodyIndent.getOrElse(
        beginning,
        settings.columns(
          source.substring(beginning, at).takeWhile(c => c == ' ' || c == '\t')
        )
      )
    var unsafe = Option.empty[String]
    def indent(columns: Int): String = settings.indent(columns) match
      case Some(value) => value
      case None        =>
        unsafe = Some("Indentation cannot be safely represented")
        ""
    def compact(from: Int, until: Int): Unit =
      (from + 1 to until).foreach { index =>
        val gap = originalGap(index)
        if gap.exists(c => c == '\n' || c == '\r') then
          val left = significant(index - 1).text
          val right = significant(index).text
          // parse_term_ops ends a term at a newline before a call/index
          // opener. Erasing that boundary can turn two arguments into an
          // application or index even when the token sequence is unchanged.
          if Set("(", "[").contains(right) &&
            !Set(",", "(", "{", "[").contains(left)
          then unsafe = Some("Significant newline before call or index opener")
          gaps(index) =
            if Set("(", "{", "[").contains(left) || Set(")", "}", "]", ",")
                .contains(right)
            then ""
            else " "
      }
    def overlong(text: String, prefix: Int): Boolean =
      settings.maxLineLength.exists { width =>
        text.split("\n", -1).zipWithIndex.exists { (line, index) =>
          settings.columns((if index == 0 then " " * prefix
                            else "") + line.stripSuffix("\r")) > width
        }
      }
    def expand(list: LayoutList, base: Int): Unit =
      if list.items.nonEmpty then
        compact(list.open, list.close)
        list.items.foreach { (from, until) =>
          gaps(from) = newline + indent(base + settings.indentSize)
          list.children
            .filter(child => child.open >= from && child.close <= until)
            .foreach { child =>
              val whole = spelling(
                from,
                until
              ) + (if until + 1 < list.close && significant(
                       until + 1
                     ).text == ","
                   then ","
                   else "")
              if child.eligible && overlong(whole, base + settings.indentSize)
              then expand(child, base + settings.indentSize)
            }
        }
        gaps(list.close) = newline + indent(base)
    def select(list: LayoutList): Unit =
      val start = source.lastIndexOf('\n', significant(list.open).start - 1) + 1
      val end = source.indexOf('\n', significant(list.close).end) match
        case -1    => source.length
        case value => value
      val from = significant.indexWhere(_.start >= start)
      val signature = list.open >= 2 && significant(list.open - 2).text == "def"
      val until = if signature then significant.lastIndexWhere(_.end <= end)
      else if list.close + 1 < significant.length && significant(
          list.close + 1
        ).text == ":"
      then list.close + 1
      else list.close
      val prefix = effectiveBodyIndent
        .get(start)
        .flatMap(settings.indent)
        .getOrElse(source.substring(start, significant(from).start))
      val region = prefix + spelling(from, until)
      if list.items.nonEmpty && overlong(region, 0) then
        if significant
            .slice(list.open, list.close + 1)
            .exists(_.text.startsWith("#"))
        then unsafe = Some("Comment-sensitive list layout")
        else if significant
            .slice(list.open + 1, list.close)
            .exists(token =>
              Set("do", "match", "case", "return", ";", "%", "\\")
                .contains(token.text)
            )
        then unsafe = Some("Unsupported block expression in list")
        else if structure.exists(atomic =>
            !atomic.eligible && atomic.open > list.open && atomic.close < list.close &&
              source
                .substring(
                  significant(atomic.open).start,
                  significant(atomic.close).end
                )
                .exists(c => c == '\n' || c == '\r')
          )
        then unsafe = Some("Unsupported multiline atomic expression")
        else if source.contains("\r") && source
            .replace("\r\n", "")
            .exists(c => c == '\n' || c == '\r')
        then unsafe = Some("Mixed line endings")
        else
          val separated = list.items.forall((from, _) =>
            originalGap(from).contains('\n')
          ) && originalGap(list.close).contains('\n')
          if separated then list.children.filter(_.eligible).foreach(select)
          else expand(list, indentation(significant(list.open).start))
    if settings.maxLineLength.nonEmpty then
      structure
        .filter(_.eligible)
        .filter { list =>
          !structure.exists(parent =>
            parent.open < list.open && parent.close > list.close &&
              (parent.eligible || Set("{", "<")
                .contains(significant(parent.open).text))
          )
        }
        .foreach(select)
    unsafe match
      case Some(reason) => return Left(reason)
      case None         => ()
    gaps.toList.sortBy(_._1).foreach { (index, replacement) =>
      val left = significant(index - 1)
      val right = significant(index)
      if source.substring(left.end, right.start) != replacement then
        replacements += Edit(left.end, right.start, replacement)
    }

    Right(replacements.toList)

  private final case class LayoutList(
      open: Int,
      close: Int,
      eligible: Boolean,
      items: Vector[(Int, Int)],
      children: Vector[LayoutList]
  )

  /** Delimiter trees retain atomic proofs, annotations and type applications.
    * Only named argument/constructor lists expose sibling comma boundaries.
    */
  private def recognize(
      source: String,
      tokens: Vector[Token]
  ): Either[String, Vector[LayoutList]] = scala.util.boundary:
    val pairs = scala.collection.mutable.Map.empty[Int, Int]
    val stack = scala.collection.mutable.ArrayBuffer.empty[(Int, String)]
    val openings = Set("(", "[", "{")
    val closings = Map(")" -> "(", "]" -> "[", "}" -> "{")
    def name(text: String): Boolean = text.matches("[A-Za-z_][A-Za-z0-9_.]*")
    def angleEnd(index: Int): Boolean =
      var at = index + 1
      var depth = 0
      while at < tokens.length do
        val text = tokens(at).text
        if depth == 0 && text == ">" then return true
        if depth == 0 && (Set(")", "}", "]")
            .contains(text) || Set("def", "law", "type").contains(text))
        then return false
        if openings(text) then depth += 1
        else if closings.contains(text) then depth -= 1
        at += 1
      false
    tokens.indices.foreach { index =>
      val token = tokens(index)
      val angle =
        token.text == "<" && index > 0 && name(tokens(index - 1).text) &&
          tokens(index - 1).end == token.start && (tokens(
            index - 1
          ).text.head.isUpper || angleEnd(index))
      if openings(token.text) || angle then stack += ((index, token.text))
      else if closings.contains(
          token.text
        ) || token.text == ">" && stack.lastOption.exists(_._2 == "<")
      then
        val expected = closings.getOrElse(token.text, "<")
        if stack.lastOption.exists(_._2 == expected) then
          val (open, _) = stack.remove(stack.size - 1)
          pairs(open) = index
        else scala.util.boundary.break(Left("Unrecognized delimiter context"))
    }
    if stack.nonEmpty then
      scala.util.boundary.break(Left("Incomplete type or list syntax"))
    // A continued header is accepted only through balanced groups. A new line
    // outside a group cannot silently turn a missing colon into a valid header.
    tokens.indices
      .filter(index => Set("def", "law", "type").contains(tokens(index).text))
      .foreach { first =>
        val prefix = source.substring(
          source.lastIndexOf('\n', tokens(first).start - 1) + 1,
          tokens(first).start
        )
        if prefix.trim.isEmpty || prefix.trim == "@unsafe" then
          if first + 1 >= tokens.length || !name(tokens(first + 1).text) then
            scala.util.boundary.break(Left("Incomplete declaration name"))
          if tokens(
              first
            ).text == "def" && (first + 2 >= tokens.length || tokens(
              first + 2
            ).text != "(")
          then scala.util.boundary.break(Left("Unsupported definition header"))
          var index = first + 1
          var found = false
          while index < tokens.length && !found do
            if tokens(index).text == ":" then
              if index > 0 && tokens(index - 1).text == "->" then
                scala.util.boundary.break(Left("Incomplete result type"))
              if tokens(first).text == "def" then
                val body = tokens
                  .drop(index + 1)
                  .find(token => !token.text.startsWith("#"))
                if body.isEmpty || body.exists(token =>
                    Set("def", "law", "type").contains(token.text)
                  )
                then
                  scala.util.boundary.break(Left("Incomplete definition body"))
              found = true
            else if tokens(index).text.startsWith("#") then
              scala.util.boundary.break(
                Left("Comment-sensitive declaration header")
              )
            else if pairs.contains(index) then index = pairs(index)
            if !found && index + 1 < tokens.length && source
                .substring(tokens(index).end, tokens(index + 1).start)
                .exists(c => c == '\n' || c == '\r')
            then
              scala.util.boundary.break(Left("Incomplete declaration header"))
            index += 1
          if !found then
            scala.util.boundary.break(Left("Incomplete declaration header"))
      }
    def build(open: Int): Either[String, LayoutList] =
      val close = pairs(open)
      val previous = if open > 0 then tokens(open - 1) else Token("", 0, 0)
      val attached = previous.end == tokens(open).start
      val sameLine = !source
        .substring(previous.end, tokens(open).start)
        .exists(c => c == '\n' || c == '\r')
      val head =
        name(previous.text) || tokens(open).text == "(" && Set(")", "]", "}")
          .contains(previous.text)
      val proofBrace =
        tokens(open).text == "{" && open + 1 < close && Set("=", "==").contains(
          tokens(open + 1).text
        )
      val eligible = Set("(", "{").contains(
        tokens(open).text
      ) && head && (attached || tokens(
        open
      ).text == "(" && sameLine) && !proofBrace &&
        !Set("match", "case", "if", "switch", "do", "rewrite", "with", "return")
          .contains(previous.text)
      val commas = Vector.newBuilder[Int]
      val children = Vector.newBuilder[LayoutList]
      var index = open + 1
      while index < close do
        if pairs.contains(index) then
          build(index) match
            case Left(reason) => scala.util.boundary.break(Left(reason))
            case Right(child) => children += child
          index = pairs(index) + 1
        else
          if tokens(index).text == "," then commas += index
          index += 1
      val separators = commas.result()
      if separators.nonEmpty && !eligible && tokens(open).text != "<" && tokens(
          open
        ).text != "["
      then scala.util.boundary.break(Left("Unsupported comma-separated syntax"))
      val bounds = Vector(open) ++ separators ++ Vector(close)
      val rawItems = if close == open + 1 then Vector.empty
      else bounds.sliding(2).map(pair => (pair(0) + 1, pair(1) - 1)).toVector
      val items = if separators.lastOption.contains(close - 1) then
        rawItems.dropRight(1)
      else rawItems
      if items.exists((from, until) => from > until) then
        scala.util.boundary.break(Left("Incomplete comma-separated list"))
      if eligible && items.exists((_, until) =>
          Set(
            ":",
            "->",
            "=>",
            "+",
            "-",
            "*",
            "/",
            "%",
            "<",
            ">",
            "<=",
            ">=",
            "&&",
            "||",
            "&",
            "|",
            "^",
            "=",
            "==",
            "!=",
            "~"
          ).contains(tokens(until).text) && !pairs.valuesIterator.contains(
            until
          )
        )
      then scala.util.boundary.break(Left("Incomplete list item"))
      Right(LayoutList(open, close, eligible, items, children.result()))
    val all = Vector.newBuilder[LayoutList]
    def collect(list: LayoutList): Unit =
      all += list
      list.children.foreach(collect)
    pairs.keys.toVector.sorted
      .filter(open =>
        !pairs.exists((parent, close) => parent < open && close > open)
      )
      .foreach { open =>
        build(open) match
          case Left(reason) => scala.util.boundary.break(Left(reason))
          case Right(list)  => collect(list)
      }
    Right(all.result())

  private def scan(source: String): Either[String, Vector[Token]] =
    val tokens = Vector.newBuilder[Token]
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
        var scalars = 0
        while index < source.length && !closed do
          val current = source.charAt(index)
          if current != char && !Character.isLowSurrogate(current) then
            scalars += 1
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
              else
                val codepoint = java.lang.Long
                  .parseLong(source.substring(digitsStart, index), 16)
                if codepoint > 0x10ffffL || codepoint >= 0xd800L && codepoint <= 0xdfffL
                then unsafe = true
                index += 1
            else
              unsafe = true
              index += 1
          else if current == char then
            index += 1
            closed = true
          else index += 1
        if !closed || char == '\'' && scalars != 1 then unsafe = true
        tokens += Token(source.substring(start, index), start, index)
      else if char.isLetterOrDigit || char == '_' then
        index += 1
        while index < source.length && (source
            .charAt(index)
            .isLetterOrDigit || "_.".contains(source.charAt(index)))
        do index += 1
        tokens += Token(source.substring(start, index), start, index)
      else if List(
          "<&>",
          "->",
          "=>",
          "==",
          "!=",
          "<=",
          ">=",
          "<-",
          "&&",
          "||",
          "::"
        ).exists(operator => source.startsWith(operator, index))
      then
        val operator = List(
          "<&>",
          "->",
          "=>",
          "==",
          "!=",
          "<=",
          ">=",
          "<-",
          "&&",
          "||",
          "::"
        ).find(operator => source.startsWith(operator, index)).get
        index += operator.length
        tokens += Token(operator, start, index)
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
