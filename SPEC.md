# Bend IntelliJ IDEA plugin specification

Status: implementation specification; no plugin implementation exists yet.
Date: 2026-09-22.
Scope: Bend 2 language support for IntelliJ IDEA, delivered in small, usable stages.
Priority rule: simplest useful features first, starting with bend-vscode, then prioritizing the existing Quint IDEA capabilities before new Bend-specific tooling. This specification supersedes the broader initial-release grouping in the earlier design study.

## Problem Statement

Bend developers using IntelliJ IDEA need language support comparable to the existing Bend VS Code extension, followed by the navigation, editing and refactoring capabilities expected from IntelliJ. The repository currently contains reference material and a design study, with no plugin implementation or existing test harness.

Bend's Python-shaped syntax hides language rules that generic highlighting and text matching cannot model reliably: dependent types, affine resources, literal dotted names, file-local aliases, declaration order, laws filled by definitions, and whitespace-sensitive parsing. The editor must remain useful while code is incomplete and must report compiler results without running the user's program while they type.

The user requested examination of the Quint IDEA plugin, canonical Quint grammar, IntelliJ plugin guides, Bend implementation and VS Code plugin, followed by a Markdown specification containing the necessary context and links. They specifically requested development priorities beginning with the simplest existing VS Code features, and subsequently clarified that existing Quint IDEA features should also receive priority. No implementation, deployment or publication target was supplied.

## Solution

Deliver a native Kotlin IntelliJ language plugin incrementally. The first release goal is the VS Code extension's practical coverage: file recognition, theme-aware highlighting, completion and snippets, library/import support, and compiler diagnostics over unsaved source. Each stage below is independently reviewable and should leave a usable plugin.

Build on a tolerant source parser and shared scope model when completion requires them. Use the actual Bend compiler for type, resource, termination and proof checking. After parity, add native navigation, documentation, formatting, rename, execution and proof workflow features. Rich expression types and proof context require a later compiler tooling interface.

### Ordered development plan

Order is authoritative. Complete the exit criteria for each stage before expanding scope. Dependencies take precedence over an isolated feature's apparent small size. P0–P5 form the first release goal. P6–P8 form the second goal: the existing Quint IDEA editing and intelligence baseline. Optional additions in those stages must not delay either baseline. P9–P11 follow afterward.

| Stage | Deliverable | Why here / dependency | Exit criteria |
|---|---|---|---|
| P0 | Minimal installable plugin: language/file type, icons, settings-free startup | Smallest foundation | Plugin loads in sandbox; opening a Bend file selects Bend; static support needs no compiler |
| P1 | Lexer highlighting and line commenting | Existing VS Code functionality; no semantic model needed | Theme colors for language tokens; comments/strings excluded from code; incomplete literals do not crash highlighting; comment toggle works |
| P2 | Contextual keyword completion and the six existing snippets: def, type, law, match, do, import | Existing VS Code functionality; small editor integration | Completion works manually; snippets have editable placeholders; suggestions suppressed in comments/strings |
| P3 | Current-file declaration/local completion and signature/comment text in suggestions | Existing VS Code functionality; introduce tolerant PSI and scope resolution here | Functions, laws, datatypes, constructors, parameters, pattern and local binders complete in their proper regions; law/def pair yields one suggestion; incomplete later code does not erase earlier declarations |
| P4 | Base, imports, aliases, dotted and import-path completion; toolchain/library settings | Completes the VS Code completion surface; builds on P3 | Source-derived Base; direct aliases and transitive Base; cached hash paths; unsaved imported files; missing/cyclic dependencies handled; accepting a suffix never duplicates existing text |
| P5a | Manual Check Current File, compiler detection and text diagnostics | First compiler integration, invoked explicitly before background scheduling | Confirmed check-only capability; whole unsaved graph snapshot; originals untouched; no main execution or package fetch; reliable line attribution or honest root fallback |
| P5b | Debounced background checking, cancellation, dependency invalidation and checker status | Completes VS Code diagnostics parity using proven P5a execution | Imported unsaved changes invalidate results; canceled/stale jobs cannot publish; unavailable/timeout/incomplete states are explicit; root-owned diagnostics clear correctly |
| P6 | Basic brace/quote handling, folding, structure, selection, Go to Declaration and usage highlighting | Existing Quint IDEA editor/navigation capabilities using established PSI/resolver | Each action follows actual parsed scopes; ambiguous angle brackets handled conservatively; imported definitions and Base navigable |
| P7 | Quick Documentation, parameter info, Go to Symbol, Find Usages, semantic colors, breadcrumbs and optional parameter-name hints | Prioritize existing Quint Quick Documentation, Find Usages and completion-related intelligence; builds on P6 | Correct signature source, separate constructor/type symbols, dotted/aliased usages found; scalable declaration index and library roots |
| P8a | Enter/backspace indentation | Existing Quint auto-indent capability; smaller transformation before full formatter | Correct body/case/do/continuation indentation; no changes to significant token adjacency |
| P8b | Conservative Reformat Code, then local/alias rename, then declaration and paired-law rename | Existing Quint formatting and rename support, ordered by increasing transformation complexity | Formatting preserves parser meaning; rename finds correct references, detects capture/conflicts and supports undo/preview |
| P9a | Check Proof Root and multiple root selection; law/proof gutter navigation; hole navigation | Bend-specific workflow supported by P5 and P7 | LAWS-alone incompleteness distinguished from proof-root success; multiple candidate proof implementations handled without global false conflicts |
| P9b | Generate implementation skeletons, simple valid match cases and a proof/holes panel | Adds code generation after navigation/status correctness | Generated proof remains incomplete; fresh binders; only known valid scrutinees; panel labels selected root and source freshness |
| P10a | Explicit Run, then Build/emit C/JS and open generated output | Reuses process/settings infrastructure; execution is a separate user action | Correct arguments, working directory/environment, save/snapshot policy, stop/rerun and output navigation; background checking remains check-only |
| P10b | Foreign-path completion/navigation, file moves, explicit auto-import, static call/dependency hierarchy, file templates and spellchecking | Later conveniences; each can ship separately | Import/path edits preserve identity/order; hierarchy states higher-order limitations; no automatic removal of imports |
| P11a | Versioned compiler tooling adapter with structured diagnostics | Prerequisite for precise semantic assistance | Compatible compiler/version handshake; original URI/range/version mapping; same completeness rules as CLI; killable bounded execution |
| P11b | Goal/context panel, actual expression-type hover/inlays, expected-type completion | Depends on P11a and explicit compiler support | Results refer to current source; missing spans/context are reported as unavailable; no synthetic types presented as compiler facts |
| P11c | Resource-availability feedback, validated simple proof intentions and explicit normalization | Most difficult reasonable features; optional follow-on | Compiler-derived usage semantics; proposed proof edits rechecked; normalization bounded and canceled; unfinished/unsafe results never mislabeled as verified |

