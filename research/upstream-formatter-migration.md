# Reusing the upstream Bend 2 formatter

Assessed 2026-10-03 at Bend IDEA `7864bf9bd859865c92d8629563555eea965899bc`.
User preference: use the canonical upstream formatter inside both our CLI and
IntelliJ integration if feasible, rather than maintaining independent rules.

## Subsequent maintainer decision

On 2026-10-03 the maintainer chose to extend the existing shared formatter for
long equality propositions before pursuing an upstream migration. Version
0.1.15 adds bounded equality/inequality wrapping; the upstream investigation
and patch below remain migration evidence, rather than an implemented engine
replacement.

## Decision

Reuse is technically feasible and is the recommended direction. EditorConfig,
CLI `check`/`fix`, packaging and IntelliJ integration are adapters, not reasons
to maintain a second formatting engine. **Do not replace the engine with the
current upstream revision yet:** real compiler tests below demonstrate two
valid-to-invalid transformations. Correct upstream layout handling and test it
before switching. This investigation changes no plugin or CLI behavior.

The inspected canonical repository revision is
`a3f1782a11ad5ee8778d26438f254bf3873bf0f1`; its latest formatter change is
`f48a8365de3dc9d875c3717e8a77395c1b3e6cd5`.
[Formatter history](https://github.com/bendlang/bend/commits/a3f1782a11ad5ee8778d26438f254bf3873bf0f1/tools/bend-fmt-lsp).

## What can be reused

The exported `formatBend(source, {tabSize?, insertSpaces?})` function has no
runtime imports. A small JavaScript wrapper can call it directly; no LSP client
is needed for a command-line formatter. The package ships compiled
`dist/formatter.js`, although it does not declare a stable package export.
Pin an exact source revision/build and retain its Apache-2.0 notices rather
than depending on an undocumented moving import path.
[Engine](https://github.com/bendlang/bend/blob/a3f1782a11ad5ee8778d26438f254bf3873bf0f1/tools/bend-fmt-lsp/src/formatter.ts),
[package](https://github.com/bendlang/bend/blob/a3f1782a11ad5ee8778d26438f254bf3873bf0f1/tools/bend-fmt-lsp/package.json).

The LSP server offers full-document formatting over stdio with a whole-file
replacement edit. Its package requires Node 22+. It provides neither
EditorConfig lookup nor CLI check/fix, wrapping, range or on-type formatting.
[Server](https://github.com/bendlang/bend/blob/a3f1782a11ad5ee8778d26438f254bf3873bf0f1/tools/bend-fmt-lsp/src/server.ts),
[README](https://github.com/bendlang/bend/blob/a3f1782a11ad5ee8778d26438f254bf3873bf0f1/tools/bend-fmt-lsp/README.md).

| Current behavior | Treatment when migrating |
|---|---|
| File discovery/arguments, UTF-8, check/fix statuses, stale-file guard | Retain the CLI adapter; change its engine call. |
| Local EditorConfig and IDE effective indentation settings | Retain settings resolution; map representable values to upstream options. |
| Independent `indent_size` and `tab_width`, tabs plus remaining spaces | Extend upstream options; the current two-option API cannot express all combinations. |
| Long-line wrapping and `bend_max_line_length` | Add to upstream or explicitly retire the feature; silently ignoring it is a regression. |
| Partial selection formatting | Add a safe upstream edit/range interface or initially make selection formatting unavailable. Filtering whole-file edits requires evidence that dependencies on surrounding layout remain safe. |
| Enter/Backspace | Retain native typing policy; full-document formatting is not a typing handler. |
| Undo, stale-buffer protection, save/commit integration | Retain IntelliJ integration around the external engine. |
| Bundled-runtime offline CLI | Repackage with an appropriate JS runtime or deliberately change installation requirements. Existing Java archives cannot directly execute this JS engine. |

These current contracts are implemented in
[BendFormatCli](../src/main/scala/com/dearlordylord/bend/idea/adapters/cli/BendFormatCli.scala),
[BendLayoutPolicy](../src/main/scala/com/dearlordylord/bend/idea/syntax/parser/BendLayoutPolicy.scala),
[format processor](../src/main/scala/com/dearlordylord/bend/idea/features/formatting/BendFormattingProcessor.scala),
and described in the [user guide](../docs/bend-format.md).

Our engine is not universally more capable: upstream normalizes nested block
indentation and ordinary operator spacing; ours chiefly adjusts comma spacing,
one simple body indent, and recognized wrapping. In the nested-match experiment
upstream converted 4/8/12 spaces to 2/4/6 while ours preserved the input. Both
results checked. A file containing a valid multiline string made upstream
return the whole input unchanged; ours could still format another declaration.
These are observed differences, not migration blockers by themselves.

## Proven blockers: physical alignment

Both sources below pass actual `--check-only`. Upstream returns edits that
make them fail parsing. Our packaged formatter preserves them.

```bend
import Base
def f(n: Bool) -> U32:
  match n: case True{}: 0
           case False{}: 1
```

Upstream replaces the eleven spaces before the second `case` with four.
The first case remains inline. The compiler then reports:
`expected 'def', 'type' or 'law'; observed 'c'`.

```bend
import Base
def main() -> IO(Unit):
  do IO<Unit>: IO.print("a")
               IO.print("b")
```

Upstream replaces the fifteen spaces before the second statement with four.
The compiler then reports the same expected declaration tokens, observed `I`.
Only checking was used; these I/O statements were never executed.

The formatter reconstructs relative indentation depths and compares token
spellings plus those depths. That fingerprint does not establish preservation
of physical columns or adjacency. Bend's parser uses physical columns for
case rows and statement continuations. It also counts a tab as one source
character, whereas upstream's indentation reconstruction expands it to eight
visual columns. The tab discrepancy is a **risk found by inspection**, not a
separately demonstrated failure in these experiments.
[Current compiler parser](https://github.com/bendlang/bend/blob/a3f1782a11ad5ee8778d26438f254bf3873bf0f1/bend2/bend.ts).

For reproduction, place either source in `before.bend`, generate `after.bend`
using the compiled engine, then run the same compiler on each:

```js
import { formatBend } from './tools/bend-fmt-lsp/dist/formatter.js';
import { readFileSync, writeFileSync } from 'node:fs';
writeFileSync('after.bend', formatBend(readFileSync('before.bend', 'utf8')));
```

```sh
bend before.bend --check-only  # exits 0
bend after.bend --check-only   # exits 1
```

## Executed evidence and limits

- Built our current CLI with full JDK 21: `./gradlew buildFormatTool` passed.
- In an isolated upstream checkout, `npm ci --ignore-scripts && npm test`
  passed **13/13** on Node 25.6.1: twelve formatter cases and one stdio LSP
  session. Those tests do not invoke the real compiler.
  [Formatter tests](https://github.com/bendlang/bend/blob/a3f1782a11ad5ee8778d26438f254bf3873bf0f1/tools/bend-fmt-lsp/src/test/formatter.test.ts),
  [server test](https://github.com/bendlang/bend/blob/a3f1782a11ad5ee8778d26438f254bf3873bf0f1/tools/bend-fmt-lsp/src/test/server.test.ts).
- Compared **13 targeted sources**, each before formatting, after upstream,
  and after our CLI. Used actual pinned Bend
  `ff7a40cc9070a34c78399ecd2bbe46a044ad9b4b` and pinned Bun
  `1.4.2+744846f84`. Twelve inputs checked; upstream preserved acceptance for
  ten and broke the two examples above. Our output retained acceptance for
  all twelve. The incomplete thirteenth input remained invalid in all three
  variants; ours reported unavailable, upstream changed comma spacing.
- Cases covered simple/nested bodies, comments, continued result arrow,
  multiline signature/string, significant newline before grouping, mixed
  line endings, mixed tabs/spaces, natural successor syntax, incomplete
  syntax and both inline layout failures. Direct byte inspection showed
  upstream canonicalized mixed LF/CRLF to CRLF; ours retained the bytes in
  that case. No assertion of universal line-ending preservation is made.
- Repeated both failures against **current upstream compiler**
  `a3f1782a11ad5ee8778d26438f254bf3873bf0f1`, with Bun 1.4.2: originals
  exit 0 (`ALL PROOFS CHECK`), upstream outputs exit 1 (`SOME PROOFS FAIL`).
- Exploratory sources/results are under `/tmp/bend-formatter-comparison/`;
  this note embeds the blockers so they remain reproducible after cleanup.
  Passing checks establish acceptance on these examples, not equivalence of
  all typed programs. Full plugin `check`, packaging/verifier and a migrated
  IDE fixture were not run; no migration was implemented.

## Integration and recommended work

Use one pinned canonical engine for IDE and CLI. Prefer a small process bridge
over writing a Scala translation with another set of rules. The plugin stays
Scala; JS is the external formatter. IntelliJ provides
`AsyncDocumentFormattingService` for external formatters. Built-in JetBrains
LSP integration excludes IDEA open-source builds, so it should not become a
mandatory dependency merely to call this formatter.
[External formatter SDK](https://plugins.jetbrains.com/docs/intellij/code-formatting.html#external-code-formatter),
[LSP supported IDEs](https://plugins.jetbrains.com/docs/intellij/language-server-protocol.html#supported-ides).

1. Fix physical-alignment preservation in upstream and add the two real
   compiler regressions. Conservative unchanged/unavailable outcomes for
   unsupported layouts are acceptable; a copied independent formatter is not
   the intended fix. Establish explicit failure status too: upstream currently
   conflates unsafe/no-op and conforming, which cannot honestly drive our
   CLI's `conforming`/`unavailable` distinction.
2. Resolve upstream options for indentation/tab widths and the wrapping
   compatibility decision. Keep EditorConfig/check/fix outside the engine.
3. Integrate through bounded process execution and explicit unavailable
   results, with cancellation and stale-buffer rejection. Update the shared
   ownership contract and compiled dependency checks with implementation.
4. Run real editor undo/selection/settings fixtures and pinned compiler
   comparisons through both clients, then the required repository gates.
   Retire duplicate formatting rules after that evidence passes.

Architecture self-review: an immediate substitution violates **A9** source
layout preservation for the concrete sources above. A conforming migration
can preserve **A2/A3** one owner/shared consumers and **A4** effects outside
pure policy. This research does not change those boundaries. The earlier
[workflow investigation](bend-editorconfig-formatting-workflows.md) already
identified upstream and recommended evaluation; it did not execute it. That
was an unclosed evaluation gap, not evidence that reuse was impossible.

## Prepared upstream fix (2026-10-03)

A local branch `fix/formatter-inline-layout` is available in the independent
checkout `/Users/firfi/work/formal-proofs/bend-formatter-upstream-fix`.
The portable [patch](upstream-formatter-inline-layout.patch) applies cleanly to
the inspected upstream base. At the user's subsequent request, the branch was
pushed to their newly created fork and opened as
[draft PR #1](https://github.com/dearlordylord/bend/pull/1), targeting that
fork's `main`. Astra reviewed the concise title/body against the diff;
no PR was opened against `bendlang/bend`.

The fix conservatively returns the original document for inline `case` rows
and unrecognized `do` headers. Only the complete single-line shape
`do NAME<NAME>:` is recognized, with the first statement on the next line.
Both horizontal spacing and indentation
can move a continuation's anchor, so skipping only indentation would not be
sufficient. This guard deliberately does not parse Bend: it declines complex
`do` headers, including complete valid ones. It does not solve general
formatting, tab interpretation, wrapping or the result-status API.

New regressions failed on the original code (including the real compiler
failure), then all **17 tests passed, zero skipped**, after the fix. Tests
cover unchanged inline layouts across indentation settings and LF/CRLF,
comment/literal exclusions, continued support for ordinary multiline blocks,
real `--check-only` for both counterexamples, and no edits over LSP after a
document change. Run from `tools/bend-fmt-lsp` with Node 25.6.1 and pinned
Bun 1.4.2, setting the documented `BEND_FMT_TEST_BUN` and
`BEND_FMT_TEST_COMPILER` paths. `git diff --check` and patch application checks
passed. The trusted compiler source and reference checkouts were not edited.

Self-review found no violation of the source-preservation requirement for the
reported cases; unchanged output is the intended conservative fallback. Full
upstream mini-cluster gates and repository token-cap gate were not run. This
is a prepared upstream fix, not evidence that the migration's other contracts
are now implemented.

## Astra medium review and related upstream work

Astra independently reviewed the code and compiler rules on medium effort.
It found a P1 gap in the original draft: a multiline `do` header bypassed the
line-local colon guard, allowing valid source to become invalid. A preliminary
"ends in a colon" refinement also failed when the final colon belonged to an
internal binder (`do IO<@x:`). Both failures were reproduced with real Bend.
The exact simple-header allowlist above addresses these witnesses. The final
Astra review found no further actionable findings in the revised diff; the
implementer reran all **17 tests, zero skipped**, with **six** real compiler
fixtures. The follow-up commit is `bfc490b5` in the existing draft PR.

The review recommends retaining document-wide no-op as a bounded safety fix.
Freezing one line is insufficient: spacing before an inline anchor and edits
to its ancestors/continuations can alter the column comparisons. More useful
active formatting should be a separate change: retain physical token offsets
and identify block/statement anchors, then plan spacing and indentation
together, preserving exact `do` continuation equality and `case` sibling and
parent comparisons. Return unchanged when structural recognition is uncertain.
Exercise nested blocks, multiline types, tabs and semicolon boundaries with
the actual compiler; do not infer preservation from the depth fingerprint.

Searched upstream open/closed issues and PRs for formatter, formatting,
`bend-fmt-lsp`, indentation, inline match and format-on-save on 2026-10-03.
No exact duplicate of this layout fix was found in those searches. Related:

- Open [PR #1296](https://github.com/bendlang/bend/pull/1296) fixes unsafe
  declaration suffix spacing: `def value?()` was formatted as `def value ? ()`.
  It adds spacing rules and regressions; it does not address continuation
  columns. Keep it separate, but track it before adopting the engine.
- Merged [PR #1264](https://github.com/bendlang/bend/pull/1264) combines
  formatter scanning and expands literal tests. It is already in our base.
- Merged [PR #1289](https://github.com/bendlang/bend/pull/1289), closing
  [issue #1126](https://github.com/bendlang/bend/issues/1126), corrects parser
  handling of `Array.set(..);` inside an inline match. It preserves following
  rows at the parser level; it does not fix formatting. Already in our base.

Local checkout to open in IntelliJ:
`/Users/firfi/work/formal-proofs/bend-formatter-upstream-fix`.
It has branch `fix/formatter-inline-layout` and remote `fork` pointing to
`dearlordylord/bend`.
