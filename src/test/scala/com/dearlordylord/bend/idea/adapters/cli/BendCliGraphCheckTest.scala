package com.dearlordylord.bend.idea.adapters.cli

import com.dearlordylord.bend.idea.analysis.api.BendGraphSnapshot
import com.dearlordylord.bend.idea.analysis.model.*
import com.dearlordylord.bend.idea.model.FileId
import com.dearlordylord.bend.idea.toolchain.api.BendToolchainSelection
import com.dearlordylord.bend.idea.workspace.api.{BendImportLines, BendWorkspaceGraph}
import com.dearlordylord.bend.idea.workspace.model.BendSourceRecord
import com.dearlordylord.bend.idea.workspace.ports.BendSourceCatalog
import java.nio.file.{Files, Path}
import org.junit.Assert.*
import org.junit.Test

/** Exercises the pinned Bend checker against closed, unsaved source graphs. */
final class BendCliGraphCheckTest:
  private lazy val compiler = RealBendCompilerFixture.inputs
  private def base: Path = compiler.base

  private def fixture(run: (Path, Path) => Unit): Unit =
    val directory = Files.createTempDirectory("bend-graph-check-")
    try
      val executable = directory.resolve("bend")
      compiler.writeLauncher(executable)
      run(directory, executable)
    finally
      val files = Files.walk(directory)
      try files.sorted(java.util.Comparator.reverseOrder()).forEach(p => Files.deleteIfExists(p))
      finally files.close()

  private def source(path: Path, text: String): BendSourceRecord =
    val canonical = if Files.exists(path) then path.toRealPath() else path.toAbsolutePath.normalize()
    BendSourceRecord(new FileId(canonical.toString, Files.exists(path)), path.toString,
      text, 7L, BendImportLines.parse(text))

  private def check(directory: Path, executable: Path, root: BendSourceRecord,
      overrides: Map[String, BendSourceRecord] = Map.empty,
      laws: Option[BendSourceRecord] = None,
      configuredBase: Path = base): BendCheckResult =
    val cache = directory.resolve("lib")
    val catalog = new BendSourceCatalog:
      override def source(path: String): Option[BendSourceRecord] =
        overrides.get(path).orElse {
          val file = Path.of(path)
          Option.when(Files.isRegularFile(file))(()).map(_ =>
            BendCliGraphCheckTest.this.source(file, Files.readString(file)))
        }
    val graph = BendWorkspaceGraph.load(root, configuredBase.toString, cache.toString, catalog)
    val selection = BendToolchainSelection(executable.toString, configuredBase.toString,
      cache.toString, true, 1L)
    val initial = BendCheckSnapshot(root.id, root.path, root.text, root.revision, selection)
    val snapshot = BendGraphSnapshot.attach(initial, graph, laws)
    new BendCliCheckBackend(directory).check(snapshot)

  private def snapshotCount(directory: Path): Long =
    val stream = Files.list(directory)
    try stream.filter(_.getFileName.toString.startsWith("bend-editor-check-")).count()
    finally stream.close()

  @Test def unsavedDependencyErrorMapsToItsOriginalFileAndLeavesDiskUntouched(): Unit = fixture { (dir, bend) =>
    val dependency = dir.resolve("math.bend")
    val original = "import Base\ndef square() -> U32:\n  1\n"
    Files.writeString(dependency, original)
    val root = source(dir.resolve("main.bend"),
      "import ./math.bend as M\ndef main() -> U32:\n  M.square()\n")
    val edited = source(dependency, "import Base\ndef square() -> U32:\n  unknown_value\n")
    val before = snapshotCount(dir)
    val result = check(dir, bend, root, Map(dependency.toString -> edited))
    assertEquals(BendCheckOutcome.Failed, result.outcome)
    assertEquals(BendCompleteness.Unknown, result.completeness)
    assertEquals(BendReliance.Unknown, result.reliance)
    assertEquals(BendLocation.SourceLine(edited.id, 2), result.diagnostics.head.location)
    assertEquals(original, Files.readString(dependency))
    assertEquals(before, snapshotCount(dir))
    assertTrue(result.mappings.exists(_.source == edited.id))
    val rootMap = result.mappings.find(_.source == root.id).get
    assertEquals(1, rootMap.rewrites.size)
    val rewrite = rootMap.rewrites.head
    assertEquals("./math.bend", rootMap.originalText.substring(
      rewrite.originalStart, rewrite.originalEnd))
    assertTrue(rootMap.copiedText.substring(rewrite.copiedStart, rewrite.copiedEnd)
      .endsWith("math.bend"))
    val afterImport = rootMap.copiedText.indexOf("def main")
    assertEquals(Some(rootMap.originalText.indexOf("def main")),
      rootMap.originalOffset(afterImport))
    assertTrue(rootMap.compilerText.startsWith("\n"))
    assertEquals(None, rootMap.copiedOffset(0))
    assertEquals(Some(afterImport), rootMap.copiedOffset(rootMap.compilerText.indexOf("def main")))
  }

  @Test def absoluteAndCachedImportsResolveAliasesOffline(): Unit = fixture { (dir, bend) =>
    val absolute = dir.resolve("math.bend")
    val cached = dir.resolve("lib/0xabc/value.bend")
    Files.createDirectories(cached.getParent)
    Files.writeString(absolute, "import Base\ndef square() -> U32:\n  1\n")
    Files.writeString(cached, "import Base\ndef value() -> U32:\n  2\n")
    val root = source(dir.resolve("main.bend"),
      s"import ${absolute.toString} as M\nimport 0xabc/value.bend as C\ndef main() -> U32:\n  U32.add(M.square(), C.value())\n")
    val result = check(dir, bend, root)
    assertEquals(result.details, BendCheckOutcome.Success, result.outcome)
    assertEquals(BendCompleteness.Complete, result.completeness)
    val baseId = new FileId(base.toRealPath().toString, true)
    assertTrue("Matching Base remains a captured input", result.sources.exists(_.id == baseId))
    assertFalse("Base is read from the compiler installation, not a copied source",
      result.mappings.exists(_.source == baseId))
  }

  @Test def graphCheckNeverRunsSideEffectingMain(): Unit = fixture { (dir, bend) =>
    val dependency = dir.resolve("math.bend")
    val marker = dir.resolve("must-not-exist")
    Files.writeString(dependency, "import Base\ndef value() -> U32:\n  1\n")
    val root = source(dir.resolve("main.bend"),
      s"import Base\nimport ./math.bend as M\ndef main() -> IO(Unit):\n  do IO<Unit>:\n    file : File <- IO.try(File, File.open(\"$marker\", \"w\"))\n    File.close(file)\n")
    val result = check(dir, bend, root)
    assertEquals(result.details, BendCheckOutcome.Success, result.outcome)
    assertFalse(Files.exists(marker))
  }

  @Test def missingHashImportDoesNotCreateCacheOrStartCompiler(): Unit = fixture { (dir, bend) =>
    val root = source(dir.resolve("main.bend"), "import 0xabc/missing.bend as M\n")
    val result = check(dir, bend, root)
    assertEquals(BendCheckOutcome.Failed, result.outcome)
    assertTrue(result.details.contains("Cannot read imported module"))
    assertEquals(BendLocation.SourceLine(root.id, 0), result.diagnostics.head.location)
    assertFalse(Files.exists(dir.resolve("lib")))
    assertEquals(0L, snapshotCount(dir))
  }

  @Test def proofGuardObservesSiblingLawsEvenWithoutImport(): Unit = fixture { (dir, bend) =>
    val laws = dir.resolve("LAWS.bend")
    Files.writeString(laws, "law claim() -> Type\n")
    val root = source(dir.resolve("PROOF.bend"), "def main() -> Type:\n  Type\n")
    val result = check(dir, bend, root, laws = Some(source(laws, Files.readString(laws))))
    assertEquals(BendCheckOutcome.Failed, result.outcome)
    assertTrue(result.details.contains("PROOF.bend must import ./LAWS.bend"))
  }

  @Test def proofGuardAcceptsImportedSibling(): Unit = fixture { (dir, bend) =>
    val lawsPath = dir.resolve("LAWS.bend")
    Files.writeString(lawsPath, "# laws imported by this proof\n")
    val laws = source(lawsPath, Files.readString(lawsPath))
    val root = source(dir.resolve("PROOF.bend"),
      "import ./LAWS.bend as Laws\ndef main() -> Type:\n  Type\n")
    val result = check(dir, bend, root, laws = Some(laws))
    assertEquals(result.details, BendCheckOutcome.Success, result.outcome)
  }

  @Test def symlinkNamespaceConflictIsRejectedBeforeRewrite(): Unit = fixture { (dir, bend) =>
    val module = dir.resolve("math.bend")
    val alias = dir.resolve("alias.bend")
    Files.writeString(module, "def item() -> Type:\n  Type\n")
    Files.createSymbolicLink(alias, module.getFileName)
    val root = source(dir.resolve("main.bend"),
      "import ./math.bend as M\nimport ./alias.bend as A\n")
    val result = check(dir, bend, root)
    assertEquals(BendCheckOutcome.Failed, result.outcome)
    assertTrue(result.details.contains("One namespace per file"))
    assertEquals(0L, snapshotCount(dir))
  }

  @Test def nestedDiamondKeepsOneCompilerNamespaceForSharedModule(): Unit = fixture { (dir, bend) =>
    val nested = dir.resolve("nested")
    Files.createDirectory(nested)
    Files.writeString(dir.resolve("shared.bend"),
      "def value() -> Type:\n  Type\n")
    Files.writeString(nested.resolve("a.bend"),
      "import ../shared.bend as Shared\ndef item() -> Type:\n  Shared.value()\n")
    Files.writeString(dir.resolve("b.bend"),
      "import ./shared.bend as Shared\ndef item() -> Type:\n  Shared.value()\n")
    val root = source(dir.resolve("main.bend"),
      "import ./nested/a.bend as A\nimport ./b.bend as B\ndef main() -> Type:\n  A.item()\n")
    val result = check(dir, bend, root)
    assertEquals(result.details, BendCheckOutcome.Success, result.outcome)
  }

  @Test def identicalExcerptsStayOnRoot(): Unit = fixture { (dir, bend) =>
    val a = dir.resolve("a.bend")
    val b = dir.resolve("b.bend")
    val text = "def broken() -> Type:\n  unknown_name\n"
    Files.writeString(a, text)
    Files.writeString(b, text)
    val root = source(dir.resolve("main.bend"),
      "import ./a.bend as A\nimport ./b.bend as B\n")
    val result = check(dir, bend, root)
    assertEquals(BendCheckOutcome.Failed, result.outcome)
    assertEquals(BendLocation.RootOnly, result.diagnostics.head.location)
  }

  @Test def baseWithImportsIsRejectedBeforeAnySourceCommand(): Unit = fixture { (dir, bend) =>
    val baseWithImport = dir.resolve("base-with-import.bend")
    val text = "import 0xabc/dependency.bend as Dep\n"
    Files.writeString(baseWithImport, text)
    val marker = dir.resolve("source-invoked")
    Files.writeString(bend,
      s"#!/bin/sh\nif [ \"$$1\" = \"--help\" ]; then echo '  bend <file.bend> --check-only check the file and its imports; run nothing'; exit 0; fi\nif [ \"$$1\" = \"base\" ]; then printf 'import 0xabc/dependency.bend as Dep\\n'; exit 0; fi\ntouch '$marker'\n")
    val root = source(dir.resolve("main.bend"), "import Base\n")
    val selection = BendToolchainSelection(bend.toString, baseWithImport.toString,
      dir.resolve("lib").toString, true, 1L)
    val snapshot = BendCheckSnapshot(root.id, root.path, root.text, root.revision, selection)
    val result = new BendCliCheckBackend(dir).check(snapshot)
    assertEquals(BendCheckOutcome.Unavailable, result.outcome)
    assertTrue(result.details.contains("closed offline snapshot"))
    assertFalse(Files.exists(marker))
  }

  @Test def byteIdenticalConfiguredBaseAlsoImportedByPathIsUnavailable(): Unit = fixture { (dir, bend) =>
    val copiedBase = dir.resolve("BaseCopy.bend")
    Files.copy(base, copiedBase)
    val root = source(dir.resolve("main.bend"),
      s"import Base\nimport ${copiedBase.toString} as Copy\ndef main() -> Type:\n  Type\n")
    val result = check(dir, bend, root, configuredBase = copiedBase)
    assertEquals(BendCheckOutcome.Unavailable, result.outcome)
    assertTrue(result.details.contains("identity relative to the compiler's built-in Base"))
    assertFalse(result.details.contains("One namespace per file"))
    assertEquals(0L, snapshotCount(dir))
  }
