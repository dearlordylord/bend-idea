# Native implementation after the Rust LSP comparison

2026-10-04. Base: local `master` and `origin/master` at `aa206dc7f937b54a1fe95af005d801a98551668a`. This follows [the comparison and Astra priority assessment](bend2-lsp-rs-reuse.md). Implementations, fixtures and benchmark inputs are original Scala/native IntelliJ work; the Rust server is neither bundled nor used as a runtime dependency.

## Delivered scope

| Priority | Result | Evidence / boundary |
|---|---|---|
| Highest: loader conformance | One canonical source per physical target, namespaces relative to each root or canonical package cache, relative imports from the real importing directory. Written edges remain available for navigation and path edits. | `BendCliGraphCheckTest` compares original files against real pinned Bend 2.0.35, including repeated symlink aliases, two roots, a misleading symlink-neighbour file and unsaved imported text. Native navigation/documentation and file-move consumers use the same policy. Cycles and graph limits remain explicit. |
| High: query profiling | Opt-in native snapshot, Find Usages and static dependency measurements, separately recording setup, first query, warm repetitions and dependency/unrelated edits. | `BendSourceQueryProfileTest`; CSV and scope below. No permanent cache/index was added. |
| Medium: source type navigation | Registered native Go to Type Declaration for explicit nominal binder/result annotations and constructor datatypes. | `BendTypeNavigationTest` uses the actual IntelliJ action, including nested imported annotation heads, unsaved imported declarations, own-annotation scope, shadowed binders/alias prefixes and absence of an annotation. Existing shared lexer, resolver and physical-target owner are reused. |
| Medium: pending-dependency witness | One worker capture can remain pending while immediate imported navigation observes a committed unsaved dependency edit and unrelated navigation completes. | `BendNavigationFreshnessTest` gates only the workspace I/O boundary, then invokes native Go to Declaration before releasing it. No new scheduler, global lock or race framework. |
| Medium, raised by user: Hub | Offline `name@four.part.version/file.bend` resolves bounded cached `names/<name@version>` records to exact lowercase 128-bit hashes. Completion keeps the written name. | Real compiler/native fixtures compare a name and hash alias; missing or malformed records remain unavailable. Snapshot fingerprint/freshness includes cache identity and observed mappings, including missing names. Frozen candidate overlays retain captured mappings. Native mapping tests cover malformed/oversized/escaping names; loader tests cap distinct metadata lookups. No package fetching. |

The remaining source-loader model is intentionally not advertised as exhaustive Bend 2.0.35 conformance. Existing last-direct-alias-wins behavior differs from the compiler's duplicate-alias rejection, and not every compiler path grammar restriction is modeled. Genuine checks remain authoritative. Source type navigation does not infer expression types and does not guess anonymous/parenthesized annotation targets or local type parameters.

## Master and changelog overlap

The latest 0.1.17 updates were already present before these edits: normalized-value pins, compiler capability negotiation, lambda binding fixes, grouping/selection fixes, syntax documentation and the Bend 2.0.35/Bun 1.4.2 upgrade. This checkout has one worktree, on `master`; fetching confirmed no newer `origin/master` commit, so no `work3` pull/merge was needed.

The overlap is shared loader/snapshot/check infrastructure, rather than duplicate features. Compiler subprocess tests use the current committed pin and the full validation includes existing semantic/normalized-inlay consumers. Published 0.1.17 notes and version metadata remain intact; new plugin behavior appears under Unreleased. Neither a new release nor publication is part of this slice.

