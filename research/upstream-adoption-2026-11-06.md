# Upstream adoption — 2026-11-06

Implementation follows the four recommendations in [the comparison](competitor-updates-2026-11-06.md), authorized together by the user. Starting plugin commit: `9e14236`. Existing untracked `.slopmemr-id` is unrelated and preserved.

## Delivery and acceptance

| Unit | Acceptance evidence required | Owner and boundaries |
| --- | --- | --- |
| Managed optional bend-lint | Pinned installation with integrity/cancellation/rollback; real lint on captured unsaved sources and dependencies; explicit partial/unavailable outcomes; lint cannot replace compiler verdicts | `toolchain.api`, compiler/process/IntelliJ adapters, checking/settings features; issue #89 and A1–A7 |
| Bend 2.0.40 helper | Exact clean compiler/Bun inputs; real check, goal, type, normalization, reliance and restricted recovery tests; unsupported sources retain fallback | Existing structured helper and compiler adapters; issues #74/#75, A5–A7 |
| Editor regression audit | Existing coverage mapped before additions; actual compiler and native editor witnesses for demonstrated gaps; shared owner fixes preserve undo/bindings/layout | Existing syntax/symbol/completion/formatting owners; specification #1 P3/P4/P6/P8, A3/A8/A9 |
| Proof-cache experiment | Reproducible independent harness; cold/warm and edited-source output/exit comparisons; root/fill/reliance cases; raw data and honest timing limits | Research only; no persistent production cache or trusted core edits; A3/A5–A7 |

Worker branches use separate worktrees. Gradle runs are serialized because compiler/editor fixtures share constrained host resources. Original reference checkouts and the protected compiler core are not implementation destinations. New upstream-derived payloads must carry the notices required by [the reuse policy](../docs/reuse-policy.md).

## Validation

The full JDK 21 integrated `check` at `328b3fb` passed: 693 main cases, 691 passed, zero failures/errors and two optional skips; two architecture cases passed. The skips are installed-current-release smoke and the explicit network installation scenario. Actual managed installation, including default Bun download, was verified separately as documented below. `buildPlugin verifyPlugin verifyPluginStructure verifyPluginProjectConfiguration` also passed. Plugin Verifier reports compatible for IC 2025.1 and IU 2026.1, with API deprecation/experimental/internal notices retained in its reports. Individual worker probes do not substitute for these integrated gates.

The [proof-cache experiment](proof-cache-2026-11-06/README.md), integrated in
`a2aa522`, completed 36 ordinary exact-output/exit comparisons with no mismatch.
Its separate deliberate cache-tampering probe produced a false cached success;
this establishes a cache-trust boundary, not a normal invalidation mismatch or
a compiler defect. Timing remains inconclusive under shared host load. No
production cache was introduced.

The [editor audit](editor-regression-audit-2026-11-06.md), integrated in
`1f040de`, adds nine behavioral witnesses and fixes ordinary completion suffix
replacement, raw apostrophe character recognition in the lexer/layout scanner,
and dependent-arrow binder leakage into a sibling case. The focused JDK 21 run
passed 155 tests and two architecture cases with no failures or skips, using
Bend 2.0.37 and Bun 1.4.2. The final integrated run additionally passed these changes with the upgraded pin.

The helper slice, integrated in `fac0842`, updates the reproducible compiler pin
and optional source-hash pairing to Bend 2.0.40 with Bun 1.4.2. Real probes and
fixtures cover check outcomes, reliance, recovery, goals, comparison, types and
normalization. The first 64-case run had one outdated compiler-message assertion;
the subsequent native cases also exposed a genuine location regression caused
by the compiler's new final `Note:` line. The existing CLI decoder now accepts
that bounded suffix after a valid excerpt while rejecting malformed tails. The
final 15-case targeted run passed with no skips. The final integrated check and JAR/ZIP notice verification passed; both configured Plugin Verifier targets passed.

The [lint implementation record](lint-integration-validation-2026-11-06.md),
integrated in `ea6513c` and `d7b03a1`, covers explicit verified installation,
repair/cancellation/rollback, read-only root/dependency diagnostics, native
annotations and rule documentation, separate partial/unavailable status, and
opt-in background scheduling. Lint 0.3.0 uses its own pinned Bend 2.0.39 and Bun
1.4.4; it does not replace the selected compiler's outcome. Custom project rules
and automatic source fixes are outside this slice. The final focused run passed
19 cases and two architecture tests without failures or skips. Earlier failures
exposed actual runtime inventory writes and an invalid absolute-entry invocation;
the report retains their corrections rather than treating them as passing runs.

