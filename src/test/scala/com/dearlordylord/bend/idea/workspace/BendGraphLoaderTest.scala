package com.dearlordylord.bend.idea.workspace

import com.dearlordylord.bend.idea.model.FileId
import com.dearlordylord.bend.idea.workspace.api.BendImportLines
import com.dearlordylord.bend.idea.workspace.api.BendSourceCatalog
import com.dearlordylord.bend.idea.workspace.loading.BendGraphLoader
import com.dearlordylord.bend.idea.workspace.model.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.{Files, Path}

final class BendGraphLoaderTest:
  private def record(
      path: String,
      text: String,
      canonical: String = ""
  ): BendSourceRecord =
    BendSourceRecord(
      new FileId(if canonical.isEmpty then path else canonical, true),
      path,
      text,
      1L,
      BendImportLines.parse(text)
    )

  @Test def diamondDeduplicatesByCanonicalIdentityAndReportsNamespaceConflict()
      : Unit =
    val root =
      record("/p/main.bend", "import ./a.bend as A\nimport ./b.bend as B\n")
    val a = record("/p/a.bend", "import ./shared.bend as S\n")
    val b = record("/p/b.bend", "import ./link.bend as Other\n")
    val shared =
      record("/p/shared.bend", "def leaf():\n  0\n", "/p/shared.bend")
    val link = record("/p/link.bend", shared.text, "/p/shared.bend")
    val catalog = mapCatalog(root, a, b, shared, link)
    val graph = BendGraphLoader.load(
      root,
      BendGraphLoader.Config("/base.bend", "/cache"),
      catalog
    )
    assertEquals(1, graph.files.count(_.source.id == shared.id))
    assertTrue(
      graph.problems.exists(_.isInstanceOf[BendGraphProblem.NamespaceConflict])
    )
    assertTrue(graph.edges.exists(_.importLine.spelling == "./link.bend"))

  @Test def cycleAndMissingRemainProblemsWithUsefulFiles(): Unit =
    val root = record(
      "/p/main.bend",
      "import ./a.bend as A\nimport ./missing.bend as M\n"
    )
    val a = record("/p/a.bend", "import ./main.bend as R\ndef own():\n  0\n")
    val graph = BendGraphLoader.load(
      root,
      BendGraphLoader.Config("/base.bend", "/cache"),
      mapCatalog(root, a)
    )
    assertTrue(graph.problems.exists(_.isInstanceOf[BendGraphProblem.Cycle]))
    assertTrue(graph.problems.exists(_.isInstanceOf[BendGraphProblem.Missing]))
    assertEquals(2, graph.files.size)

  @Test def cachedHashAndAbsolutePathsRetainSpelling(): Unit =
    val root = record(
      "/p/main.bend",
      "import 0xabc/lib.bend as H\nimport /abs/lib.bend as X\n"
    )
    val hash = record("/cache/0xabc/lib.bend", "def hash():\n  0\n")
    val absolute = record("/abs/lib.bend", "def absolute():\n  0\n")
    val graph = BendGraphLoader.load(
      root,
      BendGraphLoader.Config("/base.bend", "/cache"),
      mapCatalog(root, hash, absolute)
    )
    assertEquals(
      List("/cache/0xabc/lib.bend", "/abs/lib.bend"),
      graph.edges.map(_.requestedPath)
    )
    assertEquals(
      List("0xabc/lib.bend", "/abs/lib.bend"),
      graph.edges.map(_.importLine.spelling)
    )

  @Test def malformedLeadingImportIsRetainedAsProblem(): Unit =
    val root =
      record("/p/main.bend", "import ./missing.bend\ndef main():\n  0\n")
    val graph = BendGraphLoader.load(
      root,
      BendGraphLoader.Config("/base.bend", "/cache"),
      mapCatalog(root)
    )
    assertTrue(
      graph.problems.exists(_.isInstanceOf[BendGraphProblem.InvalidImport])
    )
    assertEquals("./missing.bend", root.imports.head.spelling)

  @Test def duplicateNormalizedSpellingsWithSameNamespaceLoadOnce(): Unit =
    val root = record(
      "/p/main.bend",
      "import ./shared.bend as S\nimport ././shared.bend as S\n"
    )
    val shared = record("/p/shared.bend", "def leaf():\n  0\n")
    val graph = BendGraphLoader.load(
      root,
      BendGraphLoader.Config("/base.bend", "/cache"),
      mapCatalog(root, shared)
    )
    assertTrue(graph.problems.isEmpty)
    assertEquals(2, graph.files.size)
    assertEquals(
      List("./shared.bend", "././shared.bend"),
      graph.edges.map(_.importLine.spelling)
    )

  @Test def parentSegmentsRemainInRootRelativeNamespace(): Unit =
    val root = record("/p/sub/main.bend", "import ../shared.bend as S\n")
    val shared = record("/p/shared.bend", "def leaf():\n  0\n")
    val graph = BendGraphLoader.load(
      root,
      BendGraphLoader.Config("/base.bend", "/cache"),
      mapCatalog(root, shared)
    )
    assertEquals("/p/shared.bend", graph.edges.head.requestedPath)
    assertEquals("../shared", graph.edges.head.namespace)

  @Test def repeatedAndMissingImportsHaveBoundedSourceLookups(): Unit =
    val repeated = List.fill(1024)("import ./shared.bend as S\n").mkString
    val missing =
      (1 to 1024).map(i => s"import ./missing$i.bend as M$i\n").mkString
    val repeatedMissing =
      List.fill(1024)("import ./absent.bend as A\n").mkString
    val root = record("/p/main.bend", repeated + repeatedMissing + missing)
    val shared = record("/p/shared.bend", "def leaf():\n  0\n")
    var lookups = List.empty[String]
    val catalog = new BendSourceCatalog:
      override def source(path: String): Option[BendSourceRecord] =
        lookups = path :: lookups
        Option.when(path == shared.path)(shared)
    val graph = BendGraphLoader.load(
      root,
      BendGraphLoader.Config("/base.bend", "/cache"),
      catalog
    )
    assertEquals(
      "Repeated paths are captured once per load",
      1,
      lookups.count(_ == shared.path)
    )
    assertEquals(
      "Missing paths are also cached per load",
      1,
      lookups.count(_ == "/p/absent.bend")
    )
    assertTrue(
      "Missing paths also consume the lookup budget",
      lookups.size <= 256
    )
    assertEquals(
      Some(shared.id),
      graph.importTarget(root.id, root.imports(1023).offset)
    )
    assertTrue(graph.problems.exists {
      case BendGraphProblem.InvalidImport(_, _, reason) =>
        reason == "source lookup limit exceeded"
      case _ => false
    })
    assertEquals(None, graph.importTarget(root.id, root.imports.last.offset))

  @Test def realSymlinkDeduplicatesAndRejectsTwoNamespaces(): Unit =
    val dir = Files.createTempDirectory("bend-graph-link")
    try
      val target = dir.resolve("shared.bend")
      val link = dir.resolve("link.bend")
      val rootPath = dir.resolve("main.bend")
      Files.writeString(target, "def leaf():\n  0\n")
      Files.createSymbolicLink(link, target)
      Files.writeString(
        rootPath,
        "import ./shared.bend as First\nimport ./link.bend as Second\n"
      )
      val catalog = new BendSourceCatalog:
        override def source(path: String): Option[BendSourceRecord] =
          val requested = Path.of(path)
          Option.when(Files.exists(requested)) {
            record(
              path,
              Files.readString(requested),
              requested.toRealPath().toString
            )
          }
      val root = catalog.source(rootPath.toString).get
      val graph = BendGraphLoader.load(
        root,
        BendGraphLoader.Config("/base.bend", "/cache"),
        catalog
      )
      assertEquals(2, graph.files.size)
      assertTrue(
        graph.problems.exists(
          _.isInstanceOf[BendGraphProblem.NamespaceConflict]
        )
      )
      assertEquals(graph.edges.head.target, graph.edges(1).target)
    finally
      val paths = Files.walk(dir)
      try
        paths
          .sorted(java.util.Comparator.reverseOrder())
          .forEach(p => {
            val _ = Files.deleteIfExists(p)
          })
      finally paths.close()

  private def mapCatalog(records: BendSourceRecord*): BendSourceCatalog =
    val byPath = records.map(r => r.path -> r).toMap
    new BendSourceCatalog:
      override def source(path: String): Option[BendSourceRecord] =
        byPath.get(path)