## Reproducible profile

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
BEND_TEST_COMPILER_DIR=/tmp/bend-idea-compiler-2.0.35 \
BEND_TEST_BUN=/tmp/bend-idea-bun-1.4.2/bun-darwin-aarch64/bun \
./gradlew test -PbendSourceProfile=true
```

Output: `build/reports/source-query-profile.csv`. The [recorded CSV](bend2-lsp-rs-source-profile.csv) comes from the successful final profile invocation on macOS 27.0 ARM64, IntelliJ Community 2025.1 fixtures. Gradle/compilation used Homebrew JDK 21.0.10; the platform test task selected the bundled JBR 21.0.6+9-b895.109 fixture JVM. Each operation creates a fresh 49-file project: one shared dependency, 24 importers with eight calls each plus a shadowed local name, and 24 unrelated files. Both full searches assert exactly 192 incoming usages/calls. Snapshot queries assert two loaded files and one visible direct declaration. Fixture construction is excluded from query timing. Five warm repetitions follow each first query; unsaved edits append comments and are committed to PSI without saving.

| Native operation | First query ms | Warm median / observed max ms | After dependency edit: first / warm median ms | After unrelated edit: first / warm median ms | Warm median allocation, current thread MiB |
|---|---:|---:|---:|---:|---:|
| Navigation snapshot | 69.661 | 2.215 / 4.375 | 2.388 / 1.139 | 2.516 / 0.571 | 0.046 |
| Find Usages | 980.136 | 170.220 / 229.179 | 259.613 / 164.477 | 144.731 / 110.403 | 26.079 |
| Static dependencies | 206.457 | 25.621 / 28.570 | 33.429 / 37.229 | 22.618 / 25.163 | 11.047 |

Cooperative cancellation after the first actual graph capture was observed after **0.262 ms** for Find Usages and **0.133 ms** for static dependencies. The test requests cancellation at that deterministic boundary and measures until `ProcessCanceledException`; it does not simulate arbitrary mid-I/O cancellation or keystroke latency. Separate pre-canceled request measurements test the runner's entry guard; those do not execute the query. Existing cancellation fixtures remain the authority for native worker and subprocess lifecycle behavior.

These are a bounded local baseline, not p95 figures, hardware-independent guarantees, isolated cold-JVM comparisons or editor-rendering measurements. All operations share one test JVM, so JIT/platform initialization and order affect first-query values. Allocations cover the current thread, not all IntelliJ worker threads, retained heap or peak RSS; `-1` means unavailable. No unstable timing gate was added to CI. The separate pending-capture fixture establishes a concrete unrelated-navigation responsiveness property, not responsiveness during every search or compiler operation.

Decision: Find Usages is the strongest next optimization candidate: repeated resolution costs substantially more than batched dependency inspection in this sample. A production occurrence candidate index still needs a larger sparse-project baseline, multiple controlled JVM runs and a demonstrated reduction while preserving local/law/import identities, unsaved edits and final native resolution. This change supplies the executable baseline without adding a speculative second resolver or persistent root-dependent index. Full tracing and Code Vision remain the lower-priority work identified in the assessment.

## Validation and review

Focused native action/compiler/loader/completion/freshness fixtures and the opt-in profile passed. The loader and named-package compiler witnesses first reproduced genuine production failures before their corrections; the dedicated type-action and named-completion witnesses also reproduced missing behavior. Additional already-correct boundary cases are coverage, not claimed red regressions. A sole-candidate completion assertion, provisional-root fixture identity, VFS permission setup and profiling API misuse were corrected as test construction, not reported as product defects.

The first integrated run executed 597 cases and exposed two existing fixture permission gaps when canonical paths used macOS's real `/private/var` temporary directory. `BendBuildConfigurationTest` and the read-only cached-law rename fixture now explicitly use the existing `VfsTestRoots` helper; both targeted regressions passed. This is test-only filesystem access, not a production permission or architecture relaxation.

**Implementer self-review** under `REVIEWER.md`: reviewed the diff against `aa206dc`, its current source/navigation, documentation, completion, move/rename, build protection, compiler materialization, candidate-overlay and freshness consumers. A1–A4 retain Scala ownership, a shared pure path/loader policy and adapter-only filesystem reads. A3/A5 retain written import spelling, canonical source identity, root namespaces and captured package mappings separately; absent/retargeted mappings invalidate captured results and candidate overlays perform no new live lookup. A6/A7 retain closed offline compiler staging, process bounds and honest source/semantic result distinctions. A8/A9 retain native resolution, current PSI buffers, physical target rechecks, file-local index rules, conservative refactoring and undo. New production classes are explicitly included in the compiled architecture inspection. No actionable finding remains in the reviewed implementation scope; no independent implementation review or architectural exception is claimed. Astra's separate assessment covered priorities only. Loader grammar limits, source type limits and local profiling scope are stated above; canonical filesystem/editor witnesses ran on macOS, not all supported host filesystems.

Final JDK 21 invocation passed: `./gradlew check buildPlugin verifyPlugin verifyPluginStructure verifyPluginProjectConfiguration`, with the exact committed Bend 2.0.35 (`79df8d9c40722ee9507a1e253f283b51025f9d6c`) and Bun 1.4.2 (`744846f84`) inputs. Main suite: **597 cases, zero failures/errors, one optional current-release smoke skipped** (596 executed); all pinned-compiler fixtures ran. Compiled architecture suite: **2 passed**. Negative warning/mutation/cast enforcement probes and formatting checks passed. The separate opt-in profile ran **3 passing tests**.

Plugin Verifier reported compatible for **IC 2025.1** and **IU 2026.1**, retaining API usage notices; structure and project configuration checks passed. Inspection of the built plugin ZIP confirmed the registered native type provider and its shared production owners, with no profiling test classes or Rust source. Original work adds no upstream notice payload; reference checkout status remained clean. No version increment, signed release, push or publication was performed.
