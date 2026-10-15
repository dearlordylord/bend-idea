# Native Find Usages request-local batching

2026-10-14. Original Scala work motivated by the measured recommendation in
[the usages/races investigation](../bend2-lsp-rs-usages-races.md), whose immutable
upstream sources record the indexing/allocation motivation. No upstream code,
fixture input or prose is copied or adapted; no dependency or notice obligation
is introduced. Production destinations are `symbols/api/BendSnapshotAwareReference.scala`,
`symbols/references/BendReferenceContributor.scala` and
`symbols/references/BendUsageSearch.scala`. These classes enter the plugin;
this report, metadata and measurement artifacts do not.

The shared native reference now accepts a per-file snapshot for physical target
reacquisition, including alias-prefix references, binders and ambiguous targets.
Find Usages captures one lazy snapshot per matching file, within the existing
request read action. Final source identity/category/offset comparisons and law
anchoring remain unchanged. There is no persistent semantic index or cache.

## Bounded experiment

One fresh IntelliJ fixture JVM before and one final measured JVM after, 49 files (24 importers,
24 unrelated files containing same-spelling local binders), eight imported
calls per importer, three warm repetitions per phase. The existing profile
asserts exactly 192 usages on every successful query, including after committed
unsaved dependency and unrelated edits. Each invocation passed all twelve
successful-query count assertions and both cancellation probes. Workload,
command, environment and source hashes are in [metadata.json](metadata.json).
Raw [before CSV](before.csv) and [after CSV](after.csv) retain all records.
The after measurement was refreshed after required Spotless formatting so its
source hashes identify the final formatted implementation; this replaces the
pre-format after measurement, rather than combining runs.

| Phase | Before median ms | After median ms | Before caller allocation MiB | After caller allocation MiB | Before caller graph loads | After caller graph loads |
|---|---:|---:|---:|---:|---:|---:|
| First query (one sample) | 518.19 | 529.54 | 65.25 | 41.06 | 688 | 85 |
| Initial warm (three samples) | 262.04 | 93.43 | 29.29 | 17.65 | 504–514 | 79 |
| Dependency-edit warm (three samples) | 75.53 | 56.99 | 30.07 | 17.49 | 516–554 | 79–99 |
| Unrelated-edit warm (three samples) | 56.86 | 49.32 | 29.67 | 16.77 | 524–542 | 69–89 |

Cancellation after the first real graph capture completed in 0.353 ms before
and 0.187 ms after; neither cancellation probe returned usages. Cancellation
allocations are unavailable. The fixture JVM was Linux aarch64, JBR
21.0.6+9-b895.109; full JDK 21 ran Gradle. Compiler input verification passed
with the unchanged Bend 2.0.35 pin and Bun 1.4.2. Source-only queries do not
invoke the compiler.

The caller-thread graph-load and allocation reduction supports retaining this
small batching change. Latency evidence is limited: the first query became
slower, and warm timings come from one JVM per implementation with three
repetitions, shared-host background activity and JIT/GC/phase-order effects.
The before invocation overlapped another worker's Gradle run; the after
invocation had an exclusive Gradle slot. These timings do not establish a
portable speedup, editor rendering latency or a statistically controlled causal
comparison. Caller allocations and graph loads exclude other platform workers.
No timing threshold or whole-process memory claim is introduced.

## Native validation and self-review

Focused `BendUsageTest` and `BendNavigationTest`: 28 passing tests, no failures
or errors. These cover alias/member ranges, local shadowing, literal dotted
names, law/fill anchoring, constructor/type distinctions, unsaved imported
source, selected subtree scopes, forward references and pooled-thread read
access. The opt-in after profile passed. Final whole-project, rename,
architecture, packaging and verifier gates are owned by the parent milestone
and are not claimed here.

Implementer self-review under `REVIEWER.md`, against issue #19 and its #17
navigation dependency: A2/A3 retain a single symbol/reference owner and shared
resolver; A8 retains bounded native candidate inventory and current-buffer
precedence, with request-local graph reuse only. Native navigation and batching
share the exact physical-target implementation, including ambiguity returning
no single target; reference ranges and category/law identity checks are
preserved. Existing static dependency consumers retain `resolveAgainst` and
the reference factory. No actionable finding remains in this bounded scope;
this is self-review, not independent review.
