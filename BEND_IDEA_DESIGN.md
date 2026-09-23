# Bend language support for IntelliJ IDEA

Research and proposed scope, 2026-09-22. This is a design study; no plugin has been implemented or tested.

Implementation update: the user subsequently selected Scala 3 and excluded Bend as a plugin implementation language. Follow [ARCHITECTURE.md](ARCHITECTURE.md) and [AGENTS.md](AGENTS.md) for current implementation guidance; the Kotlin recommendation below is historical.

## Recommendation

Build a native Kotlin IntelliJ language plugin with a tolerant source parser and PSI, source-based name resolution, and an external Bend checker. Add compiler-backed proof assistance through a separate, versioned tooling interface later.

The first useful release should cover everyday editing, accurate completion and navigation, law/proof navigation, compiler diagnostics for unsaved files, conservative formatting, and Check/Run configurations. These are achievable without implementing dependent type theory in Kotlin.

Use Quint IDEA as a platform integration reference. Its ANTLR choice follows from Quint already having a canonical grammar. Bend has a handwritten parser with context-sensitive elaboration, so copying that architecture would introduce an unnecessary grammar and adapter maintenance burden. Prefer a handwritten recursive-descent/Pratt parser using `PsiBuilder`, backed by a restartable lexer. Grammar-Kit remains an alternative for generating PSI and declaration rules, with custom expression/layout parsing.

## Evidence and versions

The local checkouts studied are:

| Reference | Revision | Relevant role |
|---|---|---|
| Bend | `ff7a40cc9070a34c78399ecd2bbe46a044ad9b4b` | `main.ts` identifies itself as 2.0.25; language parser, checker, loader, CLI and compiler interfaces |
| Bend VS Code | `d63becb903125d2ac47590a6767d57685c20e044` | Extension 0.3.1: highlighting, approximate completion, snapshot checking |
| Quint IDEA | `8cc27d68fd500d041a41aa34658d30c330898826` | Native PSI, resolver, editor features, external diagnostics, platform tests |