P0–P5 are behavioral parity with an explicit compatibility difference: initially require a compiler advertising check-only support. The VS Code extension also supports recognized older 2.0 compilers through an import-only wrapper. That legacy mode is deferred because root namespaces and PROOF/LAWS checks require additional handling. New proof APIs, full formatting, rename, stub indexes and run configurations must not delay the parity release.

### Two existing-plugin baselines

| Baseline | Existing capabilities to prioritize | Delivery |
|---|---|---|
| Bend VS Code | File recognition/icons, theme highlighting, line commenting, keyword/snippet completion, source signatures/comments, current-file/local/Base/import/dotted/path completion, compiler settings, manual/background unsaved-source diagnostics | P0–P5, simplest first |
| Quint IDEA | File recognition/highlighting, completion, settings and external checking | Covered by the first baseline, adapted to Bend semantics |
| Quint IDEA | Brace/quote/backspace assistance, folding, structure view, Go to Declaration | P6, except indentation-specific backspace behavior completed in P8a |
| Quint IDEA | Quick Documentation/type information presentation and Find Usages | P7; source-declared signatures and law types first; actual arbitrary-expression types depend on P11 |
| Quint IDEA | Auto-indent, Reformat Code and reference-based rename | P8; Bend formatting/rename semantics must be respected |

Quint's implementation and fixture tests, as well as its advertised features, define this baseline: rename is implemented/tested even though its README feature list is narrower. Exact Bend expression-type hover cannot be promised from Quint's experience because Quint's CLI supplies structured type data and Bend's does not.

Go to Symbol, breadcrumbs, selection expansion, semantic color refinements, parameter info and optional inlays are useful adjacent work in P6–P7. Implement the actual Quint baseline items first within those stages; these additions are not release blockers. Full compiler-derived expression types, proof tools and execution configurations remain later. This ordering replaces any interpretation of the original research that would put new proof features ahead of established editor support.

## User Stories

Stories are ordered approximately by the stage that delivers them. They define the full roadmap, not a requirement to ship all stories in the parity release.

