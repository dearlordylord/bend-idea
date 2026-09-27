# Expand Selection span assembly (#69)

Decision: **go**, limited to sharing the syntax scan within one selection invocation. The existing readers have distinct rules, so their token filters and tolerant parsers remain separate. The IntelliJ handler still combines and orders the returned half-open UTF-16 ranges.

## Inventory before the change

| Stage | Input work per handler call | Distinct rule retained |
| --- | --- | --- |
| Atoms | Full lexer scan | Marked atoms, dotted name components, string content and escapes |
| Expressions | Full lexer scan plus full angle-context scan | Precedence, associativity, calls and type expressions within a 4,096-unit candidate window |
| Forms | Lexer scan up to the caret line plus full line projection | Lambda and rewrite starts, multiline indentation |
| Statements | Lexer scan up to the caret line plus full line-start collection | Semicolon and indentation-sensitive local spans |
| Delimiter pairs | Full lexer and angle-context scans | Pairs outside comments/strings; only supported type angles |
| Group components | Full lexer and angle-context scans **per enclosing pair** | Top-level separators and array markers |
| Indexed write | Lexer scan up to the value and angle-context scan **per examined index prefix** | Stop at a top-level separator or rewrite boundary |
| Source applications | `prefixes`, `at`, and sometimes `atHead` each tokenize the PSI file; each also computes angle context, and `at` checks incomplete angles | Shared application facts used by other editor features |
| Folding | `ranges` and `selectionConstructs` each computed the same PSI and lexer folding ranges | Declaration and match/case/do body spans |
| Declaration and line | PSI ancestor/header/proof ranges and current line | Existing editor and proof presentation |

The handler deduplicates by `TextRange` insertion, then sorts by `(length, start)`; it does not validate a new grammar. The ranges from atoms, expressions, forms and statements use different filtering, so a single generic token filter would discard source distinctions. Angle pairing is conservative: comparison `<` and `>` remain operators unless the shared syntax context accepts them as type-application delimiters. Comments and strings must not produce code delimiter pairs. The PSI-derived folding and declaration ranges remain in the final sequence.

## Measurement

Temporary IntelliJ editor-fixture benchmark, removed after the investigation: configure a file, put the caret in `Nat` on its last line, warm the handler five times, then time twelve `BendSelectionHandler.select` calls with `System.nanoTime`. Report the seventh sorted sample as median and the largest sample as an approximate p95. The large cases use 500 preceding lines of `valueN = pair(N, [a, b])`; the small case uses five. The incomplete case omits the last call's closing delimiters. The Unicode case has `"🙂"` before `D<Nat>`. Runs used JDK 21, the same machine and fixture process type; these numbers are directional rather than a CI performance threshold.

| Case | UTF-16 units | Before median / max (ms) | After median / max (ms) |
| --- | ---: | ---: | ---: |
| Small | 162 | 3.02 / 3.83 | 1.28 / 3.76 |
| Large | 15,307 | 67.19 / 86.95 | 55.34 / 80.15 |
| Incomplete | 15,306 | 56.95 / 62.61 | 40.95 / 49.44 |
| Unicode | 15,310 | 66.05 / 69.54 | 42.68 / 56.42 |

Before the change, a plain full lexer pass on the large fixture took 2.61 ms median. Representative individual stages took 7.47 ms for expressions, 7.07 ms for application prefixes, 10.99 ms for application-at-caret, and 4.35 and 4.30 ms for the two folding calls. These stage times are separate warmed runs and do not sum to the handler time. They show both repeated scans and genuine syntax/application work; code size alone was not the go criterion.

## Result and limits

`BendSelectionSource` now holds one invocation's source and lazily lexed UTF-16 tokens and angle pairs. Atoms, expressions, forms, statements, group components, indexed-write boundaries and syntax-owned delimiter pairing reuse it. Each reader still applies its prior filter and grouping rules. Folding constructs reuse the folding ranges already obtained by the handler. The selection handler remains the IntelliJ adapter and retains its range insertion and sorting order. The editor-action fixture now asserts exact Unicode and incomplete-source expansion steps.

Source applications still tokenize through the existing `symbols.api` contract, which also serves parameter information and other consumers. PSI folding and declaration traversal still have their own owner. This change does not cache across edits or add a second application resolver. A larger performance effort should profile those shared APIs with their other consumers before changing them. The measured large-file call remains tens of milliseconds, and the fixture is synthetic; latency on much larger real files remains unproven.