Primary local source map. The reference checkouts are intentionally excluded from the public repository; use the upstream source links in the [specification issue](https://github.com/dearlordylord/bend-idea/issues/1).

- [Bend core](.references/bend/bend2/bend.ts): `book_load`, `parse_*`, `body_flatten`, `term_infer`, `term_check`, `book_valid`, `err_show`.
- [Bend CLI](.references/bend/bend2/main.ts): `cli_file`, `book_read`, `cli_report`, `book_run`, `cli_emit`.
- [Compiler](.references/bend/bend2/comp.ts): checked-term consumers, `book_owned`, C/JS generation, execution and runtime interfaces.
- [Guide](.references/bend/guide/GUIDE.md), [Base](.references/bend/bend2/base.bend), and [tests](.references/bend/tests).
- [VS Code language indexer](.references/bend-vscode/src/language.cjs), [snapshot checker](.references/bend-vscode/src/checker.cjs), and [scheduling](.references/bend-vscode/src/diagnostics.cjs).
- [Quint grammar](.references/quint/Quint.g4), [Quint plugin registration](.references/quint-idea/src/main/resources/META-INF/plugin.xml), [implementation learnings](.references/quint-idea/LEARNINGS.md).
- [Bundled Bend formatter](.references/bend/tools/bend-fmt-lsp/src/formatter.ts) and [its scope](.references/bend/tools/bend-fmt-lsp/README.md).

The VS Code study targets installed 2.0.16 and also references older source. The current checkout requires annotations for overloaded arithmetic operators; even the core's introductory comment still mentions the old implicit Nat behavior. Executable parser/checker code takes precedence over comments and older notes. The test corpus also contains historical cases whose expected result is now a missing-import error, so it cannot all be treated as positive syntax examples.

The copied SDK guides contain outdated examples. Quint's actual build uses JDK 21, whereas parts of its learnings still mention Java 17. Pin a coherent platform/JDK/Kotlin/Gradle combination when implementation starts. Do not copy individual version suggestions independently.

Current JetBrains documentation confirms native language integration remains broader than its LSP integration. Its built-in LSP support is unavailable in IDEA open source builds and Android Studio; adding an LSP module dependency does not supply that implementation. The copied LSP guide is misleading on this point. See [custom language support](https://plugins.jetbrains.com/docs/intellij/custom-language-support.html), [parser and PSI](https://plugins.jetbrains.com/docs/intellij/implementing-parser-and-psi.html), and [LSP availability](https://plugins.jetbrains.com/docs/intellij/language-server-protocol.html).

## Language model that the plugin must preserve

### Source syntax and parsing

Bend 2 has `type`, `law`, `def`, leading module imports, and `@unsafe def`. Its surface language includes dependent binders, constructors, patterns, lambdas, parallel lets, monadic blocks, equality proofs, rewrites, holes, arrays and literals.

Important distinctions:

- `D<A>` is a datatype application; `C{x}` is a constructor; `f(x)` is a function call. Type-valued functions also use parentheses. `IO(A)` and datatype `List<A>` therefore cannot be conflated.
- `Kind(q)`, `Type`, `Data`, `Quant`, and quantities `&0`, `&1`, `&2` are language constructs. Most familiar types, functions and literal constructors come from Base.
- `+`, `-`, and `~` on binders mean reusable, erased, and compile-time template respectively. A bare parameter in a normal declaration means an erased quantity parameter. Bare parameters in a law-filling definition acquire their types from the law.
- `?name` fails checking with a goal and context. `?TODO` can pass a local checking rule but makes the whole program incomplete. Neither is proof success.
- `_` is generally a nonbinding placeholder, but becomes a bound variable inside a rewrite motive. `%e@E : P` also binds `e` inside `P`, not in the following proof body.
- `as` is contextual in import headers, not a globally reserved identifier.
- Character/string escapes and multiline strings need proper lexer state. Identifiers are ASCII with dots; positions in the TypeScript implementation are UTF-16 offsets.
- The syntax is only partly governed by indentation. Match cases and do statements use column-sensitive decisions; inline bodies, semicolons and parenthesized bodies also exist. Port the actual decisions instead of imposing Python's block grammar.
- Whitespace is significant beyond indentation: constructor braces are recognized immediately after a name; call/index suffixes have newline rules; `<...>` competes with comparison syntax; nested `>>` can close type arguments; glued `+name`, `-name`, `%`, and greater-than operators have special rules.
- `(a + b : U32)` selects operator names. `{a : T}` is an annotation. These forms do different work; a formatter or intention must preserve that distinction.

The compiler parser immediately resolves names, expands sugar, fills quantities, and flattens patterns. Its core AST loses source distinctions and comments, and parsing fails at the first error. It is useful as a semantic authority but unsuitable as the IDE's editable syntax tree.

The IDE parser should retain surface nodes for imports, declarations, telescopes, bodies, statements, patterns, expressions, operator annotations and proof forms. It should recover at credible declaration/case boundaries, retain missing-token errors locally, tolerate `M.` during completion, and always advance on malformed input. Parsing must not require imported files or a compiler process.

### Names, scopes and modules

`U32.add` is one declared name. `M.U32.add` can be an imported name where only the initial `M` is a file-local alias. There is no general object field access or nested module tree.

Resolution needs two identities: a stable source symbol (file plus declaration) for IDE navigation, and a compiler namespace/order relative to the selected root for checking. `book_load` derives namespaces from paths, deduplicates by realpath and rejects inconsistent namespaces for one file. Do not flatten every project file into one global scope.

Mirror `parse_lookup` and `parse_reso`: lexical bindings take priority for ordinary references; an initial alias can select a module; an existing current-file qualified name is preferred; otherwise the compiler falls back to the bare/global name. Base occupies the empty namespace and can be loaded transitively. Ordinary import aliases are not re-exported.

Constructors belong to the file namespace, not their datatype's namespace. A type and a constructor may share a spelling: Base has both type `Unit` and constructor `Unit{}`. Keep declaration categories separate and resolve according to syntax.

Binder scopes require explicit treatment:

- Earlier telescope parameters scope over later parameter types and the result/body, but a parameter does not scope over its own type.
- All right-hand sides of a parallel let use the outer scope. Its new binders become available together in the continuation.
- Pattern variables belong to their own case; shadowing must be respected.
- Lambda, dependent arrow, existential, law `where`, and rewrite-motive binders have distinct regions.
- An array update followed by another statement implicitly rebinds the array. References after the write need to follow that continuation binding.

Declaration order is semantic. `book_valid` replays declaration and law-fill events. Forward references generally fail; a declared but unfilled law can occur in dead/type positions but cannot generally be called live. A resolver can offer navigation to an invalid forward target while recording it as unavailable for valid completion/checking; do not equate navigability with validity.

### Laws and definitions

A law is a type specification, not necessarily an equality theorem: Base uses laws for type-valued definitions and opaque handles too. A later definition fills it, potentially as `def Laws.some_law(...)` in another file.

Represent law and implementation as separate source elements connected to a logical symbol. Keep law-site documentation and signature, implementation-site parameters/body, and one completion entry. Support multiple candidate implementations in the workspace: two proof roots can independently fill the same law. Only implementations loaded in one checking root conflict.

Root selection is therefore a first-class project setting. `LAWS.bend` alone can correctly have open claims even if a sibling `PROOF.bend` supplies implementations. Offer Check Current File and Check Proof Root, and display which root a result describes. Suggest conventional roots, but allow multiple application/proof roots without inventing a package manifest.

## Feature scope

“Initial” means a worthwhile first complete plugin, not all work in its first commit. “Next” builds on the same model. “Compiler interface” needs information the current CLI does not expose reliably.

| Feature | Stage | Implementation and limits |
|---|---|---|
| `.bend` recognition, icons, theme colors | Initial | Native file type and syntax highlighter; Bend 2 explicitly identified |
| Comments, quotes, delimiters | Initial | `#` commenter, quote handler, brace matcher; angle brackets only where parsed as type arguments |
| Enter/backspace indentation | Initial | Match/do/declaration context, continuation lines and inline forms |
| Folding, outline, breadcrumbs, selection expansion | Initial | Surface PSI for declarations, constructors, cases, do blocks and multiline expressions |
| Semantic highlighting | Initial | Resolve locals, parameters, constructors, types, laws, definitions and aliases; distinguish quantity/template markers |
| Completion | Initial | Valid visible names, Base source, imported declarations/constructors, aliases, local binders, contextual keywords |
| Dotted completion | Initial | Replace member suffix correctly; preserve literal dotted names and alias prefix; no invented receiver methods |
| Import and foreign-path completion | Initial | Relative/absolute `.bend`, Base, cached hash packages, and `.c`/`.js` paths in foreign bodies |
| Live/file templates | Initial | Function, datatype, law, proof skeleton, rewrite, do block, module and LAWS/PROOF pair |
| Quick documentation | Initial | Source signature, comments, quantities and law specification; link to implementation and Base source |
| Parameter information | Initial | Function calls, constructor fields, datatype parameters; show erased/template parameters and omitted leading quantity block correctly |
| Go to declaration, implementations and symbol | Initial | Shared resolver and declaration index; law-to-proof and proof-to-law gutter links |
| Find/highlight usages | Initial | Real references, distinct alias/member ranges and binding identity; law/definition grouping |
| Rename | Initial, after resolver | Locals, aliases, declarations, constructors and paired law/defs; capture/conflict checks and preview |
| Compiler errors | Initial | Check unsaved dependency graph with `--check-only`; conservative text location mapping |
| Local inspections | Initial | Parse errors, malformed imports, missing cached imports, holes, clear duplicates; avoid duplicate compiler messages |
| Reformat Code | Initial | Conservative indentation/spacing with semantic preservation; two-space default |
| Check/Run/Build configurations | Initial | Root, working directory, executable, arguments, environment and output; stop/rerun and console links |
| Proof status | Initial | Root-level checked/incomplete/failed/checking/stale/unavailable state; preserve unsafe/foreign report |
| Generate law implementation | Next | Bare parameter names and `?TODO`; use law telescope, stop before existential witnesses; respect scope/order |
| Generate match cases | Next | Known scrutinee type and valid matchable binder; constructor fields with fresh names; placeholders remain unfinished |
| File move/rename | Next | Update relative import references, foreign paths where applicable, and revalidate affected roots |
| Auto-import | Next | Explicit candidate selection, alias conflict handling and order-aware insertion; include constructors and Base |
| Call/dependency hierarchy | Next | Static named calls/import graph; mark incomplete coverage of higher-order calls and implicit sugar |
| Parameter-name inlays | Next | Conservative hints from resolved signatures; source-declared type/quantity hints where unambiguous |
| Proof/holes tool window | Next | Workspace source inventory plus root-specific checker result; never infer proof completion from a matching def |
| Goal and local-context display | Compiler interface, with limited early action | Named-hole diagnostics already expose one goal; reliable per-hole/current-cursor context needs structured output |
| Expression type hover and type inlays | Compiler interface | Map elaborated typed terms to source nodes; dependent substitutions from compiler |
| Expected-type completion | Compiler interface | Rank/filter candidates using actual goal and instantiation, with bounded work |
| Resource availability display | Compiler interface | Live/dead demand, branch-sensitive usage and reusable promotions; textual occurrence counts are insufficient |
| Try reflexivity / simple proof edits | Compiler interface | Propose an edit and recheck its snapshot; retain explicit incomplete state until full root succeeds |
| Normalize expression | Compiler interface | Explicit action, isolated worker, timeout/output limits; reduction can diverge in dead/unsafe code |
| Show generated C/JS | Next | Build action opening compiler output; no source-level debugging claim |
| Comment/string spellchecking | Next | Reuse IDE spelling UI with language-specific token selection |

An empty match can intentionally eliminate `Empty`; missing cases are not always a syntax error. Unused affine values are legal. Do not flag every dropped value, or offer “add +” without knowing that its kind permits reuse.

Proof-oriented intentions should preserve the user's specification. In particular, never fix a failed proof by inserting `@unsafe` or weakening its law automatically.

## Architecture and implementation details

Use one small Gradle project initially, separated by packages:

```text
language / lexer / parser / psi
resolve / imports / stubs
completion / documentation / parameterInfo
editor / structure / formatting
inspections / refactoring
toolchain / checking / execution
proof / settings
```

The lexer can be JFlex or handwritten. Its restart state must work inside multiline literals, token ranges must cover every character, and bad input must produce tokens. Keep layout and adjacency visible to the parser through explicit newline tokens or raw token-gap inspection. A single lexical vocabulary can serve highlighting and parsing; no need to reproduce Quint's two unrelated token type systems.

Use `PsiNameIdentifierOwner`, source reference objects and a shared scope service. Stub top-level declarations, constructors and import syntax; index names for Go to Symbol, references and completion. Stubs must depend only on their own file contents: store the written alias-qualified proof name, then resolve its law outside stub serialization. Index full dotted names and useful search components so Find Usages does not miss `M.U32.add` when looking for `U32.add`.

Register Base and relevant cached packages as library roots for navigation/indexing, with bounds on package scanning. Read current PSI/documents before disk so unsaved declarations and imports work. Keep global search separate from accessible completion candidates. Do not recursively parse a project's entire source tree on each keystroke.

Use project services for checking, root selection and dependency tracking. Capture immutable snapshots under short read actions; do process I/O outside IDE locks and the UI thread; apply edits in undoable write commands. Associate listeners, jobs, workers and caches with plugin-disposable services. Degrade to local editor features while indexes are unavailable.

Quint's settings, registration, PSI references, folding, structure, fixture tests and process abstraction are useful patterns. Its semantic resolver is not reusable: Quint permits forward references, has different module rules and supports record fields. Its checker cache keys only the current text, and its mirror handles sibling files; Bend needs dependency/version-aware caching and the whole import graph. Preserve cancellation and reject stale results instead of blindly carrying over those implementations.

## Checking with the existing compiler

Require a detected compiler with documented `--check-only` support for the first release. The supplied checkout has it. The VS Code import-only wrapper is useful evidence for a legacy compatibility option, but changes the root namespace and bypasses root-filename behavior unless explicitly compensated. Supporting old compilers is optional additional work.

The current check flow performs loading, `book_valid`, the compiler's reserved-name check, and rejection of outstanding holes/open claims. A helper calling only `book_valid` would miss part of the CLI's success conditions. `--checkup` runs imported programs and must not be used for background diagnostics.

Snapshot requirements:

1. Capture all reachable `.bend` files and open-buffer versions. Include the sibling LAWS presence check when the selected file is PROOF, even if its import is missing.
2. Resolve relative, absolute, hash and Base imports with the selected toolchain. Detect missing dependencies before invoking the compiler. Keep canonical file identity and import order.
3. Materialize temporary copies with an explicit mapping of original URI, copied URI, namespace and rewritten source ranges. Preserve filenames/relationships needed by PROOF checks. Symlink aliases and multiple root paths require care; path rewriting must not accidentally accept a graph the compiler would reject.
4. Preserve line counts for text diagnostics. For future exact spans, retain offset maps because import removal/rewriting changes source lengths. The compiler loader blanks import lines before parsing, so core offsets are not directly original-document offsets.
5. Use the matching installed Base, not an arbitrary completion-only Base override. Surface mismatched toolchain/library configuration. Foreign imports remain inert during checking; native build snapshots, if added, also need their source assets.
6. Disable automatic package downloads and the CLI's daily update check for background work (`BEND_NO_TELEMETRY=1` exists here). Do not claim that `--check-only` alone is offline: `book_load` can fetch missing hash packages.
7. Debounce, cancel superseded work, enforce process/output limits and clean up snapshots. Invalidate roots when imports, Base, compiler identity or relevant documents change. Cache by root and dependency versions, not only the active file.
8. Attribute errors only when unambiguous. The CLI prints context, a definition name and a line excerpt, generally one failure per root; it supplies no structured URI/column map. Otherwise attach the diagnostic to the root and retain the full message.

A successful exit can still report reliance on unsafe or foreign code. Preserve that distinction in root status. A timeout, missing compiler, stale result, unfinished hole or simply locating a proof definition must never produce “verified.” Keep diagnostics owned by the root that produced them so checking one root cannot incorrectly clear another root's errors.

Run configurations are separate from checking: `bend file.bend` runs main, `-o file.c`/`.js` emits source, and other output names build a native executable. Pure main is normalized by the checker; IO main uses the JS execution path in this checkout. Native execution adds the backend's threads/GPU options. Pass arguments as a proper argument list; separate program arguments with `--` where required. Save the relevant documents for explicit run/build, or provide a complete build snapshot; do not silently execute stale disk contents.

## Rich semantic tooling

The compiler already has useful internal data: spans, binder contexts, expected/observed terms, checked terms annotated with types, and hole/open-claim counts. However:

- `Span` contains source text and offsets, not a file URI.
- Lowering/desugaring gives some synthetic nodes shared, broad or missing spans.
- `?TODO` is accepted without emitting its expected goal.
- Normalized printed terms are not guaranteed editable source syntax.
- Checking throws its first error and mutates the book while revealing definitions.

A versioned helper process is a reasonable next step. It can wrap exported compiler APIs for structured first-error diagnostics without changing the trusted core, provided the loader supplies source identity and offset mappings. An upstream tooling API is preferable for collecting all hole goals and source-to-type associations. The reference repository's AGENTS.md explicitly prohibits editing `bend2/bend.ts`; this proposal does not modify it.

Proposed request data: protocol/compiler version, selected root URI, document texts/versions and operation. Proposed responses: source-versioned diagnostics, overall completeness, unsafe/foreign dependencies, and optional goals/types tagged with stable source identity. Serialize bounded display data, not HOAS closures or the mutable Book. Probe capabilities and refuse incompatible compiler/helper pairs.

Begin with process-per-check isolation. A persistent worker and reuse of checked Base may improve latency later, but `book_valid`, template instantiation and sharing cells mutate state; naive caching can contaminate subsequent checks. An independent analysis worker can be killed on timeout, including when printing normalized error terms gets stuck.

The helper transport may eventually be LSP, benefiting several editors. Native PSI remains useful for editing incomplete code, formatting and refactoring. The bundled `bend-fmt-lsp` only formats full documents. The README mentions a community server with hover and diagnostics, but its implementation was not part of this checkout and has not been assessed here; it should not be assumed to solve the semantic-interface requirements.

## Formatting and refactoring boundaries

Port the existing formatter's conservative intent and regression examples, then strengthen them with the surface parser. Its token/indent fingerprint is not a semantic proof: whitespace-sensitive parsing can change without changing token spellings. Preserve significant gaps and newlines; do not wrap expressions, sort declarations or rewrite operator syntax until equivalence is established. Uncertain or malformed regions should be left alone.

Rename is feasible because bindings and references can be identified without proving types. It still needs special handling for literal dots, alias prefixes, separate constructor/type namespaces, cross-file law fills, shadowing, and imported read-only library sources. Parameter renames in laws and proof definitions concern separate source binders and must not be mechanically coupled just because their positions match.

Do not initially offer Optimize Imports: an import can supply transitive Base, fill a law, or affect checking order without an obvious direct name usage. Likewise, automatic declaration sorting, extract/inline function and change signature can alter resource use, termination or dependent types. These need substantially more semantic validation than ordinary text transformations.

## Delivery sequence and acceptance criteria

1. **Syntax foundation:** versioned surface-language specification, lexer, tolerant PSI parser, file type/highlighting, folding, commenting, selection and basic indentation. Cover the guide, Base and meaningful positive/negative corpus examples; retain later declarations after malformed code.
2. **Source intelligence:** imports, scopes, declaration/law model, indexes, completion, documentation, parameter info, navigation and usages. Cover unsaved imports, transitive Base, literal dotted names, aliases, shadowing, parallel RHS scope, rewrite motives and declaration order.
3. **Toolchain integration:** settings, root selection, graph snapshots, cancellable `--check-only` diagnostics, root status and Check/Run/Build configurations. Verify a side-effecting main never runs in background; missing cached packages never download; changed dependencies invalidate results; stale jobs cannot replace current results.
4. **Editing quality:** conservative formatter, rename and law/proof links/generation. Verify parse structure survives formatting, paired law references survive rename, collisions are detected and operations undo cleanly.
5. **Proof assistance:** structured compiler adapter, goal/context panel, actual expression types, bounded semantic completion and validated proof intentions. Treat this as a separate milestone with explicit compiler compatibility.

Use IntelliJ fixture tests for observable completion/navigation/refactoring behavior, parser recovery tests for incomplete buffers, and pinned Bend executables as semantic oracles for the ambiguous syntax and checking workflows. Include CRLF, astral Unicode in literals, paths with spaces, symlink identities, absent Base, multiple proof roots and editor disposal. Plugin Verifier and a real sandbox IDE smoke check cover packaging/API and editor integration. These are proposed implementation checks; no tests were run for this research.

Debugger integration, a GPU profiler, unrestricted proof search, full affine-aware extraction/inlining, package publishing UI, and broad JS/C cross-language refactoring should remain outside the initial scope. The current compiler interfaces do not make those proportionate additions. Static editing support can run wherever IntelliJ runs; compiler execution should initially target Bend's supported macOS/Linux environments, with WSL/remote execution designed separately.
