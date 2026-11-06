# Comparison-driven implementation provenance

This record covers the offline syntax documentation and regression coverage proposed in the comparison specs. All new implementation, descriptions and fixture inputs in these slices are independently authored. No community hover catalogue, grammar, scanner, test body or golden output was copied or substantially adapted. The proposals supply requirements; upstream examples identify risks rather than supply our expected output.

## Offline syntax documentation

Original destination paths:

- `src/main/scala/com/dearlordylord/bend/idea/features/documentation/BendSyntaxDocumentation.scala`
- Changes to `src/main/scala/com/dearlordylord/bend/idea/features/documentation/BendDocumentationProvider.scala`
- New fixtures in `src/test/scala/com/dearlordylord/bend/idea/features/documentation/BendDocumentationTest.scala`

The catalogue uses the proposal's original descriptions, checked against the [comparison-baseline Bend 2.0.25 guide](https://github.com/bendlang/bend/blob/ff7a40cc9070a34c78399ecd2bbe46a044ad9b4b/guide/GUIDE.md) and [the official guide at the approved Bend 2.0.35 revision](https://github.com/bendlang/bend/blob/79df8d9c40722ee9507a1e253f283b51025f9d6c/guide/GUIDE.md) and existing lexer token identities. The comparison baseline used Bend 2.0.25; the completed upgrade pins Bend 2.0.35 (`79df8d9c40722ee9507a1e253f283b51025f9d6c`). The actual `ci/bend-test-toolchain.properties` used by each validation invocation is the compiler oracle. The documentation fixtures and existing real compiler type-expiry fixture passed against both revisions with Bun 1.4.2. [Community LSP analysis](https://github.com/don2e4/bend2-lsp/blob/d85e556febcd3e787c48f4558b5f89a34729626d/src/test/analysis.test.ts) supplies prior-art motivation for syntax hover; its strings and fixture bodies are not used. Token ranges, literal/comment suppression, offline dispatch and unsaved edit/repair scenarios derive from the native provider's contract and OH1–OH7 acceptance criteria.

Production classes enter the plugin package. Fixtures enter source distributions, not the plugin. No new third-party license text or NOTICE is required for this original material.

## Regression coverage

The [regression coverage map](regression-fixtures.md) identifies existing and new native test methods, each variant's observable invariant and preventive mutation. New programs are minimal original cases for adjacency, layout, editable recovery and root/diagnostic identity. Expected formatting follows Bend IDEA's shared layout policy; compiler judgments use `ci/bend-test-toolchain.properties`, not community parser acceptance.

Original regression destinations under `src/test/scala/com/dearlordylord/bend/idea` are `features/formatting/BendAdjacencyRegressionTest.scala`, `features/editing/BendRecoveryRegressionTest.scala`, and additions to `syntax/lexer/BendLexerTest.scala`, `features/checking/BendBackgroundCheckingTest.scala` and `adapters/cli/BendCliGraphCheckTest.scala`. No production correction or derived upstream material was needed for these preventive fixtures.

Pinned motivation sources:

