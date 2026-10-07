package com.dearlordylord.bend.idea.adapters.cli

import com.dearlordylord.bend.idea.adapters.process.BendBoundedProcess
import com.dearlordylord.bend.idea.analysis.api.BendGraphSnapshot
import com.dearlordylord.bend.idea.analysis.model.*
import com.dearlordylord.bend.idea.model.FileId
import com.dearlordylord.bend.idea.toolchain.api.BendToolchainSelection
import com.dearlordylord.bend.idea.workspace.api.{
  BendImportLines,
  BendSourceCatalog,
  BendWorkspaceGraph
}
import com.dearlordylord.bend.idea.workspace.model.BendSourceRecord
import java.nio.file.{Files, Path}
import com.google.gson.{JsonNull, JsonObject}
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
      val _ = compiler.writeLauncher(executable)
      run(directory, executable)
    finally
      val files = Files.walk(directory)
      try
        files
          .sorted(java.util.Comparator.reverseOrder())
          .forEach(p => {
            val _ = Files.deleteIfExists(p)
          })
      finally files.close()

  private def source(path: Path, text: String): BendSourceRecord =
    val canonical = if Files.exists(path) then path.toRealPath()
    else path.toAbsolutePath.normalize()
    BendSourceRecord(
      new FileId(canonical.toString, Files.exists(path)),
      path.toString,
      text,
      7L,
      BendImportLines.parse(text)
    )

  private def check(
      directory: Path,
      executable: Path,
      root: BendSourceRecord,
      overrides: Map[String, BendSourceRecord] = Map.empty,
      laws: Option[BendSourceRecord] = None,
      configuredBase: Path = base,
      goalRequested: Boolean = false,
      normalizationRequest: Option[BendNormalizationRequest] = None,
      lint: com.dearlordylord.bend.idea.toolchain.api.BendLintOptions =
        com.dearlordylord.bend.idea.toolchain.api.BendLintOptions(),
      lintRequested: Boolean = false
  ): BendCheckResult =
    val cache = directory.resolve("lib")
    val catalog = new BendSourceCatalog:
      override def canonicalPath(path: String): String =
        if Files.exists(Path.of(path)) then Path.of(path).toRealPath().toString
        else path
      override def cachedPackageHash(
          packageCache: String,
          name: String
      ): Option[String] =
        val path = Path.of(packageCache).resolve("names").resolve(name)
        Option.when(Files.isRegularFile(path))(Files.readString(path).trim)
      override def source(path: String): Option[BendSourceRecord] =
        overrides
          .get(path)
          .orElse(overrides.values.find(_.id.value == path))
          .orElse {
            val file = Path.of(path)
            Option
              .when(Files.isRegularFile(file))(())
              .map(_ =>
                BendCliGraphCheckTest.this.source(file, Files.readString(file))
              )
          }
    val graph = BendWorkspaceGraph.load(
      root,
      configuredBase.toString,
      cache.toString,
      catalog
    )
    val selection = BendToolchainSelection(
      executable.toString,
      configuredBase.toString,
      cache.toString,
      true,
      1L,
      lint = lint
    )
    val initial =
      BendCheckSnapshot(
        root.id,
        root.path,
        root.text,
        root.revision,
        selection,
        goalRequested = goalRequested,
        normalizationRequest = normalizationRequest,
        lintRequested = lintRequested
      )
    val snapshot = BendGraphSnapshot.attach(initial, graph, laws)
    new BendCliCheckBackend(directory).check(snapshot)

  private def snapshotCount(directory: Path): Long =
    val stream = Files.list(directory)
    try
      stream
        .filter(_.getFileName.toString.startsWith("bend-editor-check-"))
        .count()
    finally stream.close()

  @Test def pinnedHelperMapsRealFirstErrorToUnsavedDependencyRange(): Unit =
    fixture { (dir, bend) =>
      val _ = compiler.writeStructuredLauncher(bend, structuredChecks = true)
      val dependency = dir.resolve("math.bend")
      Files.writeString(
        dependency,
        "import Base\ndef square() -> U32:\n  1\n"
      )
      val root = source(
        dir.resolve("main.bend"),
        "import ./math.bend as M\ndef main() -> U32:\n  M.square()\n"
      )
      val edited = source(
        dependency,
        "import Base\n# 😀\ndef square() -> U32:\n  unknown_value\n"
      )
      val result = check(dir, bend, root, Map(dependency.toString -> edited))
      assertEquals(BendCheckOutcome.Failed, result.outcome)
      val start = edited.text.indexOf("unknown_value")
      assertEquals(
        BendLocation.SourceRange(edited.id, BendTextRange(start, start + 13)),
        result.diagnostics.head.location
      )
      assertTrue(start > edited.text.codePointCount(0, start))
      assertEquals(
        "import Base\ndef square() -> U32:\n  1\n",
        Files.readString(dependency)
      )
    }

  @Test def compatibleSemanticProviderNeedsNoPinnedCompilerIdentity(): Unit =
    fixture { (dir, bend) =>
      val helper = dir.resolve("structured-bend")
      val _ = compiler.writeStructuredLauncher(helper, structuredChecks = true)
      Files.writeString(
        bend,
        s"""#!/bin/sh
        |if [ "$${1:-}" = "--idea-structured-capabilities" ]; then
        |  '${helper}' "$$@" | sed 's/bend-2.0.35-pinned/bend-next-development-build/'
        |else
        |  exec '${helper}' "$$@"
        |fi
        |""".stripMargin
      )
      val root = source(
        dir.resolve("main.bend"),
        "def choose(A: Type, x: A) -> A:\n  ?need\n"
      )
      val result = check(dir, bend, root, goalRequested = true)
      assertEquals(BendCompleteness.Incomplete, result.completeness)
      val goal =
        result.goal.getOrElse(throw new AssertionError("No compiler goal"))
      assertEquals("A", goal.expectedType)
      assertEquals(Some(List("x")), goal.compatibleBindings)
    }

  @Test def pinnedHelperReturnsDependentGoalFromUnsavedImportedSource(): Unit =
    fixture { (dir, bend) =>
      val _ = compiler.writeStructuredLauncher(bend, structuredChecks = true)
      val dependency = dir.resolve("proof.bend")
      Files.writeString(dependency, "def choose(A: Type, x: A) -> A:\n  x\n")
      val editedText = "def choose(A: Type, x: A) -> A:\n  ?need\n"
      val edited = source(dependency, editedText)
      val root = source(
        dir.resolve("main.bend"),
        "import ./proof.bend as P\n"
      )
      val ordinary = check(dir, bend, root, Map(dependency.toString -> edited))
      assertEquals(BendCompleteness.Incomplete, ordinary.completeness)
      assertTrue(ordinary.goal.isEmpty)
      val result = check(
        dir,
        bend,
        root,
        Map(dependency.toString -> edited),
        goalRequested = true
      )
      assertEquals(BendCheckOutcome.Failed, result.outcome)
      assertEquals(BendCompleteness.Incomplete, result.completeness)
      val goal =
        result.goal.getOrElse(throw new AssertionError("No compiler goal"))
      val start = editedText.indexOf("?need")
      assertEquals(edited.id, goal.source)
      assertEquals(BendTextRange(start, start + 5), goal.range)
      assertEquals("need", goal.holeName)
      assertEquals("A", goal.expectedType)
      assertEquals(
        List("A" -> "Type", "x" -> "A"),
        goal.context.map(binding => binding.name -> binding.typeText)
      )
      assertEquals(Some(List("x")), goal.compatibleBindings)
      assertEquals(
        "def choose(A: Type, x: A) -> A:\n  x\n",
        Files.readString(dependency)
      )
    }

  @Test def pinnedHelperMapsCheckedApplicationAndDependentBinderTypes(): Unit =
    fixture { (dir, bend) =>
      val _ = compiler.writeStructuredLauncher(bend, structuredChecks = true)
      val dependency = dir.resolve("types.bend")
      Files.writeString(dependency, "def id(A: Type, x: A) -> A:\n  x\n")
      val editedText = "# 😀\ndef id(A: Type, x: A) -> A:\n  x\n"
      val edited = source(dependency, editedText)
      val rootText =
        "import Base\nimport ./types.bend as T\ndef use() -> U32:\n  T.id(U32, 7)\n"
      val root = source(dir.resolve("main.bend"), rootText)
      val result = check(dir, bend, root, Map(dependency.toString -> edited))
      assertEquals(result.details, BendCheckOutcome.Success, result.outcome)
      assertTrue(
        result.expressionTypes.toString,
        result.expressionTypes.exists(entry =>
          entry.source == root.id && entry.range == BendTextRange(
            rootText.indexOf("T.id(U32, 7)"),
            rootText.indexOf("T.id(U32, 7)") + "T.id(U32, 7)".length
          ) && entry.typeText == "U32"
        )
      )
      assertTrue(
        result.expressionTypes.exists(entry =>
          entry.source == edited.id && entry.range == BendTextRange(
            editedText.lastIndexOf("x"),
            editedText.length - 1
          ) && entry.typeText == "A"
        )
      )
      assertEquals(
        "def id(A: Type, x: A) -> A:\n  x\n",
        Files.readString(dependency)
      )
    }

  @Test def failedCheckAndUnsupportedHelperExposeNoExpressionTypes(): Unit =
    fixture { (dir, bend) =>
      val _ = compiler.writeStructuredLauncher(bend)
      val failed = source(
        dir.resolve("failed.bend"),
        "import Base\ndef use() -> U32:\n  missing\n"
      )
      assertTrue(check(dir, bend, failed).expressionTypes.isEmpty)
      val _ = compiler.writeLauncher(bend)
      val valid = source(
        dir.resolve("valid.bend"),
        "import Base\ndef use() -> U32:\n  7\n"
      )
      val result = check(dir, bend, valid)
      assertEquals(BendCheckOutcome.Success, result.outcome)
      assertTrue(result.expressionTypes.isEmpty)
    }

  @Test def timedOutTypeCapabilityDoesNotDelayOrInventCheckVerdict(): Unit =
    fixture { (dir, bend) =>
      val slow =
        """if [ "${1:-}" = "--idea-structured-capabilities" ]; then
          |  sleep 5
          |fi""".stripMargin
      val _ = compiler.writeLauncher(bend, slow)
      val root = source(
        dir.resolve("valid.bend"),
        "import Base\ndef use() -> U32:\n  7\n"
      )
      val checked = check(dir, bend, root)
      assertEquals(BendCheckOutcome.Success, checked.outcome)
      assertEquals(BendCompleteness.Complete, checked.completeness)
      assertTrue(checked.expressionTypes.isEmpty)
    }

  @Test def pinnedHelperNormalizesOnlyClosedCheckedExpression(): Unit =
    fixture { (dir, bend) =>
      val _ = compiler.writeStructuredLauncher(bend)
      val text =
        "import Base\ndef id(A: Type, x: A) -> A:\n  x\ndef use() -> U32:\n  id(U32, 7)\n"
      val root = source(dir.resolve("normal.bend"), text)
      val call = text.lastIndexOf("id(U32, 7)")
      val closed = check(
        dir,
        bend,
        root,
        normalizationRequest = Some(
          BendNormalizationRequest(root.id, call + "id(U32, 7".length)
        )
      )
      assertEquals(BendCheckOutcome.Success, closed.outcome)
      assertEquals(
        Some(
          BendNormalization(
            root.id,
            BendTextRange(call, call + "id(U32, 7)".length),
            "7"
          )
        ),
        closed.normalization
      )
      val open = check(
        dir,
        bend,
        root,
        normalizationRequest = Some(
          BendNormalizationRequest(root.id, text.indexOf("  x\n") + 2)
        )
      )
      assertTrue(open.normalization.isEmpty)
    }

  @Test def unsafeDivergingNormalizationTimesOutWithoutChangingVerdictOrSource()
      : Unit = fixture { (dir, bend) =>
    val _ = compiler.writeStructuredLauncher(bend)
    val text =
      "import Base\n@unsafe def loop() -> Nat:\n  loop()\ndef use() -> Nat:\n  loop()\n"
    val root = source(dir.resolve("diverge.bend"), text)
    val before = snapshotCount(dir)
    val started = System.nanoTime()
    val checked = check(
      dir,
      bend,
      root,
      normalizationRequest = Some(
        BendNormalizationRequest(root.id, text.lastIndexOf("loop()") + 1)
      )
    )
    val elapsedSeconds = (System.nanoTime() - started) / 1_000_000_000L
    assertEquals(BendCheckOutcome.Success, checked.outcome)
    assertEquals(BendCompleteness.Complete, checked.completeness)
    assertEquals(BendReliance.UnsafeOrForeign, checked.reliance)
    assertTrue(checked.normalization.isEmpty)
    assertTrue(
      "bounded normalization exceeded its process limit",
      elapsedSeconds < 15
    )
    assertEquals(before, snapshotCount(dir))
  }

  @Test def earlierCompilerFailureAndTodoDoNotInventGoals(): Unit = fixture {
    (dir, bend) =>
      val _ = compiler.writeStructuredLauncher(bend)
      val earlier = source(
        dir.resolve("early.bend"),
        "import Base\ndef bad() -> U32:\n  missing_name\ndef later() -> U32:\n  ?need\n"
      )
      val failed = check(dir, bend, earlier, goalRequested = true)
      assertEquals(BendCheckOutcome.Failed, failed.outcome)
      assertTrue(failed.goal.isEmpty)
      val todo = source(
        dir.resolve("todo.bend"),
        "import Base\ndef later() -> U32:\n  ?TODO\n"
      )
      val incomplete = check(dir, bend, todo, goalRequested = true)
      assertEquals(BendCompleteness.Incomplete, incomplete.completeness)
      assertTrue(incomplete.goal.isEmpty)
  }

  @Test def incompatibleOrTimedOutHelperKeepsCliDiagnostic(): Unit = fixture {
    (dir, bend) =>
      val root = source(
        dir.resolve("main.bend"),
        "import Base\ndef main() -> U32:\n  unknown_value\n"
      )
      val incompatible =
        """if [ "${1:-}" = "--idea-structured-capabilities" ]; then
          |  printf '%s\n' '{"protocol":2,"compiler":"bend-2.0.35-pinned","operations":["diagnostic"]}'
          |  exit 0
          |fi""".stripMargin
      val _ = compiler.writeLauncher(bend, incompatible)
      val withoutHelper = check(dir, bend, root)
      assertEquals(BendCheckOutcome.Failed, withoutHelper.outcome)
      assertEquals(
        BendLocation.SourceLine(root.id, 2),
        withoutHelper.diagnostics.head.location
      )

      val slow =
        """if [ "${1:-}" = "--idea-structured-capabilities" ]; then
          |  sleep 6
          |fi""".stripMargin
      val _ = compiler.writeLauncher(bend, slow)
      val timedOut = check(dir, bend, root)
      assertEquals(BendCheckOutcome.Failed, timedOut.outcome)
      assertEquals(
        BendLocation.SourceLine(root.id, 2),
        timedOut.diagnostics.head.location
      )
  }

  @Test def helperWithoutSpanKeepsCliLine(): Unit = fixture { (dir, bend) =>
    val root = source(
      dir.resolve("main.bend"),
      "import Base\ndef main() -> U32:\n  unknown_value\n"
    )
    val baseline = check(dir, bend, root)
    assertEquals(BendCheckOutcome.Failed, baseline.outcome)
    val response = new JsonObject()
    response.addProperty("protocol", 1)
    response.addProperty("kind", "first-error")
    response.addProperty("message", baseline.details)
    response.add("span", JsonNull.INSTANCE)
    val output = dir.resolve("no-span.json")
    Files.writeString(output, response.toString + "\n")
    val helper = s"""if [ "$${1:-}" = "--idea-structured-capabilities" ]; then
                    |  printf '%s\n' '{"protocol":1,"compiler":"bend-2.0.35-pinned","operations":["diagnostic"]}'
                    |  exit 0
                    |fi
                    |if [ "$${1:-}" = "--idea-structured-diagnostic" ]; then
                    |  cat '${output.toString}'
                    |  exit 0
                    |fi""".stripMargin
    val _ = compiler.writeLauncher(bend, helper)
    val checked = check(dir, bend, root)
    assertEquals(baseline.outcome, checked.outcome)
    assertEquals(baseline.completeness, checked.completeness)
    assertEquals(baseline.reliance, checked.reliance)
    assertEquals(
      BendLocation.SourceLine(root.id, 2),
      checked.diagnostics.head.location
    )
  }

  @Test def unsavedDependencyErrorMapsToItsOriginalFileAndLeavesDiskUntouched()
      : Unit = fixture { (dir, bend) =>
    val dependency = dir.resolve("math.bend")
    val original = "import Base\ndef square() -> U32:\n  1\n"
    Files.writeString(dependency, original)
    val root = source(
      dir.resolve("main.bend"),
      "import ./math.bend as M\ndef main() -> U32:\n  M.square()\n"
    )
    val edited =
      source(dependency, "import Base\ndef square() -> U32:\n  unknown_value\n")
    val before = snapshotCount(dir)
    val result = check(dir, bend, root, Map(dependency.toString -> edited))
    assertEquals(BendCheckOutcome.Failed, result.outcome)
    assertEquals(BendCompleteness.Unknown, result.completeness)
    assertEquals(BendReliance.Unknown, result.reliance)
    assertEquals(
      BendLocation.SourceLine(edited.id, 2),
      result.diagnostics.head.location
    )
    assertEquals(original, Files.readString(dependency))
    assertEquals(before, snapshotCount(dir))
    assertTrue(result.mappings.exists(_.source == edited.id))
    assertEquals(
      edited.revision,
      result.sources.find(_.id == edited.id).get.revision
    )
    assertEquals(
      edited.revision,
      result.mappings.find(_.source == edited.id).get.revision
    )
    val rootMap = result.mappings.find(_.source == root.id).get
    assertEquals(1, rootMap.rewrites.size)
    val rewrite = rootMap.rewrites.head
    assertEquals(
      "./math.bend",
      rootMap.originalText.substring(rewrite.originalStart, rewrite.originalEnd)
    )
    assertTrue(
      rootMap.copiedText
        .substring(rewrite.copiedStart, rewrite.copiedEnd)
        .endsWith("math.bend")
    )
    assertTrue(
      rootMap.copiedText
        .substring(rewrite.copiedStart, rewrite.copiedEnd)
        .startsWith("./")
    )
    val afterImport = rootMap.copiedText.indexOf("def main")
    assertEquals(
      Some(rootMap.originalText.indexOf("def main")),
      rootMap.originalOffset(afterImport)
    )
    assertTrue(rootMap.compilerText.startsWith("\n"))
    assertEquals(None, rootMap.copiedOffset(0))
    assertEquals(
      Some(afterImport),
      rootMap.copiedOffset(rootMap.compilerText.indexOf("def main"))
    )
  }

  @Test def absoluteAndCachedImportsResolveAliasesOffline(): Unit = fixture {
    (dir, bend) =>
      val _ = compiler.writeStructuredLauncher(bend, structuredChecks = true)
      val absolute = dir.resolve("math.bend")
      val cached = dir.resolve("lib/0xabc/value.bend")
      Files.createDirectories(cached.getParent)
      Files.writeString(absolute, "import Base\ndef square() -> U32:\n  1\n")
      Files.writeString(cached, "import Base\ndef value() -> U32:\n  2\n")
      val rootText =
        s"# 😀 root\r\nimport ${absolute.toString} as M\r\n" +
          "import 0xabc/value.bend as C\r\n" +
          "def main() -> U32:\r\n  U32.add(M.square(), C.value())\r\n"
      val root = source(dir.resolve("main.bend"), rootText)
      val result = check(dir, bend, root)
      assertEquals(result.details, BendCheckOutcome.Success, result.outcome)
      assertEquals(BendCompleteness.Complete, result.completeness)
      val rootMap = result.mappings.find(_.source == root.id).get
      assertEquals(2, rootMap.rewrites.size)
      val mainStart = rootMap.compilerText.indexOf("def main")
      assertEquals(
        Some(rootText.indexOf("def main")),
        rootMap.compilerToOriginalOffset(mainStart)
      )
      assertEquals(
        Some(
          BendTextRange(
            rootText.indexOf("def main"),
            rootText.indexOf("def main") + "def main".length
          )
        ),
        rootMap.compilerToOriginalRange(
          mainStart,
          mainStart + "def main".length
        )
      )
      val baseId = new FileId(base.toRealPath().toString, true)
      assertTrue(
        "Matching Base remains a captured input",
        result.sources.exists(_.id == baseId)
      )
      assertFalse(
        "Base is read from the compiler installation, not a copied source",
        result.mappings.exists(_.source == baseId)
      )
  }

  @Test def graphCheckNeverRunsSideEffectingMain(): Unit = fixture {
    (dir, bend) =>
      val _ = compiler.writeStructuredLauncher(bend, structuredChecks = true)
      val dependency = dir.resolve("math.bend")
      val marker = dir.resolve("must-not-exist")
      Files.writeString(dependency, "import Base\ndef value() -> U32:\n  1\n")
      val root = source(
        dir.resolve("main.bend"),
        s"import Base\nimport ./math.bend as M\ndef main() -> IO(Unit):\n  do IO<Unit>:\n    file : File <- IO.try(File, File.open(\"$marker\", \"w\"))\n    File.close(file)\n"
      )
      val result = check(dir, bend, root)
      assertEquals(result.details, BendCheckOutcome.Success, result.outcome)
      assertFalse(Files.exists(marker))
  }

  @Test def missingHashImportDoesNotCreateCacheOrStartCompiler(): Unit =
    fixture { (dir, bend) =>
      val root =
        source(dir.resolve("main.bend"), "import 0xabc/missing.bend as M\n")
      val result = check(dir, bend, root)
      assertEquals(BendCheckOutcome.Failed, result.outcome)
      assertTrue(result.details.contains("Cannot read imported module"))
      assertEquals(
        BendLocation.SourceLine(root.id, 0),
        result.diagnostics.head.location
      )
      assertFalse(Files.exists(dir.resolve("lib")))
      assertEquals(0L, snapshotCount(dir))
    }

  @Test def proofGuardObservesSiblingLawsEvenWithoutImport(): Unit = fixture {
    (dir, bend) =>
      val _ = compiler.writeStructuredLauncher(bend, structuredChecks = true)
      val laws = dir.resolve("LAWS.bend")
      Files.writeString(laws, "law claim() -> Type\n")
      val root =
        source(dir.resolve("PROOF.bend"), "def main() -> Type:\n  Type\n")
      val result = check(
        dir,
        bend,
        root,
        laws = Some(source(laws, Files.readString(laws)))
      )
      assertEquals(BendCheckOutcome.Failed, result.outcome)
      assertTrue(result.details.contains("PROOF.bend must import ./LAWS.bend"))
  }

  @Test def proofGuardAcceptsImportedSibling(): Unit = fixture { (dir, bend) =>
    val _ = compiler.writeStructuredLauncher(bend, structuredChecks = true)
    val lawsPath = dir.resolve("LAWS.bend")
    Files.writeString(lawsPath, "# laws imported by this proof\n")
    val laws = source(lawsPath, Files.readString(lawsPath))
    val root = source(
      dir.resolve("PROOF.bend"),
      "import ./LAWS.bend as Laws\ndef main() -> Type:\n  Type\n"
    )
    val result = check(dir, bend, root, laws = Some(laws))
    assertEquals(result.details, BendCheckOutcome.Success, result.outcome)
  }

  @Test def repeatedSymlinkImportsAgreeWithPinnedCompilerAndUseUnsavedSource()
      : Unit =
    fixture { (dir, bend) =>
      val module = dir.resolve("math.bend")
      val alias = dir.resolve("alias.bend")
      Files.writeString(module, "def item() -> Type:\n  Type\n")
      Files.createSymbolicLink(alias, module.getFileName)
      val text =
        "import ./math.bend as M\nimport ./alias.bend as A\ndef main() -> Type:\n  A.item()\n"
      val rootPath = dir.resolve("main.bend")
      Files.writeString(rootPath, text)
      val direct = BendCliVerdictDecoder.check(
        BendBoundedProcess.run(
          List(bend.toString, rootPath.toString, "--check-only"),
          dir,
          Map(
            "BEND_LIB" -> dir.resolve("lib").toString,
            "BEND_HUB" -> "file:///__bend_editor_offline__"
          )
        )
      )
      assertEquals(direct.details, BendCheckOutcome.Success, direct.outcome)
      val root = source(rootPath, text)
      val result = check(dir, bend, root)
      assertEquals(result.details, direct.outcome, result.outcome)
      assertEquals(1, result.sources.count(_.id == source(module, "").id))
      assertEquals(
        "math",
        result.sources.find(_.id == source(module, "").id).get.namespace
      )
      val edited = source(module, "def item() -> Type:\n  ?need\n")
      val changed = check(
        dir,
        bend,
        root,
        Map(
          module.toString -> edited,
          alias.toString -> edited.copy(path = alias.toString)
        )
      )
      assertEquals(
        changed.details,
        BendCompleteness.Incomplete,
        changed.completeness
      )
      assertEquals("def item() -> Type:\n  Type\n", Files.readString(module))
      assertEquals(0L, snapshotCount(dir))
    }

  @Test def canonicalNamespacesFollowEachRootAndTheRealImportingDirectory()
      : Unit =
    fixture { (dir, bend) =>
      val library = dir.resolve("library")
      val aliases = dir.resolve("aliases")
      val nested = dir.resolve("app")
      List(library, aliases, nested).foreach(path => {
        val _ = Files.createDirectories(path)
      })
      Files.writeString(
        library.resolve("value.bend"),
        "def value() -> Type:\n  Type\n"
      )
      Files.writeString(
        library.resolve("math.bend"),
        "import ./value.bend as V\ndef item() -> Type:\n  V.value()\n"
      )
      Files.createSymbolicLink(
        aliases.resolve("math.bend"),
        library.resolve("math.bend")
      )
      Files.writeString(aliases.resolve("value.bend"), "invalid alias neighbor")
      for (path, spelling, namespace) <- List(
          (dir.resolve("main.bend"), "./aliases/math.bend", "library/math"),
          (
            nested.resolve("main.bend"),
            "../aliases/math.bend",
            "../library/math"
          )
        )
      do
        val root = source(
          path,
          s"import $spelling as M\ndef main() -> Type:\n  M.item()\n"
        )
        Files.writeString(path, root.text)
        val direct = BendCliVerdictDecoder.check(
          BendBoundedProcess.run(
            List(bend.toString, path.toString, "--check-only"),
            dir,
            Map(
              "BEND_LIB" -> dir.resolve("lib").toString,
              "BEND_HUB" -> "file:///__bend_editor_offline__"
            )
          )
        )
        assertEquals(direct.details, BendCheckOutcome.Success, direct.outcome)
        val result = check(dir, bend, root)
        assertEquals(result.details, direct.outcome, result.outcome)
        assertEquals(
          namespace,
          result.sources
            .find(
              _.id.value == library.resolve("math.bend").toRealPath().toString
            )
            .get
            .namespace
        )
        assertTrue(
          result.sources.exists(
            _.id.value == library.resolve("value.bend").toRealPath().toString
          )
        )
    }

  @Test def cachedNamedPackageAndHashAliasCheckTheSameOfflineUnsavedSource()
      : Unit =
    fixture { (dir, bend) =>
      val hash = "0x" + "a" * 32
      val cache = dir.resolve("lib")
      val module = cache.resolve(hash).resolve("item.bend")
      Files.createDirectories(module.getParent)
      Files.createDirectories(cache.resolve("names"))
      Files.writeString(cache.resolve("names/bend-sample@1.0.0.0"), hash + "\n")
      Files.writeString(module, "def item() -> Type:\n  Type\n")
      val root = source(
        dir.resolve("main.bend"),
        s"import bend-sample@1.0.0.0/item.bend as Named\nimport $hash/item.bend as Hashed\ndef main() -> Type:\n  Named.item()\n"
      )
      Files.writeString(Path.of(root.path), root.text)
      val direct = BendCliVerdictDecoder.check(
        BendBoundedProcess.run(
          List(bend.toString, root.path, "--check-only"),
          dir,
          Map(
            "BEND_LIB" -> cache.toString,
            "BEND_HUB" -> "file:///__bend_editor_offline__"
          )
        )
      )
      assertEquals(direct.details, BendCheckOutcome.Success, direct.outcome)
      val result = check(dir, bend, root)
      assertEquals(result.details, direct.outcome, result.outcome)
      assertEquals(
        List(hash + "/item"),
        result.sources.filter(_.id == source(module, "").id).map(_.namespace)
      )
      val edited = source(module, "def item() -> Type:\n  ?need\n")
      val changed = check(dir, bend, root, Map(module.toString -> edited))
      assertEquals(
        changed.details,
        BendCompleteness.Incomplete,
        changed.completeness
      )
      assertEquals("def item() -> Type:\n  Type\n", Files.readString(module))
      Files.delete(cache.resolve("names/bend-sample@1.0.0.0"))
      val missing = check(dir, bend, root)
      assertEquals(BendCheckOutcome.Failed, missing.outcome)
      assertFalse(Files.exists(cache.resolve("names/bend-sample@1.0.0.0")))
    }

  @Test def nestedDiamondKeepsOneCompilerNamespaceForSharedModule(): Unit =
    fixture { (dir, bend) =>
      val nested = dir.resolve("nested")
      Files.createDirectory(nested)
      Files.writeString(
        dir.resolve("shared.bend"),
        "def value() -> Type:\n  Type\n"
      )
      Files.writeString(
        nested.resolve("a.bend"),
        "import ../shared.bend as Shared\ndef item() -> Type:\n  Shared.value()\n"
      )
      Files.writeString(
        dir.resolve("b.bend"),
        "import ./shared.bend as Shared\ndef item() -> Type:\n  Shared.value()\n"
      )
      val root = source(
        dir.resolve("main.bend"),
        "import ./nested/a.bend as A\nimport ./b.bend as B\ndef main() -> Type:\n  A.item()\n"
      )
      val result = check(dir, bend, root)
      assertEquals(result.details, BendCheckOutcome.Success, result.outcome)
      val copiedParents = result.mappings
        .map(mapping => Path.of(mapping.copiedPath).getParent)
        .distinct
      assertEquals(1, copiedParents.size)
      result.mappings.foreach { mapping =>
        mapping.rewrites.foreach { rewrite =>
          assertTrue(
            mapping.copiedText
              .substring(rewrite.copiedStart, rewrite.copiedEnd)
              .startsWith("./")
          )
        }
      }
    }

  @Test def identicalExcerptsStayOnRoot(): Unit = fixture { (dir, bend) =>
    val a = dir.resolve("a.bend")
    val b = dir.resolve("b.bend")
    val text = "def broken() -> Type:\n  unknown_name\n"
    Files.writeString(a, text)
    Files.writeString(b, text)
    val root = source(
      dir.resolve("main.bend"),
      "import ./a.bend as A\nimport ./b.bend as B\n"
    )
    val result = check(dir, bend, root)
    assertEquals(BendCheckOutcome.Failed, result.outcome)
    assertEquals(BendLocation.RootOnly, result.diagnostics.head.location)
  }

  /** RF8 preventive: even a real structured span cannot disambiguate identical
    * captured dependency text. CRLF, astral text and unsaved revisions must map
    * exactly once the duplicate is distinguishable. Original fixture.
    */
  @Test def structuredDuplicateUnsavedCrLfSourcesStayHonest(): Unit = fixture {
    (dir, bend) =>
      val _ = compiler.writeStructuredLauncher(bend, structuredChecks = true)
      val first = dir.resolve("violet.bend")
      val second = dir.resolve("amber.bend")
      val persisted = "import Base\ndef shade() -> U32:\n  7\n"
      Files.writeString(first, persisted)
      Files.writeString(second, persisted)
      val missing = "unknown_shadow_leaf"
      val edited =
        s"import Base\r\n# 🍁 current buffer\r\ndef shade() -> U32:\r\n  $missing\r\n"
      val firstRecord = source(first, edited).copy(revision = 19L)
      val secondRecord = source(second, edited).copy(revision = 31L)
      val root = source(
        dir.resolve("root.bend"),
        "import ./violet.bend as Violet\r\nimport ./amber.bend as Amber\r\n"
      )
      val duplicate = check(
        dir,
        bend,
        root,
        Map(first.toString -> firstRecord, second.toString -> secondRecord)
      )
      assertEquals(
        duplicate.details,
        BendCheckOutcome.Failed,
        duplicate.outcome
      )
      assertTrue(duplicate.diagnostics.head.message.contains(missing))
      assertEquals(BendLocation.RootOnly, duplicate.diagnostics.head.location)
      assertTrue(
        duplicate.sources.exists(s =>
          s.id == firstRecord.id && s.revision == 19L && s.text == edited
        )
      )
      assertTrue(
        duplicate.sources.exists(s =>
          s.id == secondRecord.id && s.revision == 31L && s.text == edited
        )
      )
      val distinct = secondRecord.copy(
        text = edited.replace("current buffer", "other buffer"),
        revision = 32L
      )
      val unique = check(
        dir,
        bend,
        root,
        Map(first.toString -> firstRecord, second.toString -> distinct)
      )
      assertEquals(unique.details, BendCheckOutcome.Failed, unique.outcome)
      val start = edited.indexOf(missing)
      assertEquals(
        BendLocation.SourceRange(
          firstRecord.id,
          BendTextRange(start, start + missing.length)
        ),
        unique.diagnostics.head.location
      )
      assertTrue(start > edited.codePointCount(0, start))
      assertEquals(persisted, Files.readString(first))
      assertEquals(persisted, Files.readString(second))
  }

  @Test def baseWithImportsIsRejectedBeforeAnySourceCommand(): Unit = fixture {
    (dir, bend) =>
      val baseWithImport = dir.resolve("base-with-import.bend")
      val text = "import 0xabc/dependency.bend as Dep\n"
      Files.writeString(baseWithImport, text)
      val marker = dir.resolve("source-invoked")
      Files.writeString(
        bend,
        s"#!/bin/sh\nif [ \"$$1\" = \"--help\" ]; then echo '  bend <file.bend> --check-only check the file and its imports; run nothing'; exit 0; fi\nif [ \"$$1\" = \"--idea-check-capabilities\" ]; then exit 1; fi\nif [ \"$$1\" = \"base\" ]; then printf 'import 0xabc/dependency.bend as Dep\\n'; exit 0; fi\ntouch '$marker'\n"
      )
      val root = source(dir.resolve("main.bend"), "import Base\n")
      val selection = BendToolchainSelection(
        bend.toString,
        baseWithImport.toString,
        dir.resolve("lib").toString,
        true,
        1L
      )
      val snapshot = BendCheckSnapshot(
        root.id,
        root.path,
        root.text,
        root.revision,
        selection
      )
      val result = new BendCliCheckBackend(dir).check(snapshot)
      assertEquals(BendCheckOutcome.Unavailable, result.outcome)
      assertTrue(result.details.contains("closed offline snapshot"))
      assertFalse(Files.exists(marker))
  }

  @Test def byteIdenticalConfiguredBaseAlsoImportedByPathIsUnavailable(): Unit =
    fixture { (dir, bend) =>
      val copiedBase = dir.resolve("BaseCopy.bend")
      Files.copy(base, copiedBase)
      val root = source(
        dir.resolve("main.bend"),
        s"import Base\nimport ${copiedBase.toString} as Copy\ndef main() -> Type:\n  Type\n"
      )
      val result = check(dir, bend, root, configuredBase = copiedBase)
      assertEquals(BendCheckOutcome.Unavailable, result.outcome)
      assertTrue(
        result.details.contains(
          "identity relative to the compiler's built-in Base"
        )
      )
      assertFalse(result.details.contains("One namespace per file"))
      assertEquals(0L, snapshotCount(dir))
    }

  @Test def realBendLintMapsUnsavedRootAndImportedCompilerFacts(): Unit =
    val checkout = Option(System.getenv("BEND_TEST_LINT_DIR"))
    org.junit.Assume.assumeTrue(
      "Set BEND_TEST_LINT_DIR for the optional external prototype",
      checkout.nonEmpty
    )
    fixture { (dir, bend) =>
      val dependency = dir.resolve("identity.bend")
      val disk = "this is not the edited module\n"
      Files.writeString(dependency, disk)
      val edited = source(
        dependency,
        "import Base\r\n# 😀\r\ndef identity(x: U32) -> U32:\r\n  x\r\n"
      )
      val text =
        "import Base\r\nimport ./identity.bend as I\r\n# 😀  \r\ndef main() -> U32:\r\n  I.identity(1)\r\n"
      val root = source(dir.resolve("lint.bend"), text)
      val result = check(
        dir,
        bend,
        root,
        Map(dependency.toString -> edited),
        lint = com.dearlordylord.bend.idea.toolchain.api.BendLintOptions(
          true,
          compiler.bunExecutable.toString,
          checkout.get,
          true
        ),
        lintRequested = true
      )
      assertEquals(result.details, BendCheckOutcome.Success, result.outcome)
      val lint =
        result.lint.getOrElse(throw new AssertionError("Missing lint reply"))
      assertEquals(lint.details, BendLintOutcome.Completed, lint.outcome)
      val whitespace =
        lint.findings.find(_.code == "style/trailing-whitespace").get
      assertEquals(root.id, whitespace.source)
      assertEquals(
        "  ",
        text.substring(whitespace.range.start, whitespace.range.end)
      )
      val fact = lint.findings
        .find(f =>
          f.code == "idea/compiler-observation" && f.source == edited.id
        )
        .get
      assertEquals("x", edited.text.substring(fact.range.start, fact.range.end))
      assertTrue(fact.message, fact.message.contains("checked demand: live"))
      assertTrue(fact.message, fact.message.contains("U32"))
      assertEquals(0, lint.unmapped)
      assertEquals(disk, Files.readString(dependency))
      assertFalse(Files.exists(Path.of(root.path)))
      assertEquals(0L, snapshotCount(dir))
    }

  @Test def incompatibleLinterDoesNotChangeCompilerVerdict(): Unit =
    fixture { (dir, bend) =>
      val root =
        source(dir.resolve("lint.bend"), "def main() -> Type:\n  Type\n")
      val result = check(
        dir,
        bend,
        root,
        lint = com.dearlordylord.bend.idea.toolchain.api
          .BendLintOptions(true, compiler.bunExecutable.toString, dir.toString),
        lintRequested = true
      )
      assertEquals(BendCheckOutcome.Success, result.outcome)
      assertEquals(BendLintOutcome.Unavailable, result.lint.get.outcome)
      assertTrue(result.lint.get.findings.isEmpty)
      assertEquals(0L, snapshotCount(dir))
    }

  @Test def lintDoesNotExecuteMainOrRunOnIncompleteSource(): Unit =
    val checkout = Option(System.getenv("BEND_TEST_LINT_DIR"))
    org.junit.Assume.assumeTrue(
      "Set BEND_TEST_LINT_DIR for the optional external prototype",
      checkout.nonEmpty
    )
    fixture { (dir, bend) =>
      val options = com.dearlordylord.bend.idea.toolchain.api
        .BendLintOptions(true, compiler.bunExecutable.toString, checkout.get)
      val root = source(
        dir.resolve("lint.bend"),
        "import Base\ndef main() -> IO(Unit):\n  do IO<Unit>:\n    IO.print(\"LINT_MAIN_MUST_NOT_EXECUTE\")\n"
      )
      val complete =
        check(dir, bend, root, lint = options, lintRequested = true)
      assertEquals(complete.details, BendCheckOutcome.Success, complete.outcome)
      assertEquals(
        complete.lint.get.details,
        BendLintOutcome.Completed,
        complete.lint.get.outcome
      )
      val incomplete = source(
        dir.resolve("todo.bend"),
        "import Base\ndef main() -> U32:\n  ?unfinished\n"
      )
      val pending =
        check(dir, bend, incomplete, lint = options, lintRequested = true)
      assertEquals(BendCompleteness.Incomplete, pending.completeness)
      assertTrue(pending.lint.isEmpty)
      val ordinary = check(dir, bend, root, lint = options)
      assertEquals(BendCheckOutcome.Success, ordinary.outcome)
      assertTrue("Ordinary checks must not start lint", ordinary.lint.isEmpty)
      assertEquals(0L, snapshotCount(dir))
    }
