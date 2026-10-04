# bend2-lsp-rs against Bend IDEA

Inspected 2026-10-04. Reference: [IlyaGulya/bend2-lsp-rs, 1e75117a8d5be38d648f65f25d854c894cffbafd](https://github.com/IlyaGulya/bend2-lsp-rs/tree/1e75117a8d5be38d648f65f25d854c894cffbafd), package 0.2.5; local checkout `../bend-idea-comp/bend2-lsp-rs`. Product baseline: Bend IDEA `aa206dc7f937b54a1fe95af005d801a98551668a`, plus the existing working tree inspected during this task. This is original comparison research, not a feature implementation or an independent product review.

Subsequent implementation and actual native measurements are recorded in [implementation evidence](bend2-lsp-rs-implementation.md). Statements below about gaps and unmeasured latency describe the pre-implementation research baseline.

## Recommendation

Keep the native Scala plugin. The strongest reusable work concerns **performance measurement, immutable query indexes, graph-specific synchronization and lifecycle regression scenarios**. Most advertised LSP features already have native counterparts. Two useful optional UI additions are declaration-based type navigation and reference-count Code Vision; neither requires adopting an LSP server or another parser.

The research establishes an opportunity to investigate query cost, not a demonstrated native performance bug. No native runtime latency was measured. Searches found no registered dedicated type-definition handler or reference-count Code Vision provider; that establishes a narrow registration gap, not that current documentation/navigation is incapable of finding types. Existing static dependency inspection already supplies incoming/outgoing named-call information.

Priority reassessment on 2026-10-04: the parent rechecked native loader policies and coverage and consulted **gpt-6-astra** for a separate, read-only prioritization assessment under REVIEWER.md. Astra independently recommended moving loader conformance ahead of speculative performance/observability work. This is an independent assessment of priorities, not independent verification of an implementation. No additional tests or timing measurements were run for this reassessment.

| Tier / order | Work | Why now, cost and evidence | Bounded next deliverable |
|---|---|---|---|
| Highest / first | Canonical namespaces and repeated imports of one physical source | Documented current-compiler compatibility gap affects loading, navigation and checks. Medium/high implementation risk because graph identity and materialization consumers share the policy. | Compare two spellings of one physical file, including a symlink, under two roots against real pinned Bend and native navigation/checking. Correct the shared loader contract and affected snapshot, freshness, move/rename consumers together. |
| High / next investigation | Measure Find Usages and static dependency queries | Candidate PSI traversals are concrete; user-visible slowdown is unmeasured. A bounded baseline is cheaper than introducing a speculative cache. | Measure cold/warm queries, dependency/unrelated edits, cancellation latency and editor responsiveness on original representative projects. Decide whether an occurrence candidate index is justified; retain final native resolution. |
| Medium | Cached named packages | Documented loading gap, but broader than the original cached-hash-path scope. Raise priority if actual projects use named Hub packages. | Separate offline cached-package conformance slice after the base loader policy; no package fetching. |
| Medium | Source-based Go to Type Definition | Clear optional user convenience; dedicated registration absent. Small/medium scope if it consumes existing symbols. | Resolve an aliased nested annotation with shadowing through the shared resolver; unavailable when the source annotation cannot identify a target. |
| Medium, conditional | A missing pending-dependency/immediate-navigation witness | Existing capture/publication races cover much of the initial recommendation. Unrelated-query responsiveness during dependency work remains a distinct candidate, with no native failure established. | Audit for that exact observable scenario; add one original native witness only if absent. |
| Low, conditional | Full tracing pipeline | Useful for investigating a concrete latency problem; currently no evidence for a separate high-priority subsystem. | Start with narrow timing inside profiling; expand to opt-in traces only when the baseline leaves an actionable wait unexplained. |
| Low | Reference-count Code Vision | Find Usages already exists; automatic counts add background work and scope/completeness questions. | Specify counting scope and prove bounded, current counts before adding presentation. |
| Low | Hierarchical UI over static dependencies | Existing report supplies incoming/outgoing named calls. | Add native hierarchy only for a demonstrated workflow need, through a shared API rather than feature-handler dependencies. |

The loader evidence is stronger than the performance hypothesis: [compiler support](../docs/compiler/support.md) explicitly records canonical/symlink namespaces and repeated-import divergence. [BendImportPaths.scala](../src/main/scala/com/dearlordylord/bend/idea/workspace/api/BendImportPaths.scala) derives namespaces from written edges; [BendGraphLoader.scala](../src/main/scala/com/dearlordylord/bend/idea/workspace/loading/BendGraphLoader.scala) rejects an already-seen canonical file under another namespace. Existing [navigation fixtures](../src/test/scala/com/dearlordylord/bend/idea/features/navigation/BendNavigationTest.scala) deliberately assert this rejection. A green suite preserves that older policy; it cannot establish conformance to the newer compiler rules. This remains a documented compatibility limitation, not an allegation that the prior conforming implementation silently violated its contract. Do not merely remove the conflict guard: update architecture, consumers and real compiler/editor witnesses together (A3, A5, A7–A9).

Existing [capture fixtures](../src/test/scala/com/dearlordylord/bend/idea/adapters/intellij/BendExplicitCheckRunnerServiceTest.scala) already cover retargeted aliases, missing imports appearing, dependency edits during graph loading and unsaved revision rejection. [background fixtures](../src/test/scala/com/dearlordylord/bend/idea/features/checking/BendBackgroundCheckingTest.scala) cover cancellation, independent roots and superseded publication. This reduces the priority of a broad new race-test campaign; it does not establish unrelated-query responsiveness. Candidate-index implementation, persistent tracing and caches remain conditional on evidence, rather than automatically following profiling.

## What the implementation actually does

### Cold source construction, warm indexed queries

[analysis.rs](https://github.com/IlyaGulya/bend2-lsp-rs/blob/1e75117a8d5be38d648f65f25d854c894cffbafd/src/analysis.rs) puts source text, revision, line index and syntax index in `DocumentSnapshot`. [syntax.rs](https://github.com/IlyaGulya/bend2-lsp-rs/blob/1e75117a8d5be38d648f65f25d854c894cffbafd/src/analysis/syntax.rs) builds token/name tables, declarations, binders, occurrences, call sites, argument ranges and indexed incoming/outgoing call spans. Warm references, inlays and call queries reuse these records instead of rebuilding all language facts for every request.

The transferable principle is to prepare reusable facts once per relevant revision. The exact Rust storage design is not a proposal to duplicate PSI. IntelliJ stubs must remain file-local; root-dependent alias resolution must not be serialized into them (A3/A8). Any projection needs explicit source/loading revision keys, cancellation and bounded lifetime.

Our [BendUsageSearch.scala](../src/main/scala/com/dearlordylord/bend/idea/symbols/references/BendUsageSearch.scala) currently gathers candidate files, walks reference/name PSI, filters spelling and resolves candidates through native identity. This preserves semantics, but is a concrete place to profile repeated work. [BendStaticDependencies.scala](../src/main/scala/com/dearlordylord/bend/idea/features/navigation/BendStaticDependencies.scala) scans a bounded inventory (512 files, 1 MiB per source), captures snapshots, resolves named calls and rejects changed inputs. An occurrence candidate index could narrow these traversals while retaining final native resolution. It must not become a second resolver.

[benches/analysis.rs](https://github.com/IlyaGulya/bend2-lsp-rs/blob/1e75117a8d5be38d648f65f25d854c894cffbafd/benches/analysis.rs) distinguishes prebuilt warm snapshots from snapshot construction, ASCII/Unicode mapping and workspace workloads. [performance-policy.md](https://github.com/IlyaGulya/bend2-lsp-rs/blob/1e75117a8d5be38d648f65f25d854c894cffbafd/docs/performance-policy.md) separates Callgrind instruction/cache counts, allocation profiles and real-process LSP latency. Those published historical measurements compare their own revisions, not our plugin. Do not copy their percentage thresholds into JVM CI without baseline measurements, warmup/GC controls and stable hardware.

Rust's ASCII bitset and sparse UTF-16 checkpoints solve byte-offset conversion. IntelliJ documents already expose UTF-16 offsets: porting that entire index would solve the wrong boundary. Investigate compiler-to-editor mappings only where our source maps actually convert coordinate systems, keeping CRLF and astral tests.

### Current buffers and graph-specific synchronization

[workspace.rs](https://github.com/IlyaGulya/bend2-lsp-rs/blob/1e75117a8d5be38d648f65f25d854c894cffbafd/src/workspace.rs) retains separate open/disk snapshots, chooses open text first and stores forward/reverse import edges. Closing restores disk state. Compiler-owned generated Base sources retain their temporary backing directory and are excluded from root checking by provenance rather than basename alone.

[lsp.rs](https://github.com/IlyaGulya/bend2-lsp-rs/blob/1e75117a8d5be38d648f65f25d854c894cffbafd/src/server/lsp.rs) tracks pending document generations and waits for relevant revisions before cross-file queries. Cold work and staging use blocking-worker boundaries. This avoids returning a committed old dependency immediately after a preceding edit, without making every unrelated document wait for global indexing.

Our [BendLibrarySourceService.scala](../src/main/scala/com/dearlordylord/bend/idea/adapters/intellij/BendLibrarySourceService.scala), [BendRootSnapshotCapture.scala](../src/main/scala/com/dearlordylord/bend/idea/adapters/intellij/BendRootSnapshotCapture.scala), native reference snapshots and current-buffer contract already address the same ownership. Reuse the adversarial scenario, not the Tokio mechanism. IntelliJ commit/read-action lifetime remains authoritative.

Useful upstream protocol witnesses in [lsp_protocol.rs](https://github.com/IlyaGulya/bend2-lsp-rs/blob/1e75117a8d5be38d648f65f25d854c894cffbafd/tests/lsp_protocol.rs) include immediate queries after a pending importer update, unsaved dependency waits without delaying unrelated queries, disk reads concurrent with a newly opened import, and close fallback. These are **test sources inspected**, not by themselves evidence that tests passed.

### Compiler caching and cancellation

[compiler.rs](https://github.com/IlyaGulya/bend2-lsp-rs/blob/1e75117a8d5be38d648f65f25d854c894cffbafd/src/server/compiler.rs) stages reachable sources, appends `--check-only`, bounds compiler concurrency through a semaphore, retains resources while child cleanup is outstanding and caches diagnostics against executable/configuration/source-graph inputs. Snapshot comparison permits identical source text across revisions. Import graphs with Base, absolute imports, Hub imports or incomplete targets are conservatively not cacheable. Executable identity uses size/mtime metadata.

The compiler command path collects stdout/stderr into vectors with `read_to_end`; inspection did not find a per-check deadline or byte cap there. Semaphore/cancellation behavior does not establish these A6 guarantees. Retain our bounded process/deadline/output controls.

The good idea is explicit cache eligibility, not assuming every check can be reused. Our compiler identity, external-input freshness, selected root and reliance/completeness contracts are richer; retain them (A5–A7). If identical-text reuse is later measured useful, result reuse still needs the *current* reservation/provenance and relevant external-input equality. An executable's size/mtime alone is not an improvement over the existing toolchain contract. Never use a cached result to imply freshness after a package retarget or compiler replacement.

[README diagnostics contract](https://github.com/IlyaGulya/bend2-lsp-rs/blob/1e75117a8d5be38d648f65f25d854c894cffbafd/README.md) and actual decoder report at most one compiler diagnostic per check, using human-readable excerpts with conservative root fallback. Our structured diagnostics and genuine query ports should remain the authority; replacing them with this decoder would reduce capability.

### Tracing and honest limits

[telemetry.rs](https://github.com/IlyaGulya/bend2-lsp-rs/blob/1e75117a8d5be38d648f65f25d854c894cffbafd/src/server/telemetry.rs) enables Chrome/Perfetto traces only with `BEND2_LSP_TRACE`, retains a flush guard and filters server-owned spans. [tracing_protocol.rs](https://github.com/IlyaGulya/bend2-lsp-rs/blob/1e75117a8d5be38d648f65f25d854c894cffbafd/tests/tracing_protocol.rs) is an executable test boundary for trace behavior. This is a useful low-intrusion diagnostic pattern for explaining delayed checks, document waits and cancellation. Native tracing should record revision/root/job identifiers and durations; source bodies are unnecessary.

The README distinguishes source declaration hover/parameter hints from compiler inference, cached-package navigation from fetching, and an indexed loaded graph from whole-project discovery. These are valuable documentation distinctions already consistent with CONTEXT.md and A7. Preserve them for dependency UI and source type navigation.

## Feature parity and real deltas

| Upstream feature | Current native comparison | Decision |
|---|---|---|
| Completion, imports, Base, signatures, parameter-name inlays | Native completion/signature slices and source catalogs already exist | Retain native owners; compare a new boundary witness only |
| Hover, symbols, semantic highlighting | Native documentation, choose-by-name/stub search and semantic annotator already exist | No reason to adopt source scanner or transport |
| Definition, references, rename | Native PSI references, usages, locals/aliases/declaration/law and file moves are broader | Preserve native binding, preview and undo |
| Folding, selection, newline/range formatting | Existing editor and shared layout policy, including EditorConfig/offline formatter | Retain one formatter owner; upstream formatter has its own scanner |
| Call/type hierarchy | Incoming/outgoing source dependencies already implemented; dedicated ADT hierarchy UI is optional | Presentation opportunity, not absent source-call support |
| Type definition request | No dedicated corresponding registration found | Optional source annotation navigation, distinct from expression inference |
| Reference-count lenses | No native corresponding provider found | Optional Code Vision, reuse identities and bounded discovery |
| Delimiter-closing code actions | Existing brace matching/typing and inspections cover related behavior | First reproduce an actual missing native action before adding another |
| Compiler diagnostics | Native CLI plus structured helper and root status model are richer | Preserve structured ranges and root status; both current integrations report the first compiler error |
| Run/build, proof roots, goals, normalization, resource semantics | LSP explicitly lacks run/build and rich compiler semantics; native product has dedicated slices | Native differentiators, not features to borrow from this project |

Product paths examined include [plugin.xml](../src/main/resources/META-INF/plugin.xml), [BendSourceSymbols.scala](../src/main/scala/com/dearlordylord/bend/idea/symbols/api/BendSourceSymbols.scala), [BendSourceSemantics.scala](../src/main/scala/com/dearlordylord/bend/idea/symbols/api/BendSourceSemantics.scala), [BendStaticDependencies.scala](../src/main/scala/com/dearlordylord/bend/idea/features/navigation/BendStaticDependencies.scala) and the listed adapters. Upstream feature transport is in [features.rs](https://github.com/IlyaGulya/bend2-lsp-rs/blob/1e75117a8d5be38d648f65f25d854c894cffbafd/src/server/features.rs) and [lsp.rs](https://github.com/IlyaGulya/bend2-lsp-rs/blob/1e75117a8d5be38d648f65f25d854c894cffbafd/src/server/lsp.rs). Feature names in a README are not a completeness proof for either product.

The upstream type-definition implementation is deliberately narrower than a general type resolver: for a local binder it scans annotation tokens and chooses a matching current-file datatype declaration. This is motivation for a useful UI, not code to reuse for arbitrary nested/imported types. The inspected code-lens path emits positive document-local reference counts for non-law functions; it is not a whole-project law/fill-aware count. A native design should state its counting scope and rely on existing shared identities.

## Compiler compatibility

The Rust server does not bundle Bend, negotiate a supported Bend version or use our structured helper. It assumes `bend <staged path> --check-only` and `bend base`; configured arguments are passed as process arguments. Its performance note records real-compiler measurements with **2.0.32**, while our [CI pin](../ci/bend-test-toolchain.properties) is **2.0.35**, compiler `79df8d9c40722ee9507a1e253f283b51025f9d6c`. Most protocol tests use test compiler executables. The real Bend cache measurement is explicitly ignored in normal suites.

Consequently, a successful Rust suite does not establish 2.0.35 syntax/loading/diagnostic compatibility. Named package behavior needs our compiler's own root/canonical-namespace tests. Offline editing should continue when compiler facts are unavailable; compiler-owned source is navigation material, not proof success.

## License and provenance

[Cargo.toml](https://github.com/IlyaGulya/bend2-lsp-rs/blob/1e75117a8d5be38d648f65f25d854c894cffbafd/Cargo.toml) declares Apache-2.0, and the checkout contains the [Apache-2.0 LICENSE](https://github.com/IlyaGulya/bend2-lsp-rs/blob/1e75117a8d5be38d648f65f25d854c894cffbafd/LICENSE). No separate tracked NOTICE or alternative license file was found in this revision; inspected primary Rust source headers did not add a different file-specific license. Dependencies in Cargo.lock retain their own terms and require separate review if distributed.

This report copies no upstream code, fixture bodies, hover prose or benchmark inputs. It introduces no runtime dependency and no upstream notice payload into our plugin. Ideas can be independently implemented in Scala. Any later substantial adaptation must record immutable source/destination paths, modifications and applicable notices under [reuse policy](../docs/reuse-policy.md), then inspect the actual source/runtime archives containing it. A citation alone does not satisfy notice obligations.

## Validation and self-review

Read issue #1, AGENTS.md, REVIEWER.md, ARCHITECTURE.md, CONTEXT.md and reuse guidance; inspected actual upstream source, protocol/analyzer tests, benchmark harness and current native owners/tests. Existing native witnesses include `BendNavigationTest`, `BendDeclarationRenameTest`, `BendBackgroundCheckingTest`, `BendCheckTransitionPolicyTest`, `BendCliGraphCheckTest`, `BendStaticDependenciesTest`, `BendStaticDependenciesCancellationTest`, `BendLibrarySourceServiceTest` and structured diagnostic tests. They were read as existing coverage; no native tests or plugin build were run for this documentation-only research.

The parent ran `cargo +1.98.1 test --locked` in disposable `/tmp/bend2-lsp-rs-research.1Y6ZsT`: exit 0, **96 passed, 0 failed, 1 ignored** (library 11, analysis 16, protocol 62 passed plus the ignored real-Bend timing probe, release E2E 2, tracing 5). The reference checkout remained clean. These results exercise the upstream suites, including fake compiler process boundaries, rather than proving our compiler compatibility. The preserved reference checkout is not a test/build destination. Full quality helper installation, Callgrind performance gates, real installed-Bend manual measurement and actual editor-rendering latency are separate checks and must not be inferred from Cargo unit/protocol tests.

**Self-review**, following REVIEWER.md: scope is one original research note; no plugin owner/API changed, no changelog entry applies, and no architectural exception is proposed. Recommendations preserve A1–A4 owner/dependency/purity boundaries, A5–A7 root/freshness/compiler authority and A8–A9 current-buffer/native identity/undo. The review found no actionable architecture violation in this documentation change. Optional ideas and inspection-based opportunities are explicitly distinguished from demonstrated product defects. Independent implementation review was not performed; the subsequent Astra assessment concerns prioritization only, as recorded above.
