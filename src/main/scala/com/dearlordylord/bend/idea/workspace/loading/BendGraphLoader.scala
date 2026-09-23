package com.dearlordylord.bend.idea.workspace.loading

import com.dearlordylord.bend.idea.model.FileId
import com.dearlordylord.bend.idea.workspace.model.*
import com.dearlordylord.bend.idea.workspace.api.BendImportPaths
import com.dearlordylord.bend.idea.workspace.ports.BendSourceCatalog
import scala.collection.mutable

/** Ordered, bounded traversal matching book_load's realpath and namespace checks.
  * The catalog owns I/O; this policy never fetches missing hash packages.
  */
object BendGraphLoader:
  final case class Config(base: String, packageCache: String)

  def load(root: BendSourceRecord, paths: Config, catalog: BendSourceCatalog,
      maxFiles: Int = 256, canceled: () => Boolean = () => false): BendLoadedGraph =
    val files = mutable.ListBuffer.empty[BendLoadedFile]
    val edges = mutable.ListBuffer.empty[BendLoadedEdge]
    val problems = mutable.ListBuffer.empty[BendGraphProblem]
    val seen = mutable.Map.empty[FileId, Option[String]]
    def visit(source: BendSourceRecord, namespace: String): Unit =
      if canceled() then return
      seen(source.id) = None
      // Compiler imports are processed before the current file's declarations.
      source.imports.foreach { imp =>
        if !canceled() then target(source, namespace, imp, paths) match
          case Left(reason) => problems += BendGraphProblem.InvalidImport(source.id, imp, reason)
          case Right((path, childNamespace)) =>
            val loaded = catalog.source(path)
            edges += BendLoadedEdge(source.id, imp, path, childNamespace, loaded.map(_.id))
            loaded match
              case None => problems += BendGraphProblem.Missing(source.id, imp, path)
              case Some(child) => seen.get(child.id) match
                case Some(None) => problems += BendGraphProblem.Cycle(source.id, imp, child.id)
                case Some(Some(previous)) if previous != childNamespace =>
                  problems += BendGraphProblem.NamespaceConflict(source.id, imp, child.id,
                    previous, childNamespace)
                case Some(Some(_)) => ()
                case None if seen.size >= maxFiles =>
                  problems += BendGraphProblem.InvalidImport(source.id, imp, "graph limit exceeded")
                case None => visit(child, childNamespace)
      }
      seen(source.id) = Some(namespace)
      files += BendLoadedFile(source, namespace)
    visit(root, "")
    BendLoadedGraph(root.id, files.toList, edges.toList, problems.toList)

  private def target(source: BendSourceRecord, namespace: String, imp: BendImport,
      paths: Config): Either[String, (String, String)] =
    if imp.syntaxProblem.nonEmpty then Left(imp.syntaxProblem.get)
    else if imp.spelling == "Base" && imp.alias.isEmpty then Right((paths.base, ""))
    else if imp.alias.isEmpty then Left("non-Base imports require an alias")
    else if !imp.spelling.endsWith(".bend") then Left("an import of a .bend file")
    else
      val rel = normalize(imp.spelling)
      val hash = rel.matches("^0x[0-9a-f]+/.*")
      val resolved = BendImportPaths.target(source.path, paths.packageCache, imp.spelling)
      val sub = if hash || rel.startsWith("/") then rel
        else if parent(namespace).isEmpty then normalize(rel)
        else normalize(parent(namespace) + "/" + rel)
      Right((resolved, sub.stripSuffix(".bend")))

  private def parent(path: String): String =
    val slash = path.lastIndexOf('/')
    if slash < 0 then "" else path.substring(0, slash)

  private def normalize(path: String): String =
    val absolute = path.startsWith("/")
    val stack = mutable.ArrayBuffer.empty[String]
    path.split('/').foreach {
      case "" | "." => ()
      case ".." if stack.nonEmpty && stack.last != ".." => stack.remove(stack.size - 1)
      case ".." if !absolute => stack += ".."
      case ".." => ()
      case segment => stack += segment
    }
    (if absolute then "/" else "") + stack.mkString("/")