1. As a developer, I want to open a Bend file and have IntelliJ recognize it, so that I can work without configuring a generic file type.
2. As a developer, I want to see Bend file icons in light and dark themes, so that I can identify source files quickly.
3. As a developer, I want to see theme-aware syntax highlighting, so that I can distinguish declarations, literals, comments, proof forms and operators.
4. As a developer, I want to toggle hash line comments, so that I can use familiar editor shortcuts.
5. As a developer, I want to keep highlighting while a string or declaration is unfinished, so that typing does not disrupt the editor.
6. As a developer, I want to complete appropriate keywords, so that I can discover Bend syntax in context.
7. As a developer, I want to expand def, type, law, match, do and import snippets, so that I can enter common forms with editable placeholders.
8. As a developer, I want to avoid suggestions inside comments and strings, so that completion does not interrupt prose and literal editing.
9. As a developer, I want to complete current-file functions, datatypes, constructors and laws, so that I can find available names.
10. As a developer, I want to see signatures and source comments in suggestions, so that I can choose the intended declaration.
11. As a developer, I want to complete parameters and local binders only in scope, so that suggestions reflect the program I am writing.
12. As a developer, I want to complete case, lambda, law and rewrite binders correctly, so that dependent and proof code receives useful assistance.
13. As a developer, I want to see one completion for a law and its implementation, so that the symbol list is not duplicated.
14. As a developer, I want to insert names without forced argument lists, so that I can use functions as values or partial applications.
15. As a developer, I want to configure my Bend executable, Base source and package cache, so that the plugin works with my installation.
16. As a developer, I want to complete definitions from my actual Base source, so that suggestions match the installed language library.
17. As a developer, I want to complete declarations and constructors through direct import aliases, so that I can work across files.
18. As a developer, I want to receive Base names when a dependency imports Base, so that completion follows the loaded program.
19. As a developer, I want to complete the remaining suffix of a dotted name, so that accepting a suggestion preserves the prefix and existing suffix correctly.
20. As a developer, I want to complete relative, absolute and cached package import paths, so that I can locate available modules.
21. As a developer, I want to use unsaved changes in imported files for completion, so that I do not need to save every intermediate edit.
22. As a developer, I want to retain local completion when an import or Base is missing, so that one unavailable dependency does not disable the editor.
23. As a developer, I want to check the current file explicitly, so that I can request immediate compiler feedback.
24. As a developer, I want to check unsaved source and its unsaved dependencies, so that diagnostics describe my editor state.
25. As a developer, I want to see syntax, name, type, resource and termination failures from Bend, so that I receive the compiler’s actual judgment.
26. As a developer, I want to see compiler errors in the editor and Problems view, so that I can navigate to the failing code.
27. As a developer, I want to see a root-level error when the compiler location is ambiguous, so that the plugin does not blame an unrelated file.
28. As a developer, I want to have background checking never execute main or fetch packages, so that editing remains separate from program execution and dependency installation.
29. As a developer, I want to have edits cancel old checks and discard stale results, so that diagnostics remain current.
30. As a developer, I want to have imported-file changes invalidate affected roots, so that old successes do not survive changed dependencies.
31. As a developer, I want to see checking, checked, incomplete, failed, stale, timed-out and unavailable states, so that I understand what the plugin has actually established.
32. As a developer, I want to disable background diagnostics and receive actionable compiler setup messages, so that I can control checking on my machine.
33. As a developer, I want to retain unsafe and foreign-code information in check results, so that I understand the compiler’s stated proof dependencies.
34. As a developer, I want to use brace and quote assistance appropriate to Bend, so that routine editing takes fewer keystrokes.
35. As a developer, I want to fold declarations and inspect file structure, so that I can navigate large source files.
36. As a developer, I want to expand selection by syntax and follow breadcrumbs, so that I can select and orient myself within expressions and bodies.
37. As a developer, I want to navigate to local, imported and Base declarations, so that I can inspect the code behind a name.
38. As a developer, I want to highlight and find actual usages, so that I can understand where a binding is used.
39. As a developer, I want to search project symbols without importing every file, so that I can discover declarations across the workspace.
40. As a developer, I want to view documentation and parameter information at a call, so that I can supply constructor fields, erased arguments and template arguments correctly.
41. As a developer, I want to distinguish resolved declaration categories visually, so that I can read type and proof code more easily.
42. As a developer, I want to receive correct indentation on Enter and Backspace, so that I can edit nested bodies consistently.
43. As a developer, I want to format code without changing its parsing, so that style cleanup preserves behavior.
44. As a developer, I want to rename a local or import alias with collision checking, so that I can improve names without changing binding identity.
45. As a developer, I want to rename declarations and their paired law implementations together where appropriate, so that references stay connected across files.
46. As a proof author, I want to select a proof root separately from the active file, so that open law files are checked in the intended program context.
47. As a proof author, I want to navigate between a law and its candidate implementations, so that I can inspect both specification and proof.
48. As a proof author, I want to navigate named holes and TODO holes, so that I can find unfinished work.
49. As a proof author, I want to generate a law-filling definition with a TODO body, so that I can start a proof with the correct parameter convention.
50. As a proof author, I want to generate constructor cases for a known matchable binder, so that I can build valid case-analysis skeletons.
51. As a proof author, I want to view laws, implementations and holes alongside root status, so that I can distinguish source inventory from completed checking.
52. As a developer, I want to run main through an explicit run configuration, so that I can execute programs with configured arguments and environment.
53. As a developer, I want to build native output or emit C and JavaScript, so that I can use Bend’s supported compilation modes from the IDE.
54. As a developer, I want to stop and rerun processes and follow console locations, so that I can iterate without leaving the IDE.
55. As a developer, I want to open generated compiler output, so that I can inspect what a build produced.
56. As a developer, I want to navigate foreign source imports, so that I can inspect the external implementation of an effect.
57. As a developer, I want to move source files with import updates, so that I can reorganize the project while preserving module references.
58. As a developer, I want to explicitly import a selected declaration with a suitable alias, so that I can add dependencies without hand-editing every reference.
59. As a developer, I want to inspect static call and import dependencies, so that I can understand relationships while recognizing higher-order limitations.
60. As a developer, I want to create conventional source and law/proof files from templates, so that I can start consistent project files.
61. As a developer, I want to spellcheck comments and strings, so that I can improve documentation without flagging language tokens.
62. As a proof author, I want to see the expected goal and local context at a supported proof location, so that I can decide the next proof step.
63. As a developer, I want to see actual expression types and optional type hints, so that I can understand dependent expressions without guessing.
64. As a developer, I want to rank completion against a compiler-provided expected type, so that I can identify more relevant candidates.
65. As a developer, I want to see compiler-derived resource availability, so that I can understand affine usage and erased positions.
66. As a proof author, I want to try simple proof edits with compiler validation, so that assistance preserves the law and makes unfinished work explicit.
67. As a developer, I want to normalize an expression through an explicit bounded action, so that I can inspect evaluation without freezing the IDE.
68. As a maintainer, I want to pin supported compiler and IDE combinations and test observable behavior, so that language and platform upgrades do not silently break features.
69. As a maintainer, I want to close projects or unload the plugin without orphaned jobs, so that resources and temporary snapshots are cleaned up.