- [Community formatter tests](https://github.com/don2e4/bend2-lsp/blob/d85e556febcd3e787c48f4558b5f89a34729626d/src/test/formatter.test.ts): formatting and token adjacency risk.
- [Tree-sitter numeric boundaries](https://github.com/Soulthym/tree-sitter-bend2/blob/55e699af7e136f54a88939c9a3b7cd2eaad500bf/test/corpus/numeric-boundaries.txt) and [boundaries](https://github.com/Soulthym/tree-sitter-bend2/blob/55e699af7e136f54a88939c9a3b7cd2eaad500bf/test/corpus/boundaries.txt): larger token and malformed-buffer risk.
- [Official compiler](https://github.com/bendlang/bend/blob/79df8d9c40722ee9507a1e253f283b51025f9d6c/bend2/bend.ts): language recognition and physical-layout research at the approved 2.0.35 revision; acceptance still follows the actual test-toolchain properties.

Fixtures are source/test material and are not plugin runtime payloads. Existing tests retained by the coverage audit are evidence for their stated invariants; this record does not reclassify historical material or claim an upstream test execution. See [reuse policy](reuse-policy.md) for any future adaptation and required archive verification.

## Native scope, repair and angle boundaries

Original implementation destinations: `symbols/scope/BendScope.scala` and `syntax/parser/BendSelectionExpressions.scala` under `src/main/scala/com/dearlordylord/bend/idea`. Original fixture destinations under the corresponding test root: `features/completion/BendLocalScopeTest.scala`, `features/rename/BendLocalRenameTest.scala`, `features/editing/BendRecoveryRegressionTest.scala`, `features/editing/BendDelimiterTest.scala`, `features/editing/BendSelectionAtomsTest.scala`, and `adapters/cli/BendBoundaryCompilerTest.scala`.

Motivation: [typed local/parallel boundaries](https://github.com/Soulthym/tree-sitter-bend2/blob/55e699af7e136f54a88939c9a3b7cd2eaad500bf/test/corpus/boundaries.txt), [damage/repair policy](https://github.com/Soulthym/tree-sitter-bend2/blob/55e699af7e136f54a88939c9a3b7cd2eaad500bf/docs/recovery-policy.md), and [angle upgrade audit](https://github.com/Soulthym/tree-sitter-bend2/blob/55e699af7e136f54a88939c9a3b7cd2eaad500bf/docs/upgrades/bend-2.0.35.md), all at the recorded MIT upstream revision. These supplied risk categories, not implementation or golden outputs. Minimal fixture programs, native action assertions and the test-only compiler projection were authored here. No upstream scanner, grammar, query, test body or expressive documentation was copied or substantially adapted; no new upstream notice payload is introduced.

Expectations come from the actual branch pin in `ci/bend-test-toolchain.properties` and native shared policy contracts. The real compiler parser fixture distinguishes glued less-than grouping from spaced comparisons and rejects treating an unparenthesized compound first argument as an ADT. Tolerant editor angle matching remains available for damaged inputs and does not assert compiler acceptance. Break/repair tests assert useful binding/reference, completion, selection and folding behavior inside the edited declaration before comparing repaired structure with a fresh parse. Parallel tests assert all binders remain unavailable in each RHS and the damaged final value, then start together after repair. Lambda fixtures test origin/offset identity and native rename without changing an outer parameter.

Production owners enter the plugin; the standalone formatter packages only its existing layout/CLI owners, so these changes add no formatter payload. Test fixtures and this provenance record remain source-distribution material. No runtime parser dependency, alternative symbol model or upstream notice obligation is added by these original changes.

Validation for this slice (2026-10-04, branch `reuse/native-boundaries`, base `ed07648`): full JDK 21 `spotlessApply check buildPlugin verifyPlugin verifyPluginStructure verifyPluginProjectConfiguration` passed. The main suite reported 574 cases, zero failures/errors, one optional current-compiler smoke skipped; architecture tests reported two passing cases. Real subprocess fixtures used the branch's committed Bend `ff7a40cc9070a34c78399ecd2bbe46a044ad9b4b` pin with Bun 1.4.2. The compiler grouping projection was additionally run against Bend 2.0.35 `79df8d9c40722ee9507a1e253f283b51025f9d6c`, with the same seven outputs; this auxiliary probe is not a full 2.0.35 branch validation. Plugin Verifier passed the configured IC 2025.1 and IU 2026.1 targets, retaining API notices. No publication or version increment is part of this slice.

Implementer self-review under `REVIEWER.md`: inspected the diff against the recorded master base and traced `BendScope` through source binding eligibility/declaration lookup, native references, completion and rename; selection changes remain in the shared syntax owner consumed by native selection. Live document edits exercise current-buffer recovery, and native rename separates an outer parameter from the lambda binding. A1–A4 and A8–A9 retain their existing boundaries; no API shape, dependency gate or production parser was added. No outstanding finding for the implemented witnesses. This is self-review, not independent review. The surface parser remains tolerant with opaque expressions; these fixtures do not claim full grammar coverage or compiler acceptance of damaged buffers.

Merge validation (2026-10-04): integrated these changes with master including the committed Bend 2.0.35 upgrade and specification-link cleanup. JDK 21 `check` passed with 575 cases, zero failures/errors and one optional current-compiler smoke skipped; two architecture tests passed. `buildPlugin verifyPlugin verifyPluginStructure verifyPluginProjectConfiguration` passed on the integrated tree, including both configured IDE targets. The changelog merge retains both the compiler-upgrade and native-boundary entries. Implementer self-review found no additional behavior or architecture change in conflict resolution.


## Explicit normalized-value inlays

The value-preview UX in [bend-flow at e490b7e](https://github.com/DKormann/bend-flow/tree/e490b7e9ef9c7d83c4b724b2adfb6211cec3ca4c) motivated this independent native implementation. No upstream code, syntax extension, fixtures or documentation strings were copied or adapted. In particular, this action adds no `#?` syntax or Flow-language support. The Scala renderer, editor lifetime management and tests are authored here and consume the existing Bend IDEA current-location inquiry and pinned compiler normalization operation. Upstream root/LSP licensing was not established; only the UX idea is reused.

Original production destinations are `features/semantics/BendNormalizationInlays.scala`, `BendPinNormalizedValueAction.scala` and their plugin registrations. Native editor fixtures live in `features/semantics/BendNormalizationInlayTest.scala`; compiler judgments use the committed test pin. No new dependency, upstream payload or notice is introduced. Pins are opt-in, session-only displays and never source or proof verdicts.


Validation (2026-10-04, `feature/normalization-inlay`, master base `593211d`): JDK 21 `spotlessApply check buildPlugin verifyPlugin verifyPluginStructure verifyPluginProjectConfiguration` passed against Bend 2.0.35 `79df8d9c40722ee9507a1e253f283b51025f9d6c` and Bun 1.4.2. The main suite reported 584 cases: 583 passed, no failures/errors, one optional current-release smoke skipped. Both architecture tests and all six new inlay fixtures passed. Plugin Verifier accepted IC 2025.1 and IU 2026.1, retaining API notices.

Implementer self-review under `REVIEWER.md`: reviewed this slice against `593211d`, traced action registration through the shared inquiry, current snapshot/caret guard and root check status, and inspected editor/document/project cleanup. A1–A4 preserve Scala ownership and existing API dependencies; A5–A7 preserve exact compiler facts, bounded processes, freshness and visible reliance; A8–A9 add no indexing or source mutation. Native fixtures exercise real normalization, pin/unpin, caret movement, immediate edit invalidation, configuration invalidation, in-flight cancellation and editor release. Existing normalization/backend tests remain in the passing full suite. No actionable findings; this is self-review, not independent review. Limits remain one pin per editor, explicitly requested closed-expression normalization and a shortened display for long values. No release or publication is included.


## Canonical loading, cached Hub and source type navigation

Motivation: [Rust LSP analysis](https://github.com/IlyaGulya/bend2-lsp-rs/blob/1e75117a8d5be38d648f65f25d854c894cffbafd/src/analysis.rs), [native-query benchmark distinctions](https://github.com/IlyaGulya/bend2-lsp-rs/blob/1e75117a8d5be38d648f65f25d854c894cffbafd/benches/analysis.rs), and [pending-dependency protocol scenarios](https://github.com/IlyaGulya/bend2-lsp-rs/blob/1e75117a8d5be38d648f65f25d854c894cffbafd/tests/lsp_protocol.rs), all at the recorded Apache-2.0 revision. The actual namespace and package-name expectations come from real [Bend 2.0.35](https://github.com/bendlang/bend/blob/79df8d9c40722ee9507a1e253f283b51025f9d6c/bend2/bend.ts) subprocesses and our native source/current-buffer contracts.

Classification: original Scala changes in `workspace/api`, `workspace/loading`, `workspace/model`, the existing snapshot/capture/check adapters, import completion and file move consumers; new `syntax.psi.BendDeclaredTypeHeads`, `symbols.api.BendSourceTypeTargets` and `features.navigation.BendTypeDeclarationProvider`. Original fixtures live in the corresponding compiler/adapter/workspace/navigation/completion suites, plus `performance/BendSourceQueryProfileTest`; the opt-in Gradle switch is test infrastructure. The independent implementation uses existing lexer, PSI, resolver and IntelliJ extension APIs. No upstream scanner, function, fixture body, documentation string or benchmark input was copied or substantially adapted. No new upstream notice payload or Rust runtime dependency enters plugin or formatter artifacts. Preserved reference checkouts retain their licenses and remain untouched.

Evidence, limits and baseline/master overlap are recorded in [implementation evidence](../research/bend2-lsp-rs-implementation.md). This slice is based on master `aa206dc`, including the 0.1.17 compiler/protocol/normalized-value changes; it leaves published version notes intact and records its plugin behavior under Unreleased.

## Upstream diagnostics and lint ideas — 2026-10-14

Classification: original Scala implementation, helper protocol extension, descriptions and minimal fixtures. No upstream functions, rule bodies, grammar, fixture text or documentation descriptions were copied or substantially adapted. Motivation is pinned to [Rust LSP v0.6.1](https://github.com/IlyaGulya/bend2-lsp-rs/tree/9116cbee485b1de1512731696437762da60ced1f), [subsequent query allocation work](https://github.com/IlyaGulya/bend2-lsp-rs/commit/217a2e1c6d7830a1e394173a51ba689fadbbafb6), and [bend-lint's rule catalogue](https://github.com/MattCozendey/bend-lint/tree/02ef84b38b8c06cbd607e270c4930e633e9dab0f/bend-lint-rules). Their Apache-2.0 and MIT licenses remain with research checkouts; this slice distributes no expressive upstream material and introduces no upstream notice payload.

Original production destinations under `src/main/scala/com/dearlordylord/bend/idea`:

- `analysis/checking/BendImportDiagnostics.scala`, related-location/result additions to `analysis/model/BendCheckResult.scala`, bulk edge validation in `workspace/model/BendLoadedGraph.scala`, and the `adapters/intellij/BendCheckSession.scala` hook.
- `features/checking/BendNavigateDiagnosticCause.scala` and checking annotator integration.
- `adapters/structured/BendStructuredCheck.scala`, CLI negotiation plumbing and `src/main/resources/semantic/bend-structured-helper.ts` coordinate changes.
- `features/inspections/BendSourceInspections.scala`, `symbols/api/BendCanonicalImportPaths.scala`, `BendUnusedImportAliases.scala`, the narrow token-based `syntax/parser/BendSourceDelimiters.scala` absence-hint prerequisite, and native extension registrations.
- `symbols/api/BendSnapshotAwareReference.scala`, `symbols/references/BendReferenceContributor.scala` and `BendUsageSearch.scala` request-local batching.

Original fixtures are the matching structured check, graph check, import projection, native checking/law-range and source-inspection tests; existing usage/navigation/rename fixtures remain the behavior oracle for batching. The test launcher explicitly suppresses compiler telemetry/update notices for deterministic offline checks. Expected compiler judgments come from the unchanged pin in `ci/bend-test-toolchain.properties`; source behavior comes from shared native owners and observable IntelliJ actions. Performance observations retain their bounded workload and raw data under `research/usages-batching-2026-10-14/`; links to upstream performance work are motivation, not local timing evidence.

Production classes/resources enter the plugin. Test sources and research records enter only source material; no upstream-derived notice addition is required in either plugin or standalone formatter archives for this original work. Validation results and independent Astra review outcomes are recorded in the implementation report rather than inferred from these links.

The three native inspection descriptions in `src/main/resources/inspectionDescriptions/` are original product help text and enter the plugin with the registrations above.

## #75: restricted compiler recovery

`src/main/resources/semantic/bend-structured-helper.ts` is an **adaptation** of
orchestration hooks and reconstruction/guard ideas from
[IlyaGulya/bend2-lsp-rs](https://github.com/IlyaGulya/bend2-lsp-rs/tree/217a2e1c6d7830a1e394173a51ba689fadbbafb6),
`src/server/compiler_backend/patch_plan.js` and `recovery_runtime.js`, and native
validator/loader anchors from
[Bend 2.0.35](https://github.com/bendlang/bend/blob/79df8d9c40722ee9507a1e253f283b51025f9d6c/bend2/bend.ts).
Both revisions use Apache-2.0 with `Copyright 2026 HigherOrderCO` in LICENSE;
no root NOTICE or file-specific notices were present in the inspected paths.
The packaged `META-INF/bend-recovery-LICENSE.txt` and
`META-INF/bend-recovery-NOTICES.txt` retain terms, notices, revisions, destination
and modifications. The adaptation uses in-memory source hooks and the existing
check transport, with frozen loading, no parser recovery and a smaller,
conservative supported scope. Compiler rules themselves are not redistributed.

The graph and native editor fixtures are original examples; expected judgments
come from the pinned real compiler. The source distribution and plugin JAR/ZIP
contain the helper and its notices. The formatter JAR/native distribution does
not select this resource or compiler helper. `scripts/verify-recovery-notices.py <plugin.jar> <plugin.zip>` verifies
both plugin JAR and ZIP for byte-identical notices; the helper carries no new formatter
payload.

## Bend 2.0.37 compiler pairing

The helper adaptation above now targets [Bend 2.0.37](https://github.com/bendlang/bend/tree/4ff8545f19b09b7d2b8ca2303668100eac8efd66), `bend2/bend.ts` and `bend2/main.ts`. Native validation/lowering/literal expansion boundaries remain unchanged; the reliance walk now includes datatype parameter types and avoids recursive object traversal. The same Apache-2.0 copyright/license applies, with no upstream NOTICE. Updated immutable provenance is retained in the packaged recovery notices. Historical validation records above remain tied to their original pins.


## Bend 2.0.40 compiler pairing — 2026-11-06

The existing adapted `semantic/bend-structured-helper.ts` now targets
[Bend 2.0.40](https://github.com/bendlang/bend/tree/624dd9f4607cd29b0871a9b96d413bfb11e92f52),
`bend2/bend.ts`, `bend2/comp.ts` and `bend2/main.ts`. All source hashes were
recomputed from that clean tag. The native validator/loader anchors and private
semantic APIs used by the helper retain their previous shapes; recovery rules,
limits and first-error fallback are unchanged. The source pairing and protocol
identity were updated without copying new compiler or LSP implementation. The original Scala CLI
verdict decoder now recognizes one final nonempty `Note: ...` after a valid
numbered excerpt; source attribution still requires the unique unchanged captured
compiler/copy/original line. Unknown footers, misplaced or repeated notes and
empty notes keep the conservative root-only location. Decoder tests and native
current-file/background checking fixtures cover this boundary.
Apache-2.0 and `Copyright 2026 HigherOrderCO` remain applicable; no root NOTICE
or additional file-specific license notice is present in these inspected paths.
The packaged recovery notices retain both historical adaptations and this pin.
The formatter still excludes the helper.

The new `BendCliGraphCheckTest.changedCompilerSourcesKeepNativeCheckingWithoutRecovery`
is an original fixture: two independent errors are reported for the supported
source copy; changing only a compiler comment disables hash-pinned recovery while
ordinary native CLI checking still reports the original first error. Existing
real compiler and IntelliJ semantic fixtures remain the API/behavior oracles.
Bend 2.0.40 changes non-exhaustive-match wording to `this match doesn't cover`;
the graph/current-file/background fixture expectations were updated from the
actual native output, without changing the missing-case behavior or recovery
policy. The first corrected three-case rerun exposed loss of source attribution
in the text CLI when the new trailing note was rejected by the old decoder;
the root cause was fixed at the existing decoder boundary, rather than weakening
the editor highlight assertions. Direct real subprocess probes covered two independent errors, first-hole goals,
compiler binder comparisons, expression types, closed normalization, TODOs and
safe/unsafe verdicts. Their exact command and raw output are retained locally at
`/tmp/bend-helper-2040-probe.py` and `/tmp/bend-helper-2040-probe.jsonl`; these
probes supplement, rather than replace, the Gradle integration gates.

Implementer self-review under `REVIEWER.md`: inspected this upgrade against
`9e14236`, traced the helper consumers through `RealBendCompilerFixture`,
`BendCliCheckBackend`, structured decoders and semantic actions, and compared the
native validator/check-only/reliance boundaries to the new source. A2 and A5–A7
remain unchanged: no feature depends directly on the compiler, unsupported hashes
retain guarded CLI checking, and successful checks preserve completeness and
reliance. Reference and compiler checkouts were not modified. This is self-review,
not independent review. Full Gradle and archive verification results must be
recorded after the integrated run; they are not implied by the direct probes.

Focused upgrade validation used full JDK 21 and the exact Bend/Bun pin with
`spotlessApply test --tests '*BendCliGraphCheckTest' --tests '*BendStructured*Test'
--tests '*BendInspectGoalActionTest' --tests '*BendNormalizeExpressionActionTest'
--tests '*BendNormalizationInlayTest' --tests '*BendTryReflexivityActionTest'`.
The first run executed 64 cases: 63 passed, one failed solely on the old missing-case
message expectation; no cases were skipped. The new hash-mismatch/native-fallback
fixture and the semantic native fixtures passed. The original failed log remains
at `/tmp/bend-helper-2040-gradle.log`. The first three-case rerun
(`/tmp/bend-helper-2040-wording-rerun.log`) passed recovery but failed the two
native highlight cases because of the trailing-note attribution regression
described above. After the decoder fix, full JDK 21 `spotlessApply test` with
`BendCliVerdictDecoderTest`, `BendCliDiagnosticAttributionTest`, the poisoned-
dependent recovery method and both non-exhaustive-match native methods passed
**15 cases, zero failures/errors/skips**. The final log is
`/tmp/bend-helper-2040-notes-rerun.log`; unchanged semantic-operation fixtures
retain their passing results from the earlier run. Full integrated `check`,
packaging, verifier and archive-notice gates remain required and are not claimed
by these focused results.

A separately delegated reviewer inspected the helper and expanded decoder diff,
actual compiler sources, raw probe outputs and both Gradle result sets. Their
final review found no actionable finding under A2/A5–A7 and #74/#75. This is
independent source review; integrated full-suite and distribution evidence remain
separate gates.

## Managed optional lint — 2026-11-06

Original Scala integration updates the repository's own `prototype/bend-lint` slice
for standalone bend-lint 0.3.0, source revision
[`c6f31c0`](https://github.com/MattCozendey/bend-lint/tree/c6f31c0c2ef4587368dd6a5443a6d42e22e975fc).
The [Rust LSP 0.7.0 release](https://github.com/IlyaGulya/bend2-lsp-rs/releases/tag/v0.7.0)
motivates unsaved-dependency lint and explicit tool setup. No upstream LSP integration,
rule implementation, diagnostic strings, tests or grammar were copied into the plugin.
The protocol model/decoder, native settings/actions/annotations, installer and offline
preload are authored here. New source programs and expected native ranges are original.

Tool source is fetched only by explicit installation, not bundled in plugin/formatter
archives. Installation retains the npm package's MIT LICENSE and verifies immutable
package/compiler/Hub/build inputs. The linter's own compiler pairing is separate from
the project's compiler and canonical proof verdict. Existing recovery-helper licensing
is unaffected. Plugin payload adds original integration classes, the offline guard and
immutable tool identity metadata; the standalone formatter selects none of these owners.

Validation and limitations are recorded in the implementation report after the actual
checks, rather than inferred from upstream release notes. This provenance entry does
not claim independent review or that the old prototype's checks validate this pairing.

## Integrated adoption verification — 2026-11-06

The four slices at `328b3fb` passed full JDK 21 `check buildPlugin verifyPlugin
verifyPluginStructure verifyPluginProjectConfiguration`: 693 main tests, zero
failures/errors, two optional skips, and two passing architecture tests. Actual
managed installation was separately exercised, including automatic pinned Bun
download. Both configured IDE targets are compatible with API notices retained.
The built JAR and ZIP retain byte-identical recovery helper/notices; original lint
resources match source, and the formatter has no lint payload. Earlier pending
statements above describe the focused slice checkpoints, superseded by this
integrated evidence. See [the final report](../research/upstream-adoption-2026-11-06.md)
for archive identity, self-review, independent review and limitations.

## Lint project-compiler version restriction correction — 2026-11-06

Original adapter correction removes the entire configured-compiler version probe
and its 2.0.37/2.0.40 allowlist. Managed lint owns its verified Bend 2.0.39;
the selected project compiler continues to determine canonical checking outcomes.
Lint now reports actual execution/protocol outcomes rather than rejecting the
project's version banner. This introduces no new upstream payload or notice change.

A new regression exercises a real clean Bend 2.0.39 source checkout
`a3efe8a3801f423827424227172eed9802c5e6cb`, its matching Base and unsaved
CRLF/astral root/import texts, preserving exact diagnostic ranges and disk contents.
Another real-engine fixture makes the configured compiler fail if invoked, so
reintroducing any project-version probe fails independently of version spelling.
The original gate produced the expected Completed-versus-Unavailable failure.

The first focused retry passed the real 2.0.39 graph scenario and five other cases,
but its direct positive control found that relocating a prepared installation can
change runtime-generated files. The tests now prepare isolated generations at their
final paths through the real installer and require a successful unchanged baseline
before each deliberate process-boundary mutation. Production installation/freshness
validation was not relaxed. An unused-import compile error during this fixture
correction was fixed. Final focused and exact-candidate release gates are recorded
in the release evidence after their actual completion.
