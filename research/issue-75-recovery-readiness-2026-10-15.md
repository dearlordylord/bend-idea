# Issue #75: upstream recovery readiness — 2026-10-15

Historical readiness investigation. After this finding, the user authorized a
restricted private compiler-helper adaptation. Its current contract is documented
in [restricted pinned recovery](../docs/compiler/check-protocol.md#restricted-pinned-recovery-75).
The upstream capability findings below remain unchanged; the blocked-status
conclusion records the state before that authorized implementation.

## Verified upstream state

Live GitHub API queries and fresh temporary checkouts on October 15 found official Bend HEAD [`d2aeefc1902d617340e26f7fab5c1ef50fe28efa`](https://github.com/bendlang/bend/commit/d2aeefc1902d617340e26f7fab5c1ef50fe28efa), 36 commits after latest published release [v2.0.37](https://github.com/bendlang/bend/releases/tag/v2.0.37), whose tag resolves to `4ff8545f19b09b7d2b8ca2303668100eac8efd66`. The current plugin baseline remains its own pinned 2.0.35 pairing; this inspection does not upgrade that baseline. LSP HEAD remains [`217a2e1c6d7830a1e394173a51ba689fadbbafb6`](https://github.com/IlyaGulya/bend2-lsp-rs/commit/217a2e1c6d7830a1e394173a51ba689fadbbafb6), with latest release [v0.6.1](https://github.com/IlyaGulya/bend2-lsp-rs/releases/tag/v0.6.1).

## Official compiler boundary

At inspected HEAD, exported [`book_valid`](https://github.com/bendlang/bend/blob/d2aeefc1902d617340e26f7fab5c1ef50fe28efa/bend2/bend.ts#L3777-L3905) still throws the first error. Its `done` parameter assumes that the prefix has already been validated; it is not a failed-declaration skip or recovery API. Initialization hides definition bodies, then events publish them in source order. Validation mutates checked bodies, hole counts and the declaration table. Exported [`def_check` and `def_inst`](https://github.com/bendlang/bend/blob/d2aeefc1902d617340e26f7fab5c1ef50fe28efa/bend2/bend.ts#L3721-L3773) also demonstrate provisional template entries, mutable template maps and recursive checking. Higher-order term closures mean an object spread or JSON copy is not evidence of an independent fresh checker state.

The [`book_read` CLI owner](https://github.com/bendlang/bend/blob/d2aeefc1902d617340e26f7fab5c1ef50fe28efa/bend2/main.ts#L923-L948) loads once and calls this first-throw validator. A caller can use exported lower-level primitives, so lack of a CLI switch alone does **not** prove implementation impossible. However, an external caller that reconstructs the validator loop now owns its event, ADT, law/fill, foreign declaration, quantity, template, mutation and recovery compatibility semantics. No exported skip/accept/rollback operation was found in these inspected owners. This is the concrete missing capability, rather than merely missing JSON transport.

## What the LSP actually implements

The LSP has a substantive private compiler recovery implementation, not just an error parser. Its [`patch_plan.js`](https://github.com/IlyaGulya/bend2-lsp-rs/blob/217a2e1c6d7830a1e394173a51ba689fadbbafb6/src/server/compiler_backend/patch_plan.js#L1-L80) changes loader/parser provenance, parser continuation and the validator signature, adding skip sets and completion callbacks. It also replaces the runtime entry and forbids package downloads. Its [`preload.ts`](https://github.com/IlyaGulya/bend2-lsp-rs/blob/217a2e1c6d7830a1e394173a51ba689fadbbafb6/src/server/compiler_backend/preload.ts) fingerprints the embedded compiler and runtime before patch publication; the [`registry`](https://github.com/IlyaGulya/bend2-lsp-rs/blob/217a2e1c6d7830a1e394173a51ba689fadbbafb6/src/server/compiler_backend/registry.json) includes exact compiler identities and platform/runtime pairings, including 2.0.35 and 2.0.37. This supports only registered pairings, not arbitrary Bend installations.

The [`recovery runtime`](https://github.com/IlyaGulya/bend2-lsp-rs/blob/217a2e1c6d7830a1e394173a51ba689fadbbafb6/src/server/compiler_backend/recovery_runtime.js#L375-L595) blocks access to rejected declarations and constructors, computes/transitively propagates dependencies, records checked/failed/blocked coverage, and discards the entire Book after a failure. It reparses unchanged captured source and replays the surviving prefix. This avoids a partial rollback of mutable cells, checked terms, template caches, provisional instances and published law bodies. Its test [`compiler_multi_error.rs`](https://github.com/IlyaGulya/bend2-lsp-rs/blob/217a2e1c6d7830a1e394173a51ba689fadbbafb6/tests/compiler_multi_error.rs#L18-L85) asks its own patched backend for two genuine compiler errors. That is useful implementation precedent, not evidence that official Bend exports the same capability, nor a locally executed test result.

## Actual unchanged-source subprocess probes

Executed with Bun 1.4.2 (`/tmp/bend-bun-1.4.2/bun-linux-aarch64/bun`) against the plugin pin `79df8d9c40722ee9507a1e253f283b51025f9d6c` and the inspected official HEAD. Original independent probe, saved unchanged at `/tmp/issue75-probe/main.bend`:

```bend
import Base

def first() -> Nat:
  True{}

def second() -> Nat:
  False{}
```

Commands (both exit 1):

```sh
BEND_NO_TELEMETRY=1 /tmp/bend-bun-1.4.2/bun-linux-aarch64/bun /tmp/bend-idea-upstream-pin-2035/bend2/main.ts /tmp/issue75-probe/main.bend --check-only
BEND_NO_TELEMETRY=1 /tmp/bend-bun-1.4.2/bun-linux-aarch64/bun /tmp/issue75-bend-head/bend2/main.ts /tmp/issue75-probe/main.bend --check-only
```

Both produced the following output (trailing whitespace omitted):

```text
SOME PROOFS FAIL
Error:
- expected : Nat
- observed : Bool
Location: first
3 | def first() -> Nat:
4>|   True{}
  |   ^^^^^^
5 |
```

A separate direct exported-API experiment freshly loaded the **same unchanged source** for each invocation and compared `book_valid(book, 0)` with `book_valid(book, book.order.indexOf("first") + 1)`. It printed the error definition and whether `book.tlds.first.v !== null` after the throw. Save the following original probe as `/tmp/issue75-probe/done.ts`:

```typescript
const B = await import(process.argv[2]);
for (const mode of ["ordinary", "skip-first"]) {
  const book = B.book_nil();
  await B.book_load(book, "/tmp/issue75-probe/main.bend", "", new Map());
  const index = book.order.indexOf("first");
  try {
    B.book_valid(book, mode === "ordinary" ? 0 : index + 1);
    console.log(mode, "ACCEPTED");
  } catch (error) {
    console.log(mode, "firstIndex=" + index,
      "errorDefinition=" + error.def,
      "firstBodyPublished=" + (book.tlds.first.v !== null));
  }
}
```

```sh
BEND_NO_TELEMETRY=1 /tmp/bend-bun-1.4.2/bun-linux-aarch64/bun /tmp/issue75-probe/done.ts /tmp/bend-idea-upstream-pin-2035/bend2/bend.ts
BEND_NO_TELEMETRY=1 /tmp/bend-bun-1.4.2/bun-linux-aarch64/bun /tmp/issue75-probe/done.ts /tmp/issue75-bend-head/bend2/bend.ts
```

Output:

```text
# pinned 2.0.35
ordinary firstIndex=484 errorDefinition=first firstBodyPublished=false
skip-first firstIndex=484 errorDefinition=second firstBodyPublished=true
# inspected official HEAD
ordinary firstIndex=502 errorDefinition=first firstBodyPublished=false
skip-first firstIndex=502 errorDefinition=second firstBodyPublished=true
```

This executable counterexample shows the dangerous distinction: `done` exposes the later error by treating the invalid first definition as already validated and publishing its body. It therefore cannot supply trustworthy recovery semantics. No edited-source result is presented as a judgment of the original program. These probes do not execute `main`, exercise private patches, or claim validation of a recovery implementation. The release tag 2.0.37 was inspected through metadata only; its executable was not tested.

## Implementation recommendation

**Issue #75 remains incomplete and blocked on a trustworthy upstream recovery capability. No plugin behavior changed in this investigation.** The following requirements describe possible future work, not delivered acceptance criteria.

The protocol and editor list support are available, but official upstream recovery is not ready to consume unchanged. Preserve the original-source first-error fallback and the duplicate-import example's existing behavior. A narrow, original source-helper capability could be developed against a hash-pinned compiler if it establishes **all** of the following before advertising support:

- Successful parse of one immutable captured graph; no parser recovery or source rewriting in this first slice.
- Fresh full parse after every failure; no shallow Book reset or skipped `done` prefix assumption.
- Native declaration order, forward-body visibility, law/fill events, ADT/constructor validation and template semantics preserved.
- Failed declarations and their dependent declarations blocked rather than diagnosed from poisoned state; explicit deterministic coverage and a failing verdict.
- Bounded replay/work, cancellation and cleanup, closed offline loading, strict source identity, ranges and stale-result rejection.
- Real pinned compiler subprocess tests for independent errors and adversarial dependencies/mutation, plus native editor highlights. Test timeout/cap outcomes must not become successful completeness.

This is new compiler-adapter work with a recovery semantic contract, not a currently finished feature waiting for a decoder flag. If implemented, document its exact supported pairing and restricted successfully parsed scope. The invalid-import two-file case stays separate unless native parser recovery substantiates it. LSP source is Apache-2.0; copying/adapting its patch/runtime or tests requires the reuse-policy provenance and distribution notices. This report copies no expressive source or fixtures.

## Evidence and self-review

Read AGENTS.md, REVIEWER.md, CONTEXT.md, architecture A5–A7/capability rules, reuse policy and October 14/15 assessments. Inspected fresh temporary upstream source checkouts and primary GitHub metadata; protected references were untouched. The two actual compiler subprocess probes and direct exported-API counterexamples above were executed; no Gradle checks, release-tag executable tests or benchmarks were run for this research note. Author self-review: distinguishes official capability, private adaptation and possible original implementation; preserves original-source judgments and explicit partial coverage. This is not an independent implementation review.

## Authorized implementation and validation follow-up

The user-authorized restricted private-helper slice is implemented in source.
Native upstream adoption remains outstanding. The exact pinned 2.0.35 helper
now reports independent native validation errors from one unchanged captured
snapshot, with a failed verdict and the existing source identity/range mapping.
Each retry parses fresh compiler state, replays the surviving prefix, and blocks
failed declarations, their constructors and dependent declarations. Dependency
collection distinguishes constructor/declaration namespaces and includes native
literal expansion dependencies. Parser errors, unsupported templates/foreign
forms, incomplete prefixes, replay uncertainty and work caps retain the original
first error. Parser recovery is not delivered; the invalid-import case retains
its existing first-error behavior. See the current [contract](../docs/compiler/check-protocol.md#restricted-pinned-recovery-75).

Final validation used full JDK 21, Bend `79df8d9c40722ee9507a1e253f283b51025f9d6c`
and Bun 1.4.2 (`744846f84`) on Linux ARM64:

- `./gradlew spotlessApply check buildPlugin verifyPlugin` passed.
- Main test report: 660 cases, 659 passed, zero failures/errors, one optional
  installed-release smoke skipped because its separate inputs were not supplied.
  Both compiled architecture tests passed.
- Plugin Verifier accepted IC 2025.1 and IU 2026.1, with API notices retained.
- `python3 scripts/verify-recovery-notices.py build/libs/bend-idea-0.1.23.jar
  build/distributions/bend-idea-0.1.23.zip` passed: exact helper bytes and both
  license/attribution resources survived actual JAR and ZIP packaging. A synthetic
  archive without notices was rejected. These are local unreleased artifacts,
  not a new publication of version 0.1.23.

An earlier full run had one fixture-isolation failure: the new test retained a
stale result for `editor.bend`, colliding with an existing test's empty-result
precondition. Giving the new test its own root preserved all assertions; the
whole editor class then passed 19/19 before the final full run. Intermediate
runs cancelled while adding namespace/literal regressions are not passing gates.
The original first-error CLI counterexamples above remain valid upstream evidence.

Implementer self-review under REVIEWER.md found and corrected namespace
conflation; independent Astra review found and corrected frozen-loading,
rendering-fallback and implicit-literal dependency risks. Final Standards and
Astra Spec/safety reviews reported no open actionable findings. The native
compiler checkout remained clean, shared Scala APIs and freshness/process owners
were unchanged, and no release or native-upstream implementation is claimed.
