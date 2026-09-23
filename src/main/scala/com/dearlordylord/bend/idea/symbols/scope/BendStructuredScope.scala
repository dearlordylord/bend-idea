package com.dearlordylord.bend.idea.symbols.scope

/** Case and do continuation regions, computed from the same source tokens as ordinary locals.
  * Columns are measured from the original text: a nested match owns its own rows and a do
  * continuation accepts another statement only at the first statement's column or after `;`.
  */
private[scope] object BendStructuredScope:
  final case class Region(from: Int, until: Int, doBlock: Boolean = false)
  final case class Result(bindings: List[ScopeBinding], regions: List[Region],
      consumedAssignments: Set[Int]):
    def regionEnd(offset: Int, fallback: Int): Int =
      regions.filter(r => r.from <= offset && offset < r.until)
        .map(_.until).minOption.getOrElse(fallback)

  def inspect(source: String, tokens: Vector[ScopeToken], end: Int): Result =
    val bindings = List.newBuilder[ScopeBinding]
    val regions = List.newBuilder[Region]
    val assignments = Set.newBuilder[Int]
    val closes = enclosingCloses(tokens, end)
    val (owners, matchParents) = caseOwners(source, tokens)

    for i <- tokens.indices if owners(i) >= 0 do
      val colon = headingColon(tokens, i + 1)
      colon.foreach { headEnd =>
        val nextCase = ((i + 1) until tokens.size).find(j =>
          owners(j) >= 0 && tokens(j).start < closes(i) &&
            (owners(j) == owners(i) || isAncestor(owners(j), owners(i), matchParents)))
        val until = math.min(closes(i), nextCase.map(j => tokens(j).start).getOrElse(end))
        val from = tokens(headEnd).end
        if from < until then
          regions += Region(from, until)
          val pattern = tokens.slice(i + 1, headEnd)
          pattern.indices.foreach { p =>
            val token = pattern(p)
            val constructor = p + 1 < pattern.size && pattern(p + 1).text == "{"
            if token.name && token.text != "_" && !constructor then
              val prefix = if p > 0 && pattern(p - 1).text == "+" &&
                  pattern(p - 1).end == token.start then pattern(p - 1).start else token.start
              bindings += ScopeBinding(token.text, token.start,
                source.substring(prefix, token.end), BindingOrigin.Pattern, from, until)
          }
      }

    val caseRegions = regions.result()
    for i <- tokens.indices if tokens(i).text == "do" do
      headingColon(tokens, i + 1).foreach { colon =>
        val first = colon + 1
        if first < tokens.size then
          val column = col(source, tokens(first).start)
          val caseCap = caseRegions.filter(r => r.from <= tokens(i).start &&
            tokens(i).start < r.until).map(_.until).minOption.getOrElse(end)
          val cap = math.min(closes(i), caseCap)
          if tokens(first).start < cap then
            val pending = List.newBuilder[(ScopeToken, Int, Int)]
            var start = first
            var until = cap
            var done = false
            while start < tokens.size && tokens(start).start < cap && !done do
              var cursor = start + 1
              var depth = if Set("(", "[", "{").contains(tokens(start).text) then 1 else 0
              var separator = -1
              val typedStart = tokens(start).name &&
                tokens.lift(start + 1).exists(_.text == ":") ||
                Set("+", "-").contains(tokens(start).text) &&
                  tokens.lift(start + 1).exists(_.name) &&
                  tokens.lift(start + 2).exists(_.text == ":")
              while cursor < tokens.size && tokens(cursor).start < cap && separator < 0 do
                val token = tokens(cursor)
                val prefix = tokens.slice(start, cursor)
                val assignment = if typedStart then prefix.indexWhere(t => t.text == "=" || t.text == "<-") else -1
                val rhsStarted = !typedStart || assignment >= 0 && assignment + 1 < prefix.size
                if depth == 0 && (token.text == ";" ||
                    newLine(source, tokens(cursor - 1), token) && rhsStarted) then separator = cursor
                else
                  token.text match
                    case "(" | "[" | "{" => depth += 1
                    case ")" | "]" | "}" => depth = math.max(0, depth - 1)
                    case _ => ()
                  cursor += 1
              val stop = if separator < 0 then cursor else separator
              val next = if separator < 0 then cursor else if tokens(separator).text == ";" then separator + 1 else separator
              val statement = tokens.slice(start, stop)
              val bind = doAssignment(statement)
              bind.foreach { case (relative, _) => assignments += start + relative }
              val hasNext = next < tokens.size && tokens(next).start < cap
              val hasRhs = bind.exists { case (relative, _) => relative + 1 < statement.size }
              if hasNext && hasRhs then
                bind.filter { case (relative, _) => relative + 1 < statement.size }.foreach { case (_, name) =>
                  val prefix = if statement.head.text == "+" || statement.head.text == "-" then
                    statement.head.start else name.start
                  pending += ((name, prefix, tokens(next).start))
                }
              val step = separator >= 0 && (tokens(separator).text == ";" ||
                col(source, tokens(separator).start) == column)
              if hasNext && (hasRhs || bind.isEmpty && statement.headOption.exists(_.text != "return") && step) then
                start = next
              else
                until = if separator >= 0 then tokens(separator).start else cap
                done = true
            regions += Region(tokens(first).start, until, doBlock = true)
            pending.result().foreach { case (name, prefix, from) =>
              if from < until then bindings += ScopeBinding(name.text, name.start,
                source.substring(prefix, name.end), BindingOrigin.Do, from, until)
            }
      }

    // parse_body's term_write turns a continued Array.set into a local binder for
    // the array argument. parse_term_do_stmt has no corresponding rewrite.
    val allRegions = regions.result()
    for i <- tokens.indices if (tokens(i).text == "Array.set" ||
        (tokens(i).name && i + 1 < tokens.size && tokens(i + 1).text == "[")) &&
        standaloneTermStart(source, tokens, i) &&
        !allRegions.exists(r => r.doBlock && r.from <= tokens(i).start && tokens(i).start < r.until) do
      val column = col(source, tokens(i).start)
      var depth = 0
      var cursor = i + 1
      var boundary = -1
      while cursor < tokens.size && tokens(cursor).start < closes(i) && boundary < 0 do
        val token = tokens(cursor)
        if depth == 0 && (token.text == ";" ||
            (newLine(source, tokens(cursor - 1), token) && col(source, token.start) == column)) then
          boundary = cursor
        else
          token.text match
            case "(" | "[" | "{" => depth += 1
            case ")" | "]" | "}" => depth = math.max(0, depth - 1)
            case _ => ()
          cursor += 1
      if boundary >= 0 then
        val next = if tokens(boundary).text == ";" then boundary + 1 else boundary
        if next < tokens.size then
          arrayWriteBinder(tokens.slice(i, boundary)).foreach { name =>
            val until = math.min(closes(i), allRegions.filter(r =>
              r.from <= tokens(i).start && tokens(i).start < r.until).map(_.until).minOption.getOrElse(end))
            bindings += ScopeBinding(name.text, name.start, name.text,
              BindingOrigin.Let, tokens(next).start, until)
          }
    Result(bindings.result(), allRegions, assignments.result())

  /** A match accepts its first row only when it is deeper than the surrounding case.
    * Later rows may be at any column at least as deep as that first row. When a row
    * returns to an outer column, the innermost match no longer owns it.
    */
  private def caseOwners(source: String, tokens: Vector[ScopeToken]): (Array[Int], Map[Int, Int]) =
    final case class MatchFrame(id: Int, firstColumn: Int, var rowColumn: Int)
    val owners = Array.fill(tokens.size)(-1)
    val parents = scala.collection.mutable.Map.empty[Int, Int]
    val stack = scala.collection.mutable.ArrayBuffer.empty[MatchFrame]
    for i <- tokens.indices do
      if tokens(i).text == "match" then
        headingColon(tokens, i + 1).foreach { colon =>
          val first = colon + 1
          val parentColumn = stack.lastOption.map(_.rowColumn).getOrElse(0)
          if first < tokens.size && tokens(first).text == "case" &&
              col(source, tokens(first).start) > parentColumn then
            stack.lastOption.foreach(parent => parents(i) = parent.id)
            stack += MatchFrame(i, col(source, tokens(first).start), parentColumn)
        }
      else if tokens(i).text == "case" then
        val column = col(source, tokens(i).start)
        while stack.nonEmpty && column < stack.last.firstColumn do stack.remove(stack.size - 1)
        stack.lastOption.foreach { frame =>
          owners(i) = frame.id
          frame.rowColumn = column
        }
    (owners, parents.toMap)

  private def isAncestor(candidate: Int, owner: Int, parents: Map[Int, Int]): Boolean =
    var current = parents.get(owner)
    while current.nonEmpty do
      if current.contains(candidate) then return true
      current = current.flatMap(parents.get)
    false

  private def doAssignment(statement: Vector[ScopeToken]): Option[(Int, ScopeToken)] =
    val nameAt = if statement.headOption.exists(t => t.text == "+" || t.text == "-") then 1 else 0
    if nameAt >= statement.size || !statement(nameAt).name ||
        nameAt + 1 >= statement.size || statement(nameAt + 1).text != ":" then None
    else
      var depth = 0
      statement.indices.drop(nameAt + 2).find { i =>
        statement(i).text match
          case "(" | "[" | "{" | "<" => depth += 1; false
          case ")" | "]" | "}" | ">" => depth = math.max(0, depth - 1); false
          case "=" | "<-" if depth == 0 => true
          case _ => false
      }.map(i => (i, statement(nameAt)))

  private def arrayWriteBinder(statement: Vector[ScopeToken]): Option[ScopeToken] =
    if statement.headOption.exists(_.name) && statement.lift(1).exists(_.text == "[") then
      val close = statement.indexWhere(_.text == "]", 2)
      if close > 2 && statement.lift(close + 1).exists(_.text == "<-") &&
          statement.lift(close + 2).nonEmpty then statement.headOption
      else None
    else if statement.headOption.forall(_.text != "Array.set") then None
    else
      val open = statement.indexWhere(_.text == "(")
      if open < 0 then None
      else
        var depth = 0
        var firstComma = -1
        var secondComma = -1
        for i <- open + 1 until statement.size do
          statement(i).text match
            case "(" | "[" | "{" => depth += 1
            case ")" | "]" | "}" => depth -= 1
            case "," if depth == 0 && firstComma < 0 => firstComma = i
            case "," if depth == 0 && secondComma < 0 => secondComma = i
            case _ => ()
        if firstComma >= 0 && secondComma == firstComma + 2 &&
            statement(firstComma + 1).name then Some(statement(firstComma + 1)) else None

  private def standaloneTermStart(source: String, tokens: Vector[ScopeToken], at: Int): Boolean =
    if at == 0 then true
    else
      val previous = tokens(at - 1)
      previous.text == ";" ||
        (previous.text == ":" && tokens.indices.take(at - 1).exists(j =>
          tokens(j).text == "case" && headingColon(tokens, j + 1).contains(at - 1))) ||
        (newLine(source, previous, tokens(at)) &&
          !Set("=", "<-", ",", "(", "[", "{").contains(previous.text))

  private def headingColon(tokens: Vector[ScopeToken], from: Int): Option[Int] =
    var depth = 0
    var i = from
    while i < tokens.size do
      tokens(i).text match
        case "(" | "[" | "{" | "<" => depth += 1
        case ")" | "]" | "}" | ">" => depth = math.max(0, depth - 1)
        case ":" if depth == 0 => return Some(i)
        case "case" | "match" | "do" if depth == 0 && i > from => return None
        case _ => ()
      i += 1
    None

  private def enclosingCloses(tokens: Vector[ScopeToken], end: Int): Array[Int] =
    val limits = Array.fill(tokens.size)(end)
    val stack = scala.collection.mutable.ArrayBuffer.empty[Int]
    for i <- tokens.indices do
      tokens(i).text match
        case "(" | "[" | "{" => stack += i
        case ")" | "]" | "}" if stack.nonEmpty =>
          val open = stack.remove(stack.size - 1)
          for j <- open + 1 until i do limits(j) = math.min(limits(j), tokens(i).start)
        case _ => ()
    limits

  private def col(source: String, offset: Int): Int =
    offset - source.lastIndexOf('\n', offset - 1)

  private def newLine(source: String, left: ScopeToken, right: ScopeToken): Boolean =
    source.substring(left.end, right.start).contains('\n')
