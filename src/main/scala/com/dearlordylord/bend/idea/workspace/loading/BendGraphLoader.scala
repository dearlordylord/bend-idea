package com.dearlordylord.bend.idea.workspace.loading

import com.dearlordylord.bend.idea.model.FileId
import com.dearlordylord.bend.idea.workspace.model.*
import com.dearlordylord.bend.idea.workspace.api.BendImportPaths
import com.dearlordylord.bend.idea.workspace.api.BendSourceCatalog
import scala.collection.mutable

/** Ordered, bounded traversal matching book_load's canonical namespaces. The
  * catalog owns I/O; this policy never fetches missing hash packages.
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
    val cache = catalog.canonicalPath(paths.packageCache)
    val rootPath = if root.id.canonical then root.id.value
    else catalog.canonicalPath(root.path)
    val cachedPackages = mutable.LinkedHashMap.empty[String, Option[String]]
    val files = mutable.ListBuffer.empty[BendLoadedFile]
    val edges = mutable.ListBuffer.empty[BendLoadedEdge]
    val problems = mutable.ListBuffer.empty[BendGraphProblem]
    val sourceInventoryLimits = mutable.Set.empty[BendGraphLimit]
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
          target(
            source,
            imp,
            paths.copy(packageCache = cache),
            catalog,
            cachedPackages,
            maxFiles
          ) match
            case Left(reason) =>
              if reason == "package lookup limit exceeded" then
                sourceInventoryLimits += BendGraphLimit.SourceLookups
              problems += BendGraphProblem.InvalidImport(source.id, imp, reason)
            case Right(path)
                if !sources.contains(path) && sources.size >= maxFiles =>
              sourceInventoryLimits += BendGraphLimit.SourceLookups
              problems += BendGraphProblem.InvalidImport(
                source.id,
                imp,
                "source lookup limit exceeded"
              )
            case Right(path) =>
              val loaded = sources.getOrElseUpdate(path, catalog.source(path))
              val childNamespace =
                if imp.spelling == "Base" && imp.alias.isEmpty then ""
                else
                  BendImportPaths.canonicalNamespace(
                    rootPath,
                    loaded.map(_.id.value).getOrElse(path),
                    cache
                  )
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
                    case Some(Some(_))                 => ()
                    case None if seen.size >= maxFiles =>
                      sourceInventoryLimits += BendGraphLimit.GraphFiles
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
    BendLoadedGraph(
      root.id,
      files.toList,
      edges.toList,
      problems.toList,
      sourceInventoryLimits.toSet,
      cache,
      cachedPackages.iterator
        .map((name, hash) => BendCachedPackage(name, hash))
        .toList
    )

  private def target(
      source: BendSourceRecord,
      imp: BendImport,
      paths: Config,
      catalog: BendSourceCatalog,
      cachedPackages: mutable.Map[String, Option[String]],
      maxLookups: Int
  ): Either[String, String] =
    if imp.syntaxProblem.nonEmpty then Left(imp.syntaxProblem.get)
    else if imp.spelling == "Base" && imp.alias.isEmpty then Right(paths.base)
    else if imp.alias.isEmpty then Left("non-Base imports require an alias")
    else if !imp.spelling.endsWith(".bend") then
      Left("an import of a .bend file")
    else
      val named = BendImportPaths.namedPackage(imp.spelling)
      if named.exists(name =>
          !cachedPackages.contains(name) && cachedPackages.size >= maxLookups
        )
      then Left("package lookup limit exceeded")
      else resolveTarget(source, imp, paths, catalog, cachedPackages, named)

  private def resolveTarget(
      source: BendSourceRecord,
      imp: BendImport,
      paths: Config,
      catalog: BendSourceCatalog,
      cachedPackages: mutable.Map[String, Option[String]],
      named: Option[String]
  ): Either[String, String] =
    val hash = named.flatMap(name =>
      cachedPackages.getOrElseUpdate(
        name,
        catalog
          .cachedPackageHash(paths.packageCache, name)
          .filter(BendImportPaths.isPackageHash)
      )
    )
    if named.nonEmpty && hash.isEmpty then
      Left(
        "Named Bend package is not available in the local cache: " + named.get
      )
    else if imp.spelling.takeWhile(_ != '/').contains('@') && named.isEmpty then
      Left("Invalid Bend package name or version")
    else
      val spelling = hash.fold(imp.spelling)(
        BendImportPaths.withPackageHash(imp.spelling, _)
      )
      Right(
        BendImportPaths.target(
          if source.id.canonical then source.id.value
          else catalog.canonicalPath(source.path),
          paths.packageCache,
          spelling
        )
      )
