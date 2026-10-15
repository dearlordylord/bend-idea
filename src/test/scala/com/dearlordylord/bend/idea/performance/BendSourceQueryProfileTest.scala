package com.dearlordylord.bend.idea.performance

import com.dearlordylord.bend.idea.features.navigation.{
  BendStaticDependencies,
  BendDependencyKind
}
import com.dearlordylord.bend.idea.symbols.api.BendImportedSymbolCatalog
import com.dearlordylord.bend.idea.syntax.psi.BendDefinition
import com.dearlordylord.bend.idea.toolchain.api.{
  BendToolchainChoices,
  BendToolchainSettings
}
import com.intellij.openapi.application.{ApplicationManager, ReadAction}
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.progress.{ProgressManager, ProcessCanceledException}
import com.intellij.openapi.progress.util.ProgressIndicatorBase
import com.intellij.psi.{PsiFile, PsiDocumentManager}
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.ServiceContainerUtil
import com.dearlordylord.bend.idea.workspace.api.BendWorkspaceGraph
import com.dearlordylord.bend.idea.workspace.model.BendSourceRecord
import java.lang.management.ManagementFactory
import java.nio.file.{Files, Path, StandardOpenOption}
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.*

/** Opt-in native API measurements. No unstable timing threshold in CI. */
final class BendSourceQueryProfileTest extends BasePlatformTestCase:
  private var original: BendToolchainChoices = null
  private var dependency: PsiFile = null
  private var importer: PsiFile = null
  private var unrelated: PsiFile = null
  private val importers = parameter("importers", 24, 1, 256)
  private val unrelatedCount = parameter("unrelated", 24, 1, 2048)
  private val callsPerImporter = parameter("calls", 8, 1, 32)
  private val samples = parameter("samples", 5, 1, 50)
  private val shadowed = System
    .getProperty("bend.source.profile.shadowed", "false")
    .toBoolean
  private val run = parameter("run", 1, 1, 100)
  private val graphLoads = new AtomicLong
  @volatile private var measuredThread: Thread = null
  private val rows = scala.collection.mutable.ListBuffer.empty[String]

  private def parameter(name: String, default: Int, min: Int, max: Int): Int =
    val value =
      System.getProperty(s"bend.source.profile.$name", default.toString).toInt
    require(
      value >= min && value <= max,
      s"$name must be between $min and $max"
    )
    value

  private def row(
      phase: String,
      sample: Int,
      duration: Long,
      bytes: Long,
      count: Int,
      loads: Long
  ): String =
    s"${getName},$phase,$sample,$duration,$bytes,$count,$loads,$importers,$unrelatedCount,$callsPerImporter,$shadowed,$run"

  override def setUp(): Unit =
    super.setUp()
    val settings = ApplicationManager.getApplication.getService(
      classOf[BendToolchainSettings]
    )
    original = settings.choices
    settings.update(BendToolchainChoices())
    val start = System.nanoTime()
    dependency =
      myFixture.addFileToProject("lib.bend", "def answer() -> Type:\n  Type\n")
    val callers = (0 until importers).map { file =>
      val definitions = (0 until callsPerImporter)
        .map(call => s"def caller${file}_$call():\n  Shared.answer()\n")
        .mkString
      myFixture.addFileToProject(
        s"caller$file.bend",
        "import ./lib.bend as Shared\n" + definitions + "def shadow(answer: Type):\n  answer\n"
      )
    }
    importer = callers.head
    unrelated = (0 until unrelatedCount).map { file =>
      val text = if shadowed then
        s"def unrelated$file(answer: Type):\n  answer\n"
      else s"def unrelated$file():\n  Type\n"
      myFixture.addFileToProject(
        s"unrelated$file.bend",
        text
      )
    }.head
    PsiDocumentManager.getInstance(getProject).commitAllDocuments()
    rows += row(
      "fixture-build",
      0,
      System.nanoTime() - start,
      -1,
      1 + importers + unrelatedCount,
      0
    )
    val delegate = getProject.getService(classOf[BendWorkspaceGraph])
    val counted = new BendWorkspaceGraph:
      override def load(
          root: BendSourceRecord,
          base: String,
          cache: String,
          canceled: () => Boolean
      ) =
        if Thread.currentThread() == measuredThread then
          val _ = graphLoads.incrementAndGet()
        delegate.load(root, base, cache, canceled)
      override def siblingLaws(path: String) = delegate.siblingLaws(path)
    ServiceContainerUtil.replaceService(
      getProject,
      classOf[BendWorkspaceGraph],
      counted,
      getTestRootDisposable
    )

  override def tearDown(): Unit =
    try
      ApplicationManager.getApplication
        .getService(classOf[BendToolchainSettings])
        .update(original)
      val output = Path.of(System.getProperty("bend.source.profile.output"))
      Files.createDirectories(output.getParent)
      val metadata = new java.util.Properties
      List(
        "java.runtime.version",
        "java.vm.name",
        "os.name",
        "os.version",
        "os.arch"
      )
        .foreach(name => {
          val _ = metadata.setProperty(name, System.getProperty(name))
        })
      val metadataOutput = Files.newOutputStream(
        output.resolveSibling("source-query-profile.properties")
      )
      try
        metadata.store(
          metadataOutput,
          "Fixture JVM; timings exclude fixture construction"
        )
      finally metadataOutput.close()
      val _ = Files.writeString(
        output,
        rows.mkString("", "\n", "\n"),
        StandardOpenOption.CREATE,
        StandardOpenOption.APPEND
      )
    finally super.tearDown()

  def testNavigationSnapshotMeasurements(): Unit =
    profile(
      () => {
        val snapshot = getProject
          .getService(classOf[BendImportedSymbolCatalog])
          .navigationSnapshot(importer, "", "")
        assertEquals(2, snapshot.graph.files.size)
        snapshot.direct.size
      },
      1
    )

  def testFindUsagesMeasurements(): Unit =
    profile(
      () => {
        val declaration = ReadAction.compute(() =>
          PsiTreeUtil.findChildOfType(dependency, classOf[BendDefinition])
        )
        ReferencesSearch
          .search(declaration, GlobalSearchScope.projectScope(getProject))
          .findAll()
          .size()
      },
      importers * callsPerImporter
    )

  def testStaticDependencyMeasurements(): Unit =
    profile(
      () => {
        val declaration = ReadAction.compute(() =>
          PsiTreeUtil.findChildOfType(dependency, classOf[BendDefinition])
        )
        val report = BendStaticDependencies
          .inspect(declaration)
          .fold(message => throw new AssertionError(message), identity)
        report.dependencies.count(_.kind == BendDependencyKind.CalledBy)
      },
      importers * callsPerImporter
    )

  private def profile(query: () => Int, expected: Int): Unit =
    measure("first-query", 0, query, expected)
    (1 to samples).foreach(sample =>
      measure("warm-query", sample, query, expected)
    )
    edit(dependency)
    measure("dependency-edit-first", 0, query, expected)
    (1 to samples).foreach(sample =>
      measure("dependency-edit-warm", sample, query, expected)
    )
    edit(unrelated)
    measure("unrelated-edit-first", 0, query, expected)
    (1 to samples).foreach(sample =>
      measure("unrelated-edit-warm", sample, query, expected)
    )
    val indicator = new ProgressIndicatorBase
    indicator.cancel()
    val start = System.nanoTime()
    val canceled = assertThrows(
      classOf[ProcessCanceledException],
      () =>
        ProgressManager
          .getInstance()
          .runProcess(
            new Runnable:
              override def run(): Unit =
                ProgressManager.checkCanceled()
                val _ = query()
            ,
            indicator
          )
    )
    assertNotNull(canceled)
    rows += row("pre-canceled-request", 0, System.nanoTime() - start, -1, 0, 0)
    if getName != "testNavigationSnapshotMeasurements" then
      var canceledAt = 0L
      val duringScan = new ProgressIndicatorBase
      val delegate = getProject.getService(classOf[BendWorkspaceGraph])
      val cancelAfterCapture = new BendWorkspaceGraph:
        override def load(
            root: BendSourceRecord,
            base: String,
            cache: String,
            canceled: () => Boolean
        ) =
          val graph = delegate.load(root, base, cache, canceled)
          if canceledAt == 0L then
            canceledAt = System.nanoTime()
            duringScan.cancel()
          graph
        override def siblingLaws(path: String) = delegate.siblingLaws(path)
      ServiceContainerUtil.replaceService(
        getProject,
        classOf[BendWorkspaceGraph],
        cancelAfterCapture,
        getTestRootDisposable
      )
      val _ = assertThrows(
        classOf[ProcessCanceledException],
        () =>
          ProgressManager
            .getInstance()
            .runProcess(
              new Runnable:
                override def run(): Unit =
                  val _ = query()
              ,
              duringScan
            )
      )
      assertTrue(
        "Query canceled after its first actual graph capture",
        canceledAt > 0L
      )
      rows += row(
        "scan-cancel-after-first-capture",
        0,
        System.nanoTime() - canceledAt,
        -1,
        0,
        1
      )

  private def edit(file: PsiFile): Unit =
    val manager = PsiDocumentManager.getInstance(getProject)
    val document = manager.getDocument(file)
    WriteCommandAction.runWriteCommandAction(
      getProject,
      new Runnable:
        override def run(): Unit = document.insertString(
          document.getTextLength,
          "# edited without saving\n"
        )
    )
    manager.commitDocument(document)

  private def measure(
      phase: String,
      sample: Int,
      query: () => Int,
      expected: Int
  ): Unit =
    val bean = ManagementFactory.getThreadMXBean match
      case value: com.sun.management.ThreadMXBean
          if value.isThreadAllocatedMemorySupported && value.isThreadAllocatedMemoryEnabled =>
        Some(value)
      case _ => None
    val thread = Thread.currentThread().threadId()
    val allocated = bean.map(_.getThreadAllocatedBytes(thread))
    measuredThread = Thread.currentThread()
    val loadsBefore = graphLoads.get()
    val start = System.nanoTime()
    val count = query()
    val duration = System.nanoTime() - start
    val bytes = allocated
      .flatMap(before => bean.map(_.getThreadAllocatedBytes(thread) - before))
      .getOrElse(-1L)
    assertEquals(expected, count)
    rows += row(
      phase,
      sample,
      duration,
      bytes,
      count,
      graphLoads.get() - loadsBefore
    )
