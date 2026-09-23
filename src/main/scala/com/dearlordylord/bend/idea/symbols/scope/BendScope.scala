package com.dearlordylord.bend.idea.symbols.scope

/** Lexer-derived source token. Offsets are UTF-16 offsets in the current document. */
final case class ScopeToken(text: String, start: Int, end: Int, name: Boolean)

enum BindingOrigin:
  case Parameter, Lambda, Let, Pattern, Do

/** A source binder is eligible only in [from, until). Its spelling retains quantity/template syntax. */
final case class ScopeBinding(name: String, nameOffset: Int, source: String,
    origin: BindingOrigin, from: Int, until: Int)

/** Pure, tolerant lexical scope policy over the shared lexer's token projection. */
object BendScope:
  def lawClauses(source: String, tokens: Vector[ScopeToken]): List[BendProofScope.LawClause] =
    BendProofScope.lawClauses(source, tokens)

  def bindings(source: String, tokens: Vector[ScopeToken], headerEnd: Int,
      declarationEnd: Int, law: Boolean = false): List[ScopeBinding] =
    val header = tokens.filter(_.start < headerEnd)
    val body = tokens.filter(_.start >= headerEnd)
    val limit = bodyLimit(source, body, headerEnd, declarationEnd)
    val active = body.filter(_.start < limit)
    val structured = BendStructuredScope.inspect(source, active, limit)
    parameterBindings(source, header, limit) ++
      bodyBindings(source, active, limit, structured) ++ structured.bindings ++
      BendProofScope.bindings(source, tokens.filter(_.start < limit), headerEnd, limit, law)

  /** The surface parser deliberately retains malformed neighbors; a fresh column-zero line
    * outside delimiters is no longer in this declaration's body.
    */
  private def bodyLimit(source: String, body: Vector[ScopeToken], headerEnd: Int,
      declarationEnd: Int): Int =
    var depth = 0
    var previous = headerEnd
    var i = 0
    while i < body.size do
      val token = body(i)
      if depth == 0 && source.substring(previous, token.start).contains('\n') &&
          lineIndent(source, token.start) == 0 then return token.start
      token.text match
        case "(" | "[" | "{" => depth += 1
        case ")" | "]" | "}" => depth = math.max(0, depth - 1)
        case _ => ()
      previous = token.end
      i += 1
    declarationEnd

  def visible(bindings: List[ScopeBinding], offset: Int): List[ScopeBinding] =
    bindings.filter(b => b.from <= offset && offset < b.until)
      .sortBy(b => (-b.from, -b.nameOffset))
      .foldLeft(List.empty[ScopeBinding]) { (seen, next) =>
        if seen.exists(_.name == next.name) then seen else seen :+ next
      }

  private def parameterBindings(source: String, tokens: Vector[ScopeToken], until: Int): List[ScopeBinding] =
    val open = tokens.indexWhere(_.text == "(")
    if open < 0 then Nil
    else
      val close = matching(tokens, open).getOrElse(tokens.size)
      val sections = splitAtCommas(tokens, open + 1, close)
      sections.flatMap { case (first, last) =>
        val part = tokens.slice(first, last)
        val colon = part.indexWhere(_.text == ":")
        val head = if colon < 0 then part else part.take(colon)
        val binder = head.find(_.name)
        binder.filter(_.text != "_").map { name =>
          val from = if last < tokens.size then tokens(last).end else tokens.lastOption.map(_.end).getOrElse(until)
          ScopeBinding(name.text, name.start, source.substring(part.head.start, part.last.end),
            BindingOrigin.Parameter, from, until)
        }
      }.toList

  private def bodyBindings(source: String, tokens: Vector[ScopeToken], end: Int,
      structured: BendStructuredScope.Result): List[ScopeBinding] =
    val out = List.newBuilder[ScopeBinding]
    val enclosing = Array.fill(tokens.size)(end)
    val stack = scala.collection.mutable.ArrayBuffer.empty[Int]
    for i <- tokens.indices do
      tokens(i).text match
        case "(" | "[" | "{" => stack += i
        case ")" | "]" | "}" if stack.nonEmpty =>
          val open = stack.remove(stack.size - 1)
          // A caret just before the closing delimiter is still inside the body.
          for j <- open + 1 until i do enclosing(j) = math.min(enclosing(j), tokens(i).start + 1)
        case _ => ()

    for i <- tokens.indices do
      if tokens(i).text == "=>" && i > 0 && tokens(i - 1).name then
        val name = tokens(i - 1)
        val quantity = if i > 1 && Set("+", "-").contains(tokens(i - 2).text) &&
            tokens(i - 2).end == name.start then tokens(i - 2).start else name.start
        val stop = expressionEnd(source, tokens, i + 1, enclosing(i), end)
        out += ScopeBinding(name.text, name.start, source.substring(quantity, name.end), BindingOrigin.Lambda,
          tokens(i).end, stop)
      if tokens(i).text == "=" && !structured.consumedAssignments.contains(i) then
        val start = statementStart(source, tokens, i)
        val left = tokens.slice(start, i)
        val colon = left.indexWhere(_.text == ":")
        val binders = (if colon < 0 then left else left.take(colon))
          .filter(_.name)
        val valid = binders.nonEmpty && left.nonEmpty &&
          (colon >= 0 || left.forall(t => t.name || Set("-", "+", "~").contains(t.text)))
        if valid then
          val boundary = statementEnd(source, tokens, i + 1, enclosing(i), end)
          val from = boundary._1
          val stop = math.min(enclosing(i), structured.regionEnd(tokens(i).start, end))
          binders.foreach { name =>
            val previous = left.indexWhere(_.start == name.start) - 1
            val start = if previous >= 0 && Set("-", "+", "~").contains(left(previous).text) &&
                left(previous).end == name.start then left(previous).start else name.start
            out += ScopeBinding(name.text, name.start, source.substring(start, name.end),
              BindingOrigin.Let, from, stop)
          }
    out.result()

  private def statementStart(source: String, tokens: Vector[ScopeToken], at: Int): Int =
    var j = at - 1
    while j >= 0 && tokens(j).text != ";" && tokens(j).text != "(" && tokens(j).text != "{" &&
        !source.substring(tokens(j).end, tokens(j + 1).start).contains('\n') do j -= 1
    j + 1

  /** First token after RHS, or an unfinished RHS's enclosing boundary. */
  private def statementEnd(source: String, tokens: Vector[ScopeToken], from: Int,
      enclosingEnd: Int, fallback: Int): (Int, Int) =
    var i = from
    var depth = 0
    val indent = if from > 0 then lineIndent(source, tokens(from - 1).start) else 0
    while i < tokens.size && tokens(i).start < enclosingEnd do
      val token = tokens(i)
      if depth == 0 then
        if token.text == ";" then return (token.end, i + 1)
        if i > from && source.substring(tokens(i - 1).end, token.start).contains('\n') &&
            lineIndent(source, token.start) <= indent then return (token.start, i)
      token.text match
        case "(" | "[" | "{" => depth += 1
        case ")" | "]" | "}" => depth -= 1
        case _ => ()
      i += 1
    (math.min(enclosingEnd, fallback), i)

  private def expressionEnd(source: String, tokens: Vector[ScopeToken], from: Int,
      enclosingEnd: Int, fallback: Int): Int =
    var i = from
    var depth = 0
    val indent = if from > 0 then lineIndent(source, tokens(from - 1).start) else 0
    while i < tokens.size && tokens(i).start < enclosingEnd do
      val token = tokens(i)
      if depth == 0 then
        if token.text == "," || token.text == ";" then return token.start + 1
        if i > from && source.substring(tokens(i - 1).end, token.start).contains('\n') &&
            lineIndent(source, token.start) <= indent then return token.start
      token.text match
        case "(" | "[" | "{" => depth += 1
        case ")" | "]" | "}" => depth -= 1
        case _ => ()
      i += 1
    math.min(enclosingEnd, fallback)

  private def lineIndent(source: String, offset: Int): Int =
    val start = source.lastIndexOf('\n', offset - 1) + 1
    source.substring(start, offset).takeWhile(c => c == ' ' || c == '\t').length

  private def splitAtCommas(tokens: Vector[ScopeToken], first: Int, last: Int): Vector[(Int, Int)] =
    val parts = Vector.newBuilder[(Int, Int)]
    var start = first
    var depth = 0
    for i <- first until last do
      tokens(i).text match
        case "(" | "[" | "{" => depth += 1
        case ")" | "]" | "}" => depth -= 1
        case op if op.startsWith("<") && op != "<=" && op != "<-" => depth += op.takeWhile(_ == '<').length
        case op if op.nonEmpty && op.forall(_ == '>') => depth -= op.length
        case "," if depth == 0 => parts += ((start, i)); start = i + 1
        case _ => ()
    parts += ((start, last))
    parts.result().filter((a, b) => a < b)

  private def matching(tokens: Vector[ScopeToken], open: Int): Option[Int] =
    var depth = 0
    var i = open
    while i < tokens.size do
      tokens(i).text match
        case "(" => depth += 1
        case ")" =>
          depth -= 1
          if depth == 0 then return Some(i)
        case _ => ()
      i += 1
    None
