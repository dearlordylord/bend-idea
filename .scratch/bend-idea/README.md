# Bend IDEA ticket queue

The user approved this 46-ticket breakdown and its blocking edges. The tickets are published as native GitHub issues under the [Bend IDEA specification issue](https://github.com/dearlordylord/bend-idea/issues/1); each feature issue is a child of that parent, and its blocker edges are native GitHub issue dependencies. This local Markdown queue remains a complete reviewable copy. The [specification](../../SPEC.md) supplies the semantic contract, complete context, source revisions and upstream links. Each ticket is a separate file with its own acceptance criteria.

## Working agreement

- Implement the earliest unfinished priority group first: **01–13 VS Code coverage**, **14–22 Quint IDEA coverage**, **23–26 additional native editing**, **27–39 Bend workflows/execution**, then **40–46 compiler-backed assistance**. Within each group, the numbered order is the preferred simple-to-complex sequence.
- Blocking links express technical prerequisites, not priority. Work on a ticket only after its blockers are complete; an early dependency edge never authorizes skipping the priority groups. All tickets are currently unimplemented. The initial frontier is ticket 01.
- `ready-for-agent` means the ticket has a reviewed scope. It does not mean its blockers are complete. Later semantic tickets additionally name external compiler capability prerequisites; unsupported capabilities must be reported rather than replaced by a placeholder and marked complete.
- Each ticket includes the implementation and tests for its observable behavior. The user confirmed IntelliJ editor fixtures as the primary boundary and real Bend subprocess tests for checking unsaved graphs and proving checks never run main. Use focused lexer/parser tests for restart/recovery contracts; avoid mocks of internal resolution and tests that merely mirror the implementation.
- Keep PSI/source editing independent of compiler availability. Share scopes/references across features. Preserve unsaved documents, source versions, declaration/import order, file-local aliases, literal dotted names, separate constructor/type symbols, affine semantics and law/fill relationships.
- Use background, cancelable process work outside UI/read locks, undoable editor edits, bounded caches and plugin-owned disposable services. Test the lifecycle behavior affected by each change rather than deferring it to a generic final hardening ticket.
- Syntax and completion can work without Bend. Checking requires demonstrated check-only support, snapshots, no main execution, no automatic package downloads, complete CLI validity conditions and honest source attribution. Report current, stale, incomplete, unavailable and unsafe/foreign states accurately.
- Do not rewrite the trusted checker, weaken laws, insert unsafe annotations as a fix, sort declarations/imports or silently remove imports. The [reference instructions](../../.references/bend/AGENTS.md) prohibit editing the trusted Bend core.
- Supplied references are read-only study material. Their local links require the reference checkouts; the specification also contains upstream links pinned where provenance is known. Verify SDK APIs against the chosen platform: copied guides and some historical notes are stale.
- No prefactoring ticket is needed because this project has no implementation yet. Grow the parser, resolver and integrations through the smallest complete user-facing slices. Do not create a competing temporary semantic engine.
- At each baseline release, run the relevant fixture/compiler checks, Plugin Verifier for supported IDE versions and a sandbox editor smoke check. No release requires completion of the later semantic roadmap.
- Update individual ticket status/checklists as work is completed. Never mark a blocked compiler-dependent ticket complete merely because it displays an unavailable message.

## Tickets

| Ticket | Technical blockers | Priority group |
|---|---|---|
| [01: Recognize Bend files](issues/01-recognize-bend-files.md) | None | VS Code |
| [02: Highlight and comment Bend code](issues/02-highlight-and-comment-bend-code.md) | [01](issues/01-recognize-bend-files.md) | VS Code |
| [03: Complete keywords and expand snippets](issues/03-complete-keywords-and-expand-snippets.md) | [02](issues/02-highlight-and-comment-bend-code.md) | VS Code |
| [04: Complete current-file declarations](issues/04-complete-current-file-declarations.md) | [03](issues/03-complete-keywords-and-expand-snippets.md) | VS Code |
| [05: Complete parameters, lambdas and local bindings](issues/05-complete-parameters-lambdas-and-local-bindings.md) | [04](issues/04-complete-current-file-declarations.md) | VS Code |
| [06: Complete match and do-block bindings](issues/06-complete-match-and-do-block-bindings.md) | [05](issues/05-complete-parameters-lambdas-and-local-bindings.md) | VS Code |
| [07: Complete laws and proof bindings](issues/07-complete-laws-and-proof-bindings.md) | [06](issues/06-complete-match-and-do-block-bindings.md) | VS Code |
| [08: Configure libraries and complete Base names](issues/08-configure-libraries-and-complete-base-names.md) | [07](issues/07-complete-laws-and-proof-bindings.md) | VS Code |
| [09: Complete imported and qualified names](issues/09-complete-imported-and-qualified-names.md) | [08](issues/08-configure-libraries-and-complete-base-names.md) | VS Code |
| [10: Complete module import paths](issues/10-complete-module-import-paths.md) | [09](issues/09-complete-imported-and-qualified-names.md) | VS Code |
| [11: Check a single unsaved file explicitly](issues/11-check-a-single-unsaved-file-explicitly.md) | [08](issues/08-configure-libraries-and-complete-base-names.md) | VS Code |
| [12: Check unsaved dependency graphs](issues/12-check-unsaved-dependency-graphs.md) | [09](issues/09-complete-imported-and-qualified-names.md), [11](issues/11-check-a-single-unsaved-file-explicitly.md) | VS Code |
| [13: Check automatically after edits](issues/13-check-automatically-after-edits.md) | [12](issues/12-check-unsaved-dependency-graphs.md) | VS Code |
| [14: Assist brace and quote editing](issues/14-assist-brace-and-quote-editing.md) | [07](issues/07-complete-laws-and-proof-bindings.md) | Quint IDEA |
| [15: Fold code and browse file structure](issues/15-fold-code-and-browse-file-structure.md) | [07](issues/07-complete-laws-and-proof-bindings.md) | Quint IDEA |
| [16: Navigate to declarations](issues/16-navigate-to-declarations.md) | [09](issues/09-complete-imported-and-qualified-names.md) | Quint IDEA |
| [17: Show Quick Documentation](issues/17-show-quick-documentation.md) | [16](issues/16-navigate-to-declarations.md) | Quint IDEA |
| [18: Find and highlight usages](issues/18-find-and-highlight-usages.md) | [16](issues/16-navigate-to-declarations.md) | Quint IDEA |
| [19: Indent on Enter and Backspace](issues/19-indent-on-enter-and-backspace.md) | [07](issues/07-complete-laws-and-proof-bindings.md), [14](issues/14-assist-brace-and-quote-editing.md) | Quint IDEA |
| [20: Reformat conservatively](issues/20-reformat-conservatively.md) | [19](issues/19-indent-on-enter-and-backspace.md) | Quint IDEA |
| [21: Rename locals and aliases](issues/21-rename-locals-and-aliases.md) | [18](issues/18-find-and-highlight-usages.md) | Quint IDEA |
| [22: Rename declarations and paired laws](issues/22-rename-declarations-and-paired-laws.md) | [21](issues/21-rename-locals-and-aliases.md) | Quint IDEA |
| [23: Search workspace symbols](issues/23-search-workspace-symbols.md) | [16](issues/16-navigate-to-declarations.md) | Native additions |
| [24: Show parameter information](issues/24-show-parameter-information.md) | [17](issues/17-show-quick-documentation.md) | Native additions |
| [25: Improve semantic reading and selection](issues/25-improve-semantic-reading-and-selection.md) | [15](issues/15-fold-code-and-browse-file-structure.md), [16](issues/16-navigate-to-declarations.md) | Native additions |
| [26: Show optional parameter-name hints](issues/26-show-optional-parameter-name-hints.md) | [24](issues/24-show-parameter-information.md) | Native additions |
| [27: Select and check proof roots](issues/27-select-and-check-proof-roots.md) | [13](issues/13-check-automatically-after-edits.md) | Bend workflows |
| [28: Navigate between laws, proofs and holes](issues/28-navigate-between-laws-proofs-and-holes.md) | [18](issues/18-find-and-highlight-usages.md), [27](issues/27-select-and-check-proof-roots.md) | Bend workflows |
| [29: Generate a law implementation skeleton](issues/29-generate-a-law-implementation-skeleton.md) | [28](issues/28-navigate-between-laws-proofs-and-holes.md) | Bend workflows |
| [30: Generate simple match cases](issues/30-generate-simple-match-cases.md) | [24](issues/24-show-parameter-information.md) | Bend workflows |
| [31: Browse proof progress](issues/31-browse-proof-progress.md) | [28](issues/28-navigate-between-laws-proofs-and-holes.md) | Bend workflows |
| [32: Run Bend explicitly](issues/32-run-bend-explicitly.md) | [12](issues/12-check-unsaved-dependency-graphs.md) | Bend workflows |
| [33: Build and inspect generated output](issues/33-build-and-inspect-generated-output.md) | [32](issues/32-run-bend-explicitly.md) | Bend workflows |
| [34: Complete and navigate foreign imports](issues/34-complete-and-navigate-foreign-imports.md) | [16](issues/16-navigate-to-declarations.md) | Bend workflows |
| [35: Move and rename source files](issues/35-move-and-rename-source-files.md) | [22](issues/22-rename-declarations-and-paired-laws.md), [34](issues/34-complete-and-navigate-foreign-imports.md) | Bend workflows |
| [36: Import a selected symbol explicitly](issues/36-import-a-selected-symbol-explicitly.md) | [23](issues/23-search-workspace-symbols.md) | Bend workflows |
| [37: Inspect static dependencies](issues/37-inspect-static-dependencies.md) | [18](issues/18-find-and-highlight-usages.md), [23](issues/23-search-workspace-symbols.md) | Bend workflows |
| [38: Create conventional files from templates](issues/38-create-conventional-files-from-templates.md) | [03](issues/03-complete-keywords-and-expand-snippets.md), [29](issues/29-generate-a-law-implementation-skeleton.md) | Bend workflows |
| [39: Spellcheck comments and strings](issues/39-spellcheck-comments-and-strings.md) | [02](issues/02-highlight-and-comment-bend-code.md) | Bend workflows |
| [40: Show structured compiler diagnostics](issues/40-show-structured-compiler-diagnostics.md) | [12](issues/12-check-unsaved-dependency-graphs.md) | Semantic assistance |
| [41: Inspect compiler-provided goals and context](issues/41-inspect-compiler-provided-goals-and-context.md) | [28](issues/28-navigate-between-laws-proofs-and-holes.md), [40](issues/40-show-structured-compiler-diagnostics.md) | Semantic assistance |
| [42: Inspect actual expression types](issues/42-inspect-actual-expression-types.md) | [17](issues/17-show-quick-documentation.md), [40](issues/40-show-structured-compiler-diagnostics.md) | Semantic assistance |
| [43: Complete against an expected type](issues/43-complete-against-an-expected-type.md) | [09](issues/09-complete-imported-and-qualified-names.md), [41](issues/41-inspect-compiler-provided-goals-and-context.md), [42](issues/42-inspect-actual-expression-types.md) | Semantic assistance |
| [44: Explain resource availability](issues/44-explain-resource-availability.md) | [41](issues/41-inspect-compiler-provided-goals-and-context.md), [42](issues/42-inspect-actual-expression-types.md) | Semantic assistance |
| [45: Try simple proof edits](issues/45-try-simple-proof-edits.md) | [29](issues/29-generate-a-law-implementation-skeleton.md), [41](issues/41-inspect-compiler-provided-goals-and-context.md) | Semantic assistance |
| [46: Normalize an expression explicitly](issues/46-normalize-an-expression-explicitly.md) | [40](issues/40-show-structured-compiler-diagnostics.md), [42](issues/42-inspect-actual-expression-types.md) | Semantic assistance |