## Implementation Decisions

### Delivery and architecture

- Implement the ordered plan above. P0–P2 need a restartable lexer and editor registration; build the minimum tolerant source PSI needed by P3, extending it as language forms require. Avoid creating a second throwaway regex semantic engine merely to reach completion earlier.
- Use native Kotlin language support. Prefer a handwritten recursive-descent/Pratt parser using PsiBuilder. Grammar-Kit may generate PSI/declaration support if useful; no canonical Bend ANTLR grammar exists in the supplied material.
- Keep syntax parsing independent of imports, compiler availability and dependent type checking. Use a shared scope/resolution model for completion, references, documentation and later rename.
- Start with one Gradle project and modules/packages for language/lexer/parser/PSI, scopes/imports, completion, editor UI, checking/toolchain/settings, and later indexing/documentation/refactoring/formatting/execution/proofs. These are logical boundaries, not a requirement for separate build modules.
- Begin with current-file and direct-import lookup plus bounded caches. Add declaration stubs and global indexes when workspace navigation needs them; avoid scanning the project or package cache on every keystroke.
- Static language support must function without Bend or a JS runtime. Compiler integration uses the configured Bend executable. Do not introduce an LSP or Node dependency for the parity release.
- Target the IntelliJ language/platform APIs, keeping baseline support available in IDEA open source builds. Select and pin a coherent IDE/JDK/Kotlin/Gradle combination at implementation time. The local Quint build is an integration reference, not a guarantee for a different target version.
- Expose diagnostics enablement and executable/Base/package settings by P4/P5. Use source-derived Base definitions, prefer open documents, and distinguish a missing library from a compiler failure. Keep completion and checker libraries aligned with the selected toolchain.
- Ordinary completion inserts names only. Explicit snippets perform larger insertions. Local variables rank ahead of less specific candidates. Do not auto-import during ordinary completion in the parity release.
- Preserve the user’s specifications. Never automatically insert unsafe annotations, weaken a law, or call an unfinished proof verified.

### Required language and compiler context

The following decisions preserve the findings from the source study. They apply across stages even where their user-facing features ship later.


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


### Platform and indexing details

The lexer can be JFlex or handwritten. Its restart state must work inside multiline literals, token ranges must cover every character, and bad input must produce tokens. Keep layout and adjacency visible to the parser through explicit newline tokens or raw token-gap inspection. A single lexical vocabulary can serve highlighting and parsing; no need to reproduce Quint's two unrelated token type systems.

Use `PsiNameIdentifierOwner`, source reference objects and a shared scope service. When global indexing is introduced, stub top-level declarations, constructors and import syntax; index names for Go to Symbol, references and completion. Stubs must depend only on their own file contents: store the written alias-qualified proof name, then resolve its law outside stub serialization. Index full dotted names and useful search components so Find Usages does not miss `M.U32.add` when looking for `U32.add`.

Register Base and relevant cached packages as library roots for navigation/indexing, with bounds on package scanning. Read current PSI/documents before disk so unsaved declarations and imports work. Keep global search separate from accessible completion candidates. Do not recursively parse a project's entire source tree on each keystroke.

Use project services for checking, root selection and dependency tracking. Capture immutable snapshots under short read actions; do process I/O outside IDE locks and the UI thread; apply edits in undoable write commands. Associate listeners, jobs, workers and caches with plugin-disposable services. Degrade to local editor features while indexes are unavailable.

Quint's settings, registration, PSI references, folding, structure, fixture tests and process abstraction are useful patterns. Its semantic resolver is not reusable: Quint permits forward references, has different module rules and supports record fields. Its checker cache keys only the current text, and its mirror handles sibling files; Bend needs dependency/version-aware caching and the whole import graph. Preserve cancellation and reject stale results instead of blindly carrying over those implementations.


### Compiler checking contract


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


### Later compiler tooling interface


The compiler already has useful internal data: spans, binder contexts, expected/observed terms, checked terms annotated with types, and hole/open-claim counts. However:

