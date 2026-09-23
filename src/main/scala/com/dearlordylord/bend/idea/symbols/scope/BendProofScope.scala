package com.dearlordylord.bend.idea.symbols.scope

/** Proof binders from the shared token stream. The policy is tolerant of unfinished terms. */
private[scope] object BendProofScope:
  final case class LawClause(name: String, nameOffset: Int, sourceSpecification: String)

  def lawClauses(source: String, tokens: Vector[ScopeToken]): List[LawClause] =
    clauses(source, tokens).map { case (name, colon, boundary, _) =>
      // `where P` changes the clause into a packed witness. Keep it in the
      // source specification instead of advertising the domain as the fill type.
      val stop = tokens.lift(boundary).map(_.start)
        .getOrElse(tokens.lastOption.map(_.end).getOrElse(source.length))
      LawClause(name.text, name.start, source.substring(tokens(colon).end, stop).trim)
    }

  def bindings(source: String, tokens: Vector[ScopeToken], headerEnd: Int,
      end: Int, law: Boolean): List[ScopeBinding] =
    val out = List.newBuilder[ScopeBinding]
    val closes = enclosingCloses(tokens, end)
    val lawClauses = if law then clauses(source, tokens) else Nil
    if law then
      lawClauses.foreach { case (name, colon, boundary, prefix) =>
        if name.text != "_" then
          val where = topLevel(tokens, colon + 1,
            tokens.lift(boundary).map(_.start).getOrElse(end), "where")
          val from = where.map(i => tokens(i).end)
            .orElse(tokens.lift(boundary).map(_.start)).getOrElse(end)
          out += ScopeBinding(name.text, name.start, source.substring(prefix, name.end),
            BindingOrigin.Parameter, from, end)
      }
    for i <- tokens.indices do
      if Set("@", "@+", "@-", "&").contains(tokens(i).text) &&
          !tokens.lift(i - 1).exists(t => t.name && t.end == tokens(i).start) then
        val nameAt = (i + 1 until math.min(tokens.size, i + 4)).find(j =>
          tokens(j).name && tokens.slice(i + 1, j).forall(t => Set("+", "-", "~").contains(t.text)))
        nameAt.foreach { j =>
          if tokens.lift(j + 1).exists(_.text == ":") then
            val arrow = topLevel(tokens, j + 2, closes(i), "->")
            arrow.foreach { k =>
              val clauseCap = lawClauses.find { case (_, colon, boundary, _) =>
                colon < i && i < boundary
              }.map { case (_, colon, boundary, _) =>
                val boundaryOffset = tokens.lift(boundary).map(_.start).getOrElse(end)
                topLevel(tokens, colon + 1, boundaryOffset, "where")
                  .filter(_ > i).map(j => tokens(j).start).getOrElse(boundaryOffset)
              }.getOrElse(end)
              val cap = if tokens(i).start < headerEnd then math.min(headerEnd, closes(i))
                else math.min(closes(i), clauseCap)
              val prefix = if j > 0 && Set("+", "-", "~").contains(tokens(j - 1).text) then
                tokens(j - 1).start else tokens(j).start
              out += ScopeBinding(tokens(j).text, tokens(j).start,
                source.substring(prefix, tokens(j).end), BindingOrigin.Parameter,
                tokens(k).end, cap)
            }
        }

      if tokens(i).text == "%" then
        topLevel(tokens, i + 1, closes(i), ":").foreach { colon =>
          val semi = topLevel(tokens, colon + 1, closes(i), ";")
          val nextLine = ((colon + 1) until tokens.size).find(j =>
            tokens(j).start < closes(i) && line(source, tokens(j).start) > line(source, tokens(colon).start) &&
              col(source, tokens(j).start) <= col(source, tokens(i).start))
          val cap = List(Some(closes(i)), semi.map(j => tokens(j).start),
            nextLine.map(j => tokens(j).start)).flatten.min
          val named = tokens.lift(i + 1).filter(_.name).filter(_ => tokens.lift(i + 2).exists(_.text == "@"))
          named.foreach { name =>
            out += ScopeBinding(name.text, name.start, name.text,
              BindingOrigin.Parameter, tokens(colon).end, cap)
          }
          // In a rewrite motive '_' is an implicit placeholder even though an ordinary
          // underscore is a nonbinding wildcard. Give it its own source-local locator.
          if tokens.exists(t => t.text == "_" && t.start >= tokens(colon).end && t.start < cap) then
            out += ScopeBinding("_", tokens(i).start, "_", BindingOrigin.Parameter,
              tokens(colon).end, cap)
        }
    out.result()

  private def clauses(source: String, tokens: Vector[ScopeToken]): List[(ScopeToken, Int, Int, Int)] =
    val out = List.newBuilder[(ScopeToken, Int, Int, Int)]
    for i <- tokens.indices if Set("for", "exs").contains(tokens(i).text) do
      val nameAt = (i + 1 until math.min(tokens.size, i + 4)).find(j =>
        tokens(j).name && tokens.slice(i + 1, j).forall(t => Set("+", "-", "~").contains(t.text)))
      nameAt.foreach { j =>
        if tokens.lift(j + 1).exists(_.text == ":") then
          val next = ((j + 2) until tokens.size).find(k =>
            (line(source, tokens(k).start) > line(source, tokens(j).start) &&
              col(source, tokens(k).start) <= col(source, tokens(i).start) &&
              tokens(k).text != "where") ||
              Set("for", "exs").contains(tokens(k).text))
          val prefix = if j > 0 && Set("+", "-", "~").contains(tokens(j - 1).text) then
            tokens(j - 1).start else tokens(j).start
          out += ((tokens(j), j + 1, next.getOrElse(tokens.size), prefix))
      }
    out.result()

  private def topLevel(tokens: Vector[ScopeToken], from: Int, cap: Int,
      wanted: String): Option[Int] =
    var depth = 0
    var i = from
    while i < tokens.size && tokens(i).start < cap do
      if tokens(i).text == wanted && depth == 0 then return Some(i)
      tokens(i).text match
        case "(" | "[" | "{" | "<" => depth += 1
        case ")" | "]" | "}" | ">" => depth = math.max(0, depth - 1)
        case _ => ()
      i += 1
    None

  private def enclosingCloses(tokens: Vector[ScopeToken], end: Int): Array[Int] =
    val closes = Array.fill(tokens.size)(end)
    val stack = scala.collection.mutable.ArrayBuffer.empty[Int]
    for i <- tokens.indices do
      tokens(i).text match
        case "(" | "[" | "{" => stack += i
        case ")" | "]" | "}" if stack.nonEmpty =>
          val open = stack.remove(stack.size - 1)
          for j <- open + 1 until i do closes(j) = tokens(i).start
        case _ => ()
    closes

  private def line(source: String, offset: Int): Int =
    source.take(offset).count(_ == '\n')

  private def col(source: String, offset: Int): Int =
    offset - source.lastIndexOf('\n', offset - 1)
