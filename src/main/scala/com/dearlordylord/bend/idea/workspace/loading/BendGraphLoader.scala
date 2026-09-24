package com.dearlordylord.bend.idea.workspace.loading

import com.dearlordylord.bend.idea.model.FileId
import com.dearlordylord.bend.idea.workspace.model.*
import com.dearlordylord.bend.idea.workspace.api.BendImportPaths
import com.dearlordylord.bend.idea.workspace.api.BendSourceCatalog
import scala.collection.mutable

/** Ordered, bounded traversal matching book_load's realpath and namespace
  * checks. The catalog owns I/O; this policy never fetches missing hash
  * packages.
  */
object BendGraphLoader:
  final case class Config(base: String, packageCache: String)

  def load(
      root: BendSourceRecord,
      paths: Config,
      catalog: BendSourceCatalog,
      maxFiles: Int = 256,
      canceled: () => Boolean = () => false
  ): BendLoadedGraph =
    val files = mutable.ListBuffer.empty[BendLoadedFile]
    val edges = mutable.ListBuffer.empty[BendLoadedEdge]
    val problems = mutable.ListBuffer.empty[BendGraphProblem]
    val seen = mutable.Map.empty[FileId, Option[String]]
    // Bound catalog access, including failed lookups, before doing I/O. Reuse
    // one capture per requested path within this load, never across revisions.
    val sources = mutable.Map.empty[String, Option[BendSourceRecord]]
    def visit(source: BendSourceRecord, namespace: String): Unit =
      if canceled() then return
      seen(source.id) = None
      // Compiler imports are processed before the current file's declarations.
      source.imports.foreach { imp =>
        if !canceled() then
          target(source, namespace, imp, paths) match
            case Left(reason) =>
              problems += BendGraphProblem.InvalidImport(source.id, imp, reason)
            case Right((path, _))
                if !sources.contains(path) && sources.size >= maxFiles =>
              problems += BendGraphProblem.InvalidImport(
                source.id,
                imp,
                "source lookup limit exceeded"
              )
            case Right((path, childNamespace)) =>
              val loaded = sources.getOrElseUpdate(path, catalog.source(path))
              edges += BendLoadedEdge(
                source.id,
                imp,
                path,
                childNamespace,
                loaded.map(_.id)
              )
              loaded match
                case None =>
                  problems += BendGraphProblem.Missing(source.id, imp, path)
                case Some(child) =>
                  seen.get(child.id) match
                    case Some(None) =>
                      problems += BendGraphProblem.Cycle(
                        source.id,
                        imp,
                        child.id
                      )
                    case Some(Some(previous)) if previous != childNamespace =>
                      problems += BendGraphProblem.NamespaceConflict(
                        source.id,
                        imp,
                        child.id,
                        previous,
                        childNamespace
                      )
                    case Some(Some(_))                 => ()
                    case None if seen.size >= maxFiles =>
                      problems += BendGraphProblem.InvalidImport(
                        source.id,
                        imp,
                        "graph limit exceeded"
                      )
                    case None => visit(child, childNamespace)
      }
      seen(source.id) = Some(namespace)
      files += BendLoadedFile(source, namespace)
    visit(root, "")
    BendLoadedGraph(root.id, files.toList, edges.toList, problems.toList)

  private def target(
      source: BendSourceRecord,
      namespace: String,
      imp: BendImport,
      paths: Config
  ): Either[String, (String, String)] =
    if imp.syntaxProblem.nonEmpty then Left(imp.syntaxProblem.get)
    else if imp.spelling == "Base" && imp.alias.isEmpty then
      Right((paths.base, ""))
    else if imp.alias.isEmpty then Left("non-Base imports require an alias")
    else if !imp.spelling.endsWith(".bend") then
      Left("an import of a .bend file")
    else
      val resolved =
        BendImportPaths.target(source.path, paths.packageCache, imp.spelling)
      Right((resolved, BendImportPaths.namespace(namespace, imp.spelling)))
