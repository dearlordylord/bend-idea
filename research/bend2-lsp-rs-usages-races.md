# Find Usages profiling and native revision races

2026-10-07. Worktree branch: `research/usages-revision-races`, based on `master`
at `499fecb`. Scope: the user's requested follow-up to profile Find Usages before
adding indexes and identify missing native lifecycle fixtures.

Research source: [bend2-lsp-rs at 00dcfb63bc7ec7911de81d9c1e77c5ac87041d2d](https://github.com/IlyaGulya/bend2-lsp-rs/tree/00dcfb63bc7ec7911de81d9c1e77c5ac87041d2d),
particularly its [indexing decision](https://github.com/IlyaGulya/bend2-lsp-rs/blob/00dcfb63bc7ec7911de81d9c1e77c5ac87041d2d/docs/adr-global-indexing.md),
[measurement scope](https://github.com/IlyaGulya/bend2-lsp-rs/blob/00dcfb63bc7ec7911de81d9c1e77c5ac87041d2d/docs/performance-policy.md)
and [revision scenarios](https://github.com/IlyaGulya/bend2-lsp-rs/blob/00dcfb63bc7ec7911de81d9c1e77c5ac87041d2d/src/server/revision_tests.rs).
These identify risks and measurement distinctions; they do not establish native
IntelliJ behavior or test results.

All changes here are original Scala, test and measurement work. No Rust implementation,
fixture body, benchmark input or documentation text is copied or adapted. The
Scala fixtures derive expectations from shared native resolution, current-buffer
and publication contracts (A3/A5/A6/A8), issues #13/#14/#17/#19 and the real pinned
Bend compiler. Existing upstream reference checkouts remain untouched. No new
upstream payload, runtime dependency or notice obligation is introduced.

Original production destinations are
`adapters/intellij/BendLibrarySourceService.scala`,
`workspace/api/BendSourceCatalog.scala` and
`symbols/api/BendPhysicalTargets.scala`, under
`src/main/scala/com/dearlordylord/bend/idea`. Original fixtures are the named
adapter/checking suites below and `performance/BendSourceQueryProfileTest.scala`
under the matching test package. The runner and opt-in Gradle controls are test
infrastructure. Production classes enter the plugin; tests, the runner and these
research records do not. This original work adds no upstream notice payload to
either the plugin or the formatter distribution.

## Measurement procedure

The existing opt-in `BendSourceQueryProfileTest` now accepts bounded workload
controls. `scripts/profile-bend-usages.py` runs only its Find Usages method, with
three independent fixture JVMs per workload and ten warm repetitions per phase.
Workload order rotates between runs. Each query uses native `ReferencesSearch`
and checks the exact result count. Importers include a same-spelling local
binder, which must not count as a reference to the imported declaration.

| Workload | Files | Importers / calls each | Other files | Expected usages |
|---|---:|---|---|---:|
| dense49 | 49 | 24 / 8 | 24 distinct-name files | 192 |
| sparse273 | 273 | 16 / 4 | 256 distinct-name files | 64 |
| sparse1041 | 1,041 | 16 / 4 | 1,024 distinct-name files | 64 |
| shadowed1041 | 1,041 | 16 / 4 | 1,024 files containing a local `answer` binder and use | 64 |

Fixture setup is recorded separately. Each run records the first query, warm
queries, the first/warm queries after a committed unsaved dependency edit, then
the first/warm queries after a committed unsaved unrelated edit. Both edits add
comments and preserve the binding/use set. Allocation counts cover only the
executing thread, not retained heap or other IntelliJ workers. A test-only
workspace-service decorator counts actual graph loads on the query's executing
thread; it adds one atomic increment per counted load and does not cache or
alter resolution. Incidental background queries are excluded. The count is a
mechanism witness, not a substitute for latency measurement.
It also excludes other worker threads if IntelliJ delegates search work to them;
it is not a whole-process graph-load total.

Pre-canceled requests and cancellation after the first real graph capture are
recorded separately; neither contributes a successful-query timing. These are
API/fixture measurements, not editor rendering latency or a cold JVM before
IntelliJ initialization. There is no timing threshold in CI. Every profile
invocation starts a new fixture JVM; Gradle daemon reuse is outside measured
queries. Multiple JVMs reduce dependence on one JIT history but do not establish
hardware-independent performance or isolate every GC/host effect.

Reproduce with a full JDK 21 and the exact inputs from
`ci/bend-test-toolchain.properties`:

```sh
JAVA_HOME=/absolute/path/to/jdk21 \
BEND_TEST_COMPILER_DIR=/absolute/path/to/pinned-clean-bend \
BEND_TEST_BUN=/absolute/path/to/pinned-bun \
python3 scripts/profile-bend-usages.py
```

The runner creates a fresh output directory and refuses to mix prior results.
It retains commands, Gradle logs, per-run CSVs, fixture JVM/OS properties, source
commit/diff identity and a combined CSV under `build/reports/usages-profile`.

## Lifecycle coverage audit

| Scenario | Native evidence | Scope |
|---|---|---|
| Pending dependency loading while unsaved/imported and unrelated navigation execute | Existing `BendNavigationFreshnessTest.testPendingDependencyCaptureDoesNotHideUnsavedEditOrBlockUnrelatedNavigation` | No duplicate fixture; native queries proceed before the gated worker is released. |
| Dependency edit while building a graph | Existing shared-capture retry and background first-check replacement fixtures | Captures must retry or reject mixed revisions. |
| Closed intermediate and modified leaf dependencies across two roots | Existing `BendBackgroundCheckingTest.testClosedIntermediateTracksUnsavedLeafAndClearsBothRoots` | Closing a native editor preserves a modified document; it does not implement LSP's close-to-disk rule. |
| Close/reopen during an active compiler check | New `testCloseAndReopenDuringCompilerCheckPublishesOnlyReopenedRevision` | Real delayed Bend subprocess; the reopened edit owns the published source revision and old error text is absent. Native document stamps do not restart like LSP version counters. |
| Watch notification following an external disk write conflicts with unsaved import edges | New `BendExplicitCheckRunnerServiceTest.testDiskWatchNotificationPreservesUnsavedImportEdgesAfterCloseAndReopen` | Actual disk write plus an explicitly delivered VFS notification; capture, native navigation and Find Usages retain the modified document and its selected dependency. IntelliJ conflict/reload UI is outside this fixture. |
| Closed modified symlink alias read through its canonical target | New `BendLibrarySourceServiceTest.testClosedModifiedSymlinkAliasRemainsCurrentForCanonicalSourceReads` | Native close followed by direct capture of text/revision and Go to Declaration of an unsaved renamed declaration. Both capture and PSI reacquisition failed before their respective corrections. |
| Compiler checking and repair through a closed modified alias | New `BendCheckCurrentFileTest.testCheckUsesClosedModifiedAliasOfCanonicalDependency` | Native Check Current File invokes real pinned Bend; the unsaved imported error fails, a closed-document repair invalidates that result and the replacement succeeds without saving the original. |
| Disposal with active compiler subprocess | Existing `testDisposingActiveSessionTerminatesProcessAndDropsResult` and bounded-process tests | Retain existing coverage; do not replace real process lifecycle assertions with mocks. |
| Disposal during graph capture before compiler dispatch | New `testDisposalDuringGraphCaptureInterruptsWorkerWithoutStartingCompiler` | Gate follows real graph loading. Disposal must interrupt/terminate the scheduler thread, release its reservation, publish nothing and never dispatch the compiler. |

IntelliJ owns document/editor lifecycle; no LSP epochs, close queue or new
semantic representation are introduced. Closing a modified editor must keep its
document authoritative. An explicit discard/reload is a different platform
operation and is not claimed by the close/reopen fixture.

## Reproduced alias defect and correction

The watch scenario initially found that, after close, the graph used the
disk-selected import while the modified alias document still contained its own
import. A minimized explicit symlink fixture reproduced the same error without
a watch event or any graph cache. The source catalog chose aliases from open
editors only; a closed modified document could use a different VFS handle from
the canonical request. Its saved canonical document then displaced the edit.

With the test inputs above, the narrow feedback command was:

```sh
./gradlew test --tests '*testClosedModifiedSymlinkAlias*'
```

Before correction it failed with `Closed modified aliases still own current
source`, expecting `def edited()` and receiving `def saved()`. Extending the
same fixture to native Go to Declaration then failed separately with `Native
navigation reacquires the closed modified alias`: source capture was correct,
but the shared physical-target owner still reacquired the saved canonical PSI.

`BendLibrarySourceService` now chooses modified documents by canonical identity
before unmodified open documents or persisted source. The same private selection
serves source capture and the read-only `BendSourceCatalog.currentSourcePath`
contract. `BendPhysicalTargets` uses that path for native PSI reacquisition and
retains its existing canonical identity/category/name/offset validation.
References, documentation, module/type navigation and proof links consume this
shared owner; no feature duplicates alias policy or resolver behavior. The
IntelliJ adapter can answer the path without another source-text read. It does
not install a new cache, index, document lifetime or platform handle in policy.

The source/catalog and physical-target classes already participate in compiled
architecture rules; no enforcement suppression or dependency relaxation was
needed. The architecture's source-precedence contract and Unreleased changelog
now describe the correction.

Fixture construction corrections are not product defects: VFS notifications
must publish through the application bus; a light fixture's shared project
scheduler must not be permanently disposed by one method; and
`configureFromExistingVirtualFile` rewrites VFS bytes, so reopening a modified
document uses `openFileInEditor`. The final watch fixture does not automate
IntelliJ's conflict-resolution UI or refresh overwritten editor bytes.

## Results and review

The twelve independent profile invocations passed. There are **396 successful
query measurements**, each asserting 192 or 64 actual usages as specified, plus
12 fixture-build records and 24 cancellation records. The abandoned preliminary
project-wide-counter probe is not included in these results. The retained
[raw CSV](bend2-lsp-rs-usages-profile.csv) has 432 records;
[metadata and source hashes](bend2-lsp-rs-usages-profile-metadata.json) identify
all measured production/test inputs and the runner. Those file hashes were
checked against the final measured source tree before archiving. Detailed
per-invocation Gradle logs and commands remain in the local build report directory.

Host: macOS 27.0 ARM64. Gradle/compilation used Homebrew JDK 21.0.10; each fixture
JVM used the pinned IDEA Community 2025.1 JBR 21.0.6+9-b895.109. The supplied clean
compiler checkout was Bend 2.0.35 at
`79df8d9c40722ee9507a1e253f283b51025f9d6c`, with Bun 1.4.2
(`1.4.2+744846f84`). Source-only queries do not invoke Bend, but each invocation
still passed the repository's input verification gate. The host was shared with
other development work; these timings are a local baseline, not exclusive-host
microbenchmark or editor-latency guarantees.

| Workload | First query range, ms (3 JVMs) | Warm median per JVM, ms | Warm caller-thread graph-load range | Warm median allocation, MiB |
|---|---:|---|---:|---:|
| dense49 | 1,095–1,269 | 145.0 / 182.5 / 169.5 | 442–502 | 26.0 |
| sparse273 | 617–980 | 55.2 / 78.2 / 99.0 | 166–190 | 10.0 |
| sparse1041 | 642–1,519 | 134.0 / 53.2 / 86.6 | 166–196 | 10.4 |
| shadowed1041 | 2,698–4,169 | 1,072.6 / 871.3 / 908.7 | 2,286–2,466 | 249.3 |

Each per-JVM warm median uses ten repetitions. Allocation medians pool the 30
warm repetitions and cover only the caller thread. Graph-load ranges cover
those same repetitions and do not count other workers. Timings include native
search/result materialization and target reacquisition, excluding fixture build.

The following medians pool the 30 warm samples per phase. They should not be
read as a causal speedup from editing: phases execute in order, so later samples
benefit from more JIT/platform warmup and can have different GC/host effects.

| Workload | Initial warm, ms | Warm after dependency edit, ms | Warm after unrelated edit, ms |
|---|---:|---:|---:|
| dense49 | 171.0 | 115.3 | 103.7 |
| sparse273 | 70.7 | 52.0 | 49.5 |
| sparse1041 | 80.1 | 66.4 | 64.1 |
| shadowed1041 | 1,002.6 | 754.0 | 694.1 |

Cancellation after the first actual graph capture completed in **0.165–0.886 ms**
across the twelve runs. This is cooperative query cancellation at that seam,
not cancellation during every parser/index operation or UI responsiveness.

**Decision:** this baseline supports a focused experiment with request-local
snapshot reuse and shared local-binding resolution before a persistent
occurrence index. The two 1,041-file workloads return the same 64 usages, yet
the local same-spelling corpus has far greater query cost, allocation and
caller-thread graph loads. A name-occurrence index would retain these spelling
candidates; its existence alone would not remove repeated semantic work. The
existing native snapshot-aware reference API is the appropriate place to test
batching while preserving final resolved identity, law grouping, alias/member
and constructor/type distinctions. Do not duplicate the resolver or serialize
root-dependent facts into file stubs. No batching speedup has been measured or
implemented in this slice, and no persistent index or timing CI gate was added.

Final validation used the full JDK 21 and exact pinned inputs above:

```sh
./gradlew check buildPlugin verifyPlugin verifyPluginStructure verifyPluginProjectConfiguration
```

The command **passed**. Main suite: **604 cases, zero failures/errors, one
optional current-release smoke skipped** (603 executed). All pinned compiler
fixtures ran. Architecture suite: **2 passing tests**. Scoped mutation,
production cast and fatal value-discard negative probes passed, as did formatting
and plugin structure/configuration checks. Plugin Verifier reported compatible
for **IC 2025.1** and **IU 2026.1**, retaining its API usage notices. The built ZIP
contains all three changed production owners and excludes profiling test classes
and research records. The supplied pinned Bend checkout remains clean.

**Implementer self-review** under `REVIEWER.md`: reviewed the change against
`499fecb`, the user's profiling/race slice and the relevant #13/#14/#17/#19
criteria. Traced the catalog precedence and current-path contract through root
capture/freshness, native references, documentation links, module/type navigation,
proof generation/links and static dependencies; existing rename and editing
consumers remain in the passing full suite. A1–A4 retain Scala ownership, one
source-selection rule in the IntelliJ adapter, allowed symbol-to-workspace-API
dependencies and explicit immutable path data in the API. A5–A7 preserve the
actual modified document revision, canonical source identity, root-owned
publication and genuine bounded compiler judgments; close/reopen rejects the
old root check, and capture disposal terminates its worker without dispatch.
A8–A9 preserve bounded native usage discovery, current buffers, final physical
identity/category/name/offset checks and existing source-edit/undo owners. Both
production owners and the API are already required by compiled architecture
inspection; no rule was weakened. No actionable finding remains in this scope.
This is self-review, not independent review.

Remaining limits: the profile is synthetic and local, has phase-order/JIT/GC and
shared-host effects, and measures caller-thread allocations/graph loads rather
than the entire process. The watch witness delivers a notification after a real
disk write; it does not exercise conflict-resolution UI. Native symlink/canonical
witnesses ran on macOS, not every supported filesystem/host. Request-local
batching and a persistent occurrence index remain unimplemented, with no claimed
speedup. There was no compiler change, dependency addition, version increment,
merge into the user's working tree or publication.
