package com.dearlordylord.bend.idea.workspace.api

/** Pure spellings shared by native file refactoring and PSI references. */
object BendSourcePathEdits:
  private final case class Parts(root: String, segments: Vector[String])

  def relativeSpelling(sourceFile: String, targetFile: String): Option[String] =
    for
      source <- normalized(sourceFile)
      target <- normalized(targetFile)
      if source.root == target.root
      parent = source.segments.dropRight(1)
      shared = parent.zip(target.segments).takeWhile(_ == _).size
      upward = Vector.fill(parent.size - shared)("..")
      spelling = (upward ++ target.segments.drop(shared)).mkString("/")
    yield if spelling.isEmpty then "." else spelling

  def withFileName(spelling: String, newName: String): Option[String] =
    val name = newName.replace('\\', '/')
    if name.isEmpty || name.contains("/") then None
    else
      val normalizedSpelling = spelling.replace('\\', '/')
      val separator = normalizedSpelling.lastIndexOf('/')
      Some(if separator < 0 then name
      else normalizedSpelling.take(separator + 1) + name)

  private def normalized(value: String): Option[Parts] =
    val spelling = value.replace('\\', '/')
    val drive = spelling.length >= 2 && spelling.charAt(1) == ':'
    val root =
      if drive then spelling.take(2).toLowerCase
      else if spelling.startsWith("/") then "/"
      else ""
    val remainder = if drive then spelling.drop(2) else spelling
    val stack = remainder.split('/').foldLeft(Vector.empty[String]) {
      case (parts, "" | ".")                                     => parts
      case (parts, "..") if parts.nonEmpty && parts.last != ".." =>
        parts.dropRight(1)
      case (parts, "..") if root.isEmpty => parts :+ ".."
      case (parts, "..")                 => parts
      case (parts, segment)              => parts :+ segment
    }
    Option.when(value.nonEmpty)(Parts(root, stack))