- `Span` contains source text and offsets, not a file URI.
- Lowering/desugaring gives some synthetic nodes shared, broad or missing spans.
- `?TODO` is accepted without emitting its expected goal.
- Normalized printed terms are not guaranteed editable source syntax.
- Checking throws its first error and mutates the book while revealing definitions.

A versioned helper process is a reasonable next step. It can wrap exported compiler APIs for structured first-error diagnostics without changing the trusted core, provided the loader supplies source identity and offset mappings. An upstream tooling API is preferable for collecting all hole goals and source-to-type associations. The Bend reference repository instructions explicitly prohibit editing its trusted core; this proposal does not modify it. The relevant instructions are linked in Further Notes.

Proposed request data: protocol/compiler version, selected root URI, document texts/versions and operation. Proposed responses: source-versioned diagnostics, overall completeness, unsafe/foreign dependencies, and optional goals/types tagged with stable source identity. Serialize bounded display data, not HOAS closures or the mutable Book. Probe capabilities and refuse incompatible compiler/helper pairs.

Begin with process-per-check isolation. A persistent worker and reuse of checked Base may improve latency later, but `book_valid`, template instantiation and sharing cells mutate state; naive caching can contaminate subsequent checks. An independent analysis worker can be killed on timeout, including when printing normalized error terms gets stuck.

The helper transport may eventually be LSP, benefiting several editors. Native PSI remains useful for editing incomplete code, formatting and refactoring. The bundled `bend-fmt-lsp` only formats full documents. The README mentions a community server with hover and diagnostics, but its implementation was not part of this checkout and has not been assessed here; it should not be assumed to solve the semantic-interface requirements.


### Formatting and refactoring contract


Port the existing formatter's conservative intent and regression examples, then strengthen them with the surface parser. Its token/indent fingerprint is not a semantic proof: whitespace-sensitive parsing can change without changing token spellings. Preserve significant gaps and newlines; do not wrap expressions, sort declarations or rewrite operator syntax until equivalence is established. Uncertain or malformed regions should be left alone.

Rename is feasible because bindings and references can be identified without proving types. It still needs special handling for literal dots, alias prefixes, separate constructor/type namespaces, cross-file law fills, shadowing, and imported read-only library sources. Parameter renames in laws and proof definitions concern separate source binders and must not be mechanically coupled just because their positions match.

Do not initially offer Optimize Imports: an import can supply transitive Base, fill a law, or affect checking order without an obvious direct name usage. Likewise, automatic declaration sorting, extract/inline function and change signature can alter resource use, termination or dependent types. These need substantially more semantic validation than ordinary text transformations.


## Testing Decisions

### Test boundaries

The proposed primary boundary is the IntelliJ editor fixture: open one or more source files, make edits, invoke an editor action and assert the behavior visible to a developer. This covers completion, references, documentation, usages, folding/structure, inspections, formatting and refactoring without mocking the parser or resolver.

One additional boundary is necessary for compiler execution: use a pinned real Bend executable against temporary project graphs and the plugin's checker entry point. This proves that snapshotting, imports, output attribution and process behavior agree with the actual toolchain. Fixture-only mocks cannot establish that background checks never run main.

The user confirmed this testing approach on 2026-09-22: IntelliJ editor fixtures for observable editor behavior, plus real Bend subprocess tests for unsaved dependency checking and confirmation that background checks never execute main.

Good tests assert external results and failure behavior. Avoid tests that duplicate implementation algorithms or mock internal PSI/resolver calls. Lexer coverage/restart and parser recovery tests are justified supporting tests because malformed text and incremental restarts are essential editor contracts. Use real IntelliJ platform fixtures for integration and real compiler fixtures for semantic cases.

### Stage acceptance coverage

- P0–P1: load/install smoke test, file recognition, comment toggle, lexer covering all source without gaps, invalid-character handling, restart inside multiline literals, theme color registration.
- P2: snippet text and placeholder behavior, explicit completion invocation, contextual suppression in comments/strings and incomplete input.
- P3: all declaration categories, paired law/def suggestions, shadowing, telescope order, case isolation, parallel-let RHS scope, lambda/dependent/existential/where/rewrite scopes, array rebinding, forward-reference behavior and parser recovery retaining later declarations.
- P4: direct aliases, non-reexported aliases, transitive Base, same-name type/constructor, exact dotted suffix replacement in the middle/end of a name, newly created and unsaved imported files, unavailable Base, missing/cyclic imports, cached hashes, absolute/relative paths and bounded cache invalidation.
- P5: check a main that would create a marker file and prove the marker remains absent; prove no network request/cache download occurs for missing packages; check unsaved dependencies, stale/canceled process results, timeout/output limits, compiler replacement, errors in identical source excerpts, changed Base, sibling LAWS guard, original-file preservation, cleanup and multiple roots' diagnostic ownership.
- P6–P7: invoke real navigation and usage actions from both declarations and usages; include full dotted names and alias/member ranges; use Base/library roots; verify structure/folding and signature source including law-backed parameters and implicit quantities.
- P8: formatting preserves parsed source structure and compiler outcome for valid examples, is idempotent and does not corrupt malformed input; verify significant spaces/newlines around constructors, calls, natural patterns, prefixes and nested type closers. Rename must preserve binding, detect conflicts, update qualified references and support undo.
- P9: law files with intentionally open claims; proof roots with all fills, TODOs, named holes, errors, unsafe/foreign dependencies and multiple independent implementations. Generate only valid skeleton shapes and never turn source inventory into a successful proof verdict.
- P10: argument quoting/serialization, working directory/environment, save/snapshot policy, stop/rerun, output navigation, generated C/JS, path edits and import order. Explicit Run is the only workflow here that executes the program.
- P11: version/capability incompatibility, missing/ambiguous spans, source-version rejection, each supported goal/type query, bounded normalization and full-root rechecking after a proposed proof edit.