The first combined run against `d7b03a1` completed 693 main cases: 690 passed,
one failed and two optional scenarios skipped. Its single failure was an old
file-move assertion expecting the pre-2.0.40 foreign-reliance row. Direct real
compiler checks before and after the move produced identical exit status and
the new grouped row; `328b3fb` updates that expectation without changing
production code. The failed log is retained at
`/tmp/bend-upstream-final-check-first.log`.

Final combined verification uses `328b3fb` in a fresh worktree,
with the exact 2.0.40/1.4.2 compiler inputs and the real prepared 0.3.0/1.4.4 lint
installation. This clean sandbox preserves existing IDE/test state in the user's
checkout. The command includes all required check, package, structure/configuration
and Plugin Verifier gates. Its local log is `/tmp/bend-upstream-final-check.log`.

## Review

Implementer self-review under [REVIEWER.md](../REVIEWER.md) traced the changed shared syntax, scope and completion consumers; compiler/helper and bounded process consumers; and lint settings, capture, publication and diagnostics. The three passes found no remaining actionable behavior or A1–A9 violation. The new tool-management API supports current settings and later explicit repair without changing proof verdict policy. No architecture enforcement rule was relaxed. The integrated evidence above completes the required gates. Independent review is identified separately below with scope and evidence. The decision trail is [.audit/upstream-20261106.tsv](../.audit/upstream-20261106.tsv).

Independent Astra review of the cache experiment (`a2aa522` against `9e14236`)
found no actionable issue under A3/A5–A7 and specification #1 P5/P11. The reviewer
verified all 74 raw observation records, hashes and classifications, fixture
transitions, compiler source hashes and the reported timing aggregates. The
reviewer did not rerun the experiment or Gradle. The available transcript tool
returned truncated history, so this does not establish a complete audit of the
conversation trail.

The reviewer subsequently read the exact root rollout JSONL and resolved that
interim transcript limitation. It confirmed the four-part authorization,
worktree separation, preparation reads, retained failed checks and integration
decisions against the trail. Final lint and integrated gate evidence is recorded in this report; the concluding trail audit is recorded below.

Independent Astra review of the editor slice found no actionable issue under
A3/A8/A9 and specification #1 P3/P4/P6/P8. The reviewer traced the affected source
and native consumers and inspected both expected failing baselines and the
passing 155-case XML plus architecture log. This review did not rerun Gradle.

Independent Astra review of the helper and its later decoder correction found
no actionable issue under A2/A5–A7 and issues #74/#75. It verified source hashes,
recovery boundaries, fallback behavior, notices and the actual focused results.

Independent Astra lint review found and rechecked fixes for installation
validation/publication races, untracked executable additions, normal generated-JS
mtime touches, Bun transpiler-cache writes and relative entry configuration.
It inspected the final positive real fixtures and their 19-case/two-architecture
results and found no remaining actionable source issue under #89 and A1–A8.
The reviewer did not run Gradle; integrated 2.0.40 evidence is separately recorded above.

The built candidate ZIP is `bend-idea-0.1.26.zip`, SHA-256
`d9e520ea1cd879ff245eec8b1d7603ef7d30099d7d27dcf5a355e511220dc5dd`.
`verify-recovery-notices.py` verified byte-identical helper and required notices
in both plugin JAR and ZIP. Original lint guard/pins match the source; the plugin
JAR has no upstream engine paths and the standalone formatter has no lint classes
or resources. This is an unreleased local candidate, not a published release.

The same ZIP is available locally as
[`build/distributions/bend-idea-upstream-20261106.zip`](../build/distributions/bend-idea-upstream-20261106.zip).
Runtime execution was on Linux ARM64. Pinned archives for other hosts have not
been executed here. Current-file dotted middle-name completion parity remains
an untested coverage edge; existing imported/Base dotted-suffix behavior is preserved.

Concluding independent cross-model review by `gpt-6-astra` found no remaining
actionable source, evidence or trail finding. It independently parsed final XML,
verified required gate and compatibility logs, reran the read-only archive-notice
check, recomputed the ZIP digest and checked packaged resources. It audited the
exact root transcript against the TSV, including the failed first integrated run
and its narrow assertion correction. It did not rerun Gradle.