Cross-cutting cases include CRLF, astral Unicode in literals, paths with spaces, symlink identity, malformed intermediate edits, unknown compiler versions, project close/plugin unload and indexing-unavailable operation. Use deliberate fixtures for real import graphs: some historical Bend tests now assert missing imports and do not exercise their original positive scenario.

### Prior art and release checks

Reuse scenarios from the VS Code completion, grammar, checker and actual-editor integration suites. Reuse the Quint IntelliJ fixture style for completion, cross-file references, rename, formatting and diagnostic presentation. Use Bend's guide, Base, demos and selected compiler tests as source examples and semantic oracles. Exact source/test links are in Further Notes.

For releases, run appropriate platform tests, Plugin Verifier against the supported IDE matrix, and a sandbox editor smoke check. Compilation alone does not verify plugin classloader compatibility or real editor behavior. No implementation tests were run while writing this specification.

## Out of Scope

The parity release excludes navigation/refactoring/formatting and execution features assigned to later stages, rich compiler APIs, legacy compiler fallback, full library inference and automatic imports. They remain in this roadmap where explicitly scheduled.

The overall roadmap excludes Bend 1/HVM compatibility; a Kotlin reimplementation of the trusted checker; automatic compiler installation/update; package publishing or an account/package-manager UI; a debugger; GPU profiling; unrestricted proof search; automatic law weakening or unsafe insertion; and general affine/dependent extract-function, inline-function or change-signature refactoring.

Do not add Optimize Imports or declaration sorting until semantics-preserving handling of proof fills, transitive Base and declaration order exists. Do not claim complete call hierarchy through higher-order calls, exact affine consumption from textual occurrence counts, or precise compiler diagnostics where the CLI supplies only excerpts.

Initial compiler execution targets Bend-supported macOS/Linux environments. WSL, remote toolchains and broad JS/C cross-language refactoring require separate designs. No changes to the supplied trusted Bend core are authorized by this specification.

## Further Notes

### Context, provenance and precedence

The project began with references only. The prior study, [BEND_IDEA_DESIGN.md](BEND_IDEA_DESIGN.md), records the investigation and original feature matrix. This specification carries forward its language/architecture constraints, while replacing its broad “Initial” feature grouping with the ordered P0–P11 plan. In conflicts about delivery priority, this specification wins.

The local sources, rather than old marketing descriptions of Bend 1, define the target. The relevant glossary is: **law** (type specification/open claim), **definition/fill** (implementation of a law or self-typed function), **datatype**, **constructor**, **binder**, **quantity** (erased/affine/reusable), **template** (leading compile-time syntax argument), **motive** (rewrite proposition with bound placeholders), **Base** (library in the empty namespace), **book** (loaded declarations and checking order), **root** (entry file defining one loaded/checking graph), **PSI** (IntelliJ source structure), and **snapshot** (immutable captured source graph used for an external check).


The local checkouts studied are:

| Reference | Revision | Relevant role |
|---|---|---|
| Bend | `ff7a40cc9070a34c78399ecd2bbe46a044ad9b4b` | `main.ts` identifies itself as 2.0.25; language parser, checker, loader, CLI and compiler interfaces |
| Bend VS Code | `d63becb903125d2ac47590a6767d57685c20e044` | Extension 0.3.1: highlighting, approximate completion, snapshot checking |
| Quint IDEA | `8cc27d68fd500d041a41aa34658d30c330898826` | Native PSI, resolver, editor features, external diagnostics, platform tests |

Primary local source map:

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


### Reference index for implementation

Local links refer to the supplied reference checkouts, which are intentionally ignored by this project's Git configuration. Public upstream links are included for navigation and reproducibility where available. Retain pinned revisions when comparing behavior; current default branches may have changed. The local Quint IDEA study revision was not published upstream, so its public links target the current master branch and are not byte-for-byte reproductions.

| Material | Local reference | Pinned/public reference |
|---|---|---|
| Bend core parser, scopes, checker, diagnostics | [Core](.references/bend/bend2/bend.ts) | [Pinned core](https://github.com/bendlang/bend/blob/ff7a40cc9070a34c78399ecd2bbe46a044ad9b4b/bend2/bend.ts) |
| Bend CLI, check-only, PROOF guard, run/build | [CLI](.references/bend/bend2/main.ts) | [Pinned CLI](https://github.com/bendlang/bend/blob/ff7a40cc9070a34c78399ecd2bbe46a044ad9b4b/bend2/main.ts) |
| Compiler and runtime interfaces | [Compiler](.references/bend/bend2/comp.ts) | [Pinned compiler](https://github.com/bendlang/bend/blob/ff7a40cc9070a34c78399ecd2bbe46a044ad9b4b/bend2/comp.ts) |
| Language guide | [Guide](.references/bend/guide/GUIDE.md) | [Pinned guide](https://github.com/bendlang/bend/blob/ff7a40cc9070a34c78399ecd2bbe46a044ad9b4b/guide/GUIDE.md) |
| Base declarations and comments | [Base](.references/bend/bend2/base.bend) | [Pinned Base](https://github.com/bendlang/bend/blob/ff7a40cc9070a34c78399ecd2bbe46a044ad9b4b/bend2/base.bend) |
| Compiler behavior fixtures | [Tests](.references/bend/tests) | [Pinned tests](https://github.com/bendlang/bend/tree/ff7a40cc9070a34c78399ecd2bbe46a044ad9b4b/tests) |
| Concrete separate laws/proofs | [Pong laws](.references/bend/demos/app_pong_game_2d/LAWS.bend), [Pong proofs](.references/bend/demos/app_pong_game_2d/PROOF.bend) | [Pinned demo](https://github.com/bendlang/bend/tree/ff7a40cc9070a34c78399ecd2bbe46a044ad9b4b/demos/app_pong_game_2d) |
| Trusted-core editing restriction | [Bend instructions](.references/bend/AGENTS.md) | [Pinned instructions](https://github.com/bendlang/bend/blob/ff7a40cc9070a34c78399ecd2bbe46a044ad9b4b/AGENTS.md) |
| Formatting implementation and scope | [Formatter](.references/bend/tools/bend-fmt-lsp/src/formatter.ts), [README](.references/bend/tools/bend-fmt-lsp/README.md), [tests](.references/bend/tools/bend-fmt-lsp/src/test) | [Pinned formatter package](https://github.com/bendlang/bend/tree/ff7a40cc9070a34c78399ecd2bbe46a044ad9b4b/tools/bend-fmt-lsp) |
| VS Code behavior baseline | [README](.references/bend-vscode/README.md), [language study](.references/bend-vscode/docs/language-notes.md), [manifest](.references/bend-vscode/package.json) | [Pinned extension](https://github.com/Giulio2002/bend-vscode/tree/d63becb903125d2ac47590a6767d57685c20e044) |
| VS Code highlighting/configuration | [TextMate grammar](.references/bend-vscode/syntaxes/bend.tmLanguage.json), [language configuration](.references/bend-vscode/language-configuration.json) | [Pinned grammar](https://github.com/Giulio2002/bend-vscode/blob/d63becb903125d2ac47590a6767d57685c20e044/syntaxes/bend.tmLanguage.json) |
| VS Code completion/indexing | [Language index](.references/bend-vscode/src/language.cjs), [extension/import graph](.references/bend-vscode/src/extension.cjs) | [Pinned source](https://github.com/Giulio2002/bend-vscode/tree/d63becb903125d2ac47590a6767d57685c20e044/src) |
| VS Code checker/scheduling | [Checker](.references/bend-vscode/src/checker.cjs), [diagnostics](.references/bend-vscode/src/diagnostics.cjs) | [Pinned checker](https://github.com/Giulio2002/bend-vscode/blob/d63becb903125d2ac47590a6767d57685c20e044/src/checker.cjs) |
| VS Code test prior art | [Completion tests](.references/bend-vscode/test/completion.test.cjs), [grammar tests](.references/bend-vscode/test/grammar.test.cjs), [checker tests](.references/bend-vscode/test/checker.test.cjs), [editor integration](.references/bend-vscode/test/integration/suite.cjs) | [Pinned tests](https://github.com/Giulio2002/bend-vscode/tree/d63becb903125d2ac47590a6767d57685c20e044/test) |
| Quint IDEA architecture/build | [README](.references/quint-idea/README.md), [PRD](.references/quint-idea/PRD.md), [build](.references/quint-idea/build.gradle.kts), [properties](.references/quint-idea/gradle.properties), [plugin registration](.references/quint-idea/src/main/resources/META-INF/plugin.xml) | [Current public upstream](https://github.com/dearlordylord/quint-idea/tree/master) (the local study revision is unavailable upstream) |
| Quint platform lessons | [Instructions](.references/quint-idea/AGENTS.md), [learnings](.references/quint-idea/LEARNINGS.md) | [Current public learnings](https://github.com/dearlordylord/quint-idea/blob/master/LEARNINGS.md) |
| Quint implementations/tests | [Production Kotlin](.references/quint-idea/src/main/kotlin/com/dearlordylord/quint/idea), [fixture tests](.references/quint-idea/src/test/kotlin/com/dearlordylord/quint/idea) | [Current public source](https://github.com/dearlordylord/quint-idea/tree/master/src) |
| Canonical Quint grammar comparison | [Quint grammar](.references/quint/Quint.g4), [effect grammar](.references/quint/Effect.g4), [reference notes](.references/quint-idea-README.md) | [Upstream grammar, unpinned](https://github.com/informalsystems/quint/blob/main/quint/src/generated/Quint.g4) |
| Community Bend server, not evaluated | Mentioned in [Bend README](.references/bend/README.md) | [bend2-lsp](https://github.com/don2e4/bend2-lsp) |

Quint's checkout is cloned from a local sibling repository. Its study revision is not present in the public upstream; the available links point to the current public repository. The supplied local checkout is the authoritative reference for this study. The standalone Quint grammar snapshot has no recorded commit in the supplied reference notes; its upstream link is deliberately marked unpinned. Preserve notices/licenses when adapting source: [Bend license](.references/bend/LICENSE), [VS Code license](.references/bend-vscode/LICENSE), [Quint license](.references/quint-idea/LICENSE) and [Quint third-party notices](.references/quint-idea/NOTICE).

### IntelliJ guide index

The [local guide index](.references/idea/README.md) covers all 24 topic files. Use these as reading aids, and verify APIs against the pinned platform; code examples and LSP availability in the copied notes contain known stale details.

| Topic | Local guides | Official documentation |
|---|---|---|
| Setup/build/registration | [Getting started](.references/idea/01-getting-started.md), [Gradle](.references/idea/02-gradle-plugin.md), [structure](.references/idea/03-plugin-structure.md) | [Plugin development](https://plugins.jetbrains.com/docs/intellij/developing-plugins.html), [Gradle plugin](https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html) |
| Actions/threading/lifecycle | [Actions](.references/idea/04-action-system.md), [threading](.references/idea/05-threading-model.md), [disposal](.references/idea/06-disposables.md) | [Actions](https://plugins.jetbrains.com/docs/intellij/action-system.html), [threading](https://plugins.jetbrains.com/docs/intellij/threading-model.html), [disposal](https://plugins.jetbrains.com/docs/intellij/disposers.html) |
| Language foundations | [PSI](.references/idea/07-psi.md), [overview](.references/idea/08-custom-language-overview.md), [file type](.references/idea/09-file-type.md), [lexer](.references/idea/10-lexer.md), [parser](.references/idea/11-parser-and-psi.md), [highlighting](.references/idea/12-syntax-highlighting.md) | [Custom languages](https://plugins.jetbrains.com/docs/intellij/custom-language-support.html), [lexer](https://plugins.jetbrains.com/docs/intellij/implementing-lexer.html), [parser/PSI](https://plugins.jetbrains.com/docs/intellij/implementing-parser-and-psi.html) |
| Intelligence/refactoring | [References](.references/idea/13-references-and-navigation.md), [completion](.references/idea/14-code-completion.md), [usages](.references/idea/15-find-usages.md), [refactoring](.references/idea/16-refactoring.md) | [References](https://plugins.jetbrains.com/docs/intellij/references-and-resolve.html), [completion](https://plugins.jetbrains.com/docs/intellij/code-completion.html), [rename](https://plugins.jetbrains.com/docs/intellij/rename-refactoring.html) |
| Quality/indexing | [Formatting](.references/idea/17-code-formatting.md), [inspections](.references/idea/18-inspections.md), [stubs](.references/idea/19-stub-indexes.md) | [Formatter](https://plugins.jetbrains.com/docs/intellij/code-formatter.html), [inspections](https://plugins.jetbrains.com/docs/intellij/code-inspections.html), [stubs](https://plugins.jetbrains.com/docs/intellij/stub-indexes.html) |
| LSP/tool UI/execution | [LSP](.references/idea/20-lsp.md), [tool windows](.references/idea/21-tool-windows.md), [run configurations](.references/idea/22-run-configurations.md) | [LSP](https://plugins.jetbrains.com/docs/intellij/language-server-protocol.html), [tool windows](https://plugins.jetbrains.com/docs/intellij/tool-windows.html), [run configurations](https://plugins.jetbrains.com/docs/intellij/run-configurations.html) |
| Validation/distribution | [Testing](.references/idea/23-testing.md), [publishing](.references/idea/24-publishing.md) | [Tests/fixtures](https://plugins.jetbrains.com/docs/intellij/tests-and-fixtures.html), [plugin verification](https://plugins.jetbrains.com/docs/intellij/plugin-verifier.html), [publishing](https://plugins.jetbrains.com/docs/intellij/publishing-plugin.html) |

### Specification and issue-tracker handoff

This document is the implementation specification for the Bend IDEA plugin. The public [GitHub issue tracker](https://github.com/dearlordylord/bend-idea/issues) contains a parent specification issue and 46 feature issues. The feature issues are children of that specification issue, use the `ready-for-agent` label and a label for their priority group, and preserve technical blockers as native GitHub issue dependencies. The repository retains this Markdown specification and the complete local ticket queue for review and portability.
