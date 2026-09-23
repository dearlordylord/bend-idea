# Bend IDEA implementation issue index

The user approved this 46-ticket breakdown and its blocking edges. The tickets are published as native GitHub issues under the [Bend IDEA specification issue](https://github.com/dearlordylord/bend-idea/issues/1); each feature issue is a child of that parent, and its blocker edges are native GitHub issue dependencies. This index links to the GitHub issue bodies, which hold the feature scope and acceptance criteria. The [specification](../../SPEC.md) supplies the semantic contract, complete context, source revisions and upstream links.

## Working agreement

- Implement the earliest unfinished priority group first: **01–13 VS Code coverage**, **14–22 Quint IDEA coverage**, **23–26 additional native editing**, **27–39 Bend workflows/execution**, then **40–46 compiler-backed assistance**. Within each group, the numbered order is the preferred simple-to-complex sequence.
- Blocking links express technical prerequisites, not priority. Work on a ticket only after its blockers are complete; an early dependency edge never authorizes skipping the priority groups. All tickets are currently unimplemented. The initial frontier is ticket 01.
- `ready-for-agent` means the ticket has a reviewed scope. It does not mean its blockers are complete. Later semantic tickets additionally name external compiler capability prerequisites; unsupported capabilities must be reported rather than replaced by a placeholder and marked complete.
- Each ticket includes the implementation and tests for its observable behavior. The user confirmed IntelliJ editor fixtures as the primary boundary and real Bend subprocess tests for checking unsaved graphs and proving checks never run main. Use focused lexer/parser tests for restart/recovery contracts; avoid mocks of internal resolution and tests that merely mirror the implementation.
- Keep PSI/source editing independent of compiler availability. Share scopes/references across features. Preserve unsaved documents, source versions, declaration/import order, file-local aliases, literal dotted names, separate constructor/type symbols, affine semantics and law/fill relationships.
- Use background, cancelable process work outside UI/read locks, undoable editor edits, bounded caches and plugin-owned disposable services. Test the lifecycle behavior affected by each change rather than deferring it to a generic final hardening ticket.
- Syntax and completion can work without Bend. Checking requires demonstrated check-only support, snapshots, no main execution, no automatic package downloads, complete CLI validity conditions and honest source attribution. Report current, stale, incomplete, unavailable and unsafe/foreign states accurately.
- Do not rewrite the trusted checker, weaken laws, insert unsafe annotations as a fix, sort declarations/imports or silently remove imports. The [Bend reference instructions](https://github.com/bendlang/bend/blob/ff7a40cc9070a34c78399ecd2bbe46a044ad9b4b/AGENTS.md) prohibit editing the trusted Bend core.
- Supplied references are read-only study material. Their local links require the reference checkouts; the specification also contains upstream links pinned where provenance is known. Verify SDK APIs against the chosen platform: copied guides and some historical notes are stale.
- No prefactoring ticket is needed because this project has no implementation yet. Grow the parser, resolver and integrations through the smallest complete user-facing slices. Do not create a competing temporary semantic engine.
- At each baseline release, run the relevant fixture/compiler checks, Plugin Verifier for supported IDE versions and a sandbox editor smoke check. No release requires completion of the later semantic roadmap.
- Update each GitHub issue status/checklist as work is completed. Never mark a blocked compiler-dependent ticket complete merely because it displays an unavailable message.

## Tickets

| Feature issue | Blocked by | Priority group |
|---|---|---|
| [01: Recognize Bend files](https://github.com/dearlordylord/bend-idea/issues/2) | None | VS Code |
| [02: Highlight and comment Bend code](https://github.com/dearlordylord/bend-idea/issues/3) | [#2](https://github.com/dearlordylord/bend-idea/issues/2) | VS Code |
| [03: Complete keywords and expand snippets](https://github.com/dearlordylord/bend-idea/issues/4) | [#3](https://github.com/dearlordylord/bend-idea/issues/3) | VS Code |
| [04: Complete current-file declarations](https://github.com/dearlordylord/bend-idea/issues/5) | [#4](https://github.com/dearlordylord/bend-idea/issues/4) | VS Code |
| [05: Complete parameters, lambdas and local bindings](https://github.com/dearlordylord/bend-idea/issues/6) | [#5](https://github.com/dearlordylord/bend-idea/issues/5) | VS Code |
| [06: Complete match and do-block bindings](https://github.com/dearlordylord/bend-idea/issues/7) | [#6](https://github.com/dearlordylord/bend-idea/issues/6) | VS Code |
| [07: Complete laws and proof bindings](https://github.com/dearlordylord/bend-idea/issues/8) | [#7](https://github.com/dearlordylord/bend-idea/issues/7) | VS Code |
| [08: Configure libraries and complete Base names](https://github.com/dearlordylord/bend-idea/issues/9) | [#8](https://github.com/dearlordylord/bend-idea/issues/8) | VS Code |
| [09: Complete imported and qualified names](https://github.com/dearlordylord/bend-idea/issues/10) | [#9](https://github.com/dearlordylord/bend-idea/issues/9) | VS Code |
| [10: Complete module import paths](https://github.com/dearlordylord/bend-idea/issues/11) | [#10](https://github.com/dearlordylord/bend-idea/issues/10) | VS Code |
| [11: Check a single unsaved file explicitly](https://github.com/dearlordylord/bend-idea/issues/12) | [#9](https://github.com/dearlordylord/bend-idea/issues/9) | VS Code |
| [12: Check unsaved dependency graphs](https://github.com/dearlordylord/bend-idea/issues/13) | [#10](https://github.com/dearlordylord/bend-idea/issues/10), [#12](https://github.com/dearlordylord/bend-idea/issues/12) | VS Code |
| [13: Check automatically after edits](https://github.com/dearlordylord/bend-idea/issues/14) | [#13](https://github.com/dearlordylord/bend-idea/issues/13) | VS Code |
| [14: Assist brace and quote editing](https://github.com/dearlordylord/bend-idea/issues/15) | [#8](https://github.com/dearlordylord/bend-idea/issues/8) | Quint IDEA |
| [15: Fold code and browse file structure](https://github.com/dearlordylord/bend-idea/issues/16) | [#8](https://github.com/dearlordylord/bend-idea/issues/8) | Quint IDEA |
| [16: Navigate to declarations](https://github.com/dearlordylord/bend-idea/issues/17) | [#10](https://github.com/dearlordylord/bend-idea/issues/10) | Quint IDEA |
| [17: Show Quick Documentation](https://github.com/dearlordylord/bend-idea/issues/18) | [#17](https://github.com/dearlordylord/bend-idea/issues/17) | Quint IDEA |
| [18: Find and highlight usages](https://github.com/dearlordylord/bend-idea/issues/19) | [#17](https://github.com/dearlordylord/bend-idea/issues/17) | Quint IDEA |
| [19: Indent on Enter and Backspace](https://github.com/dearlordylord/bend-idea/issues/20) | [#8](https://github.com/dearlordylord/bend-idea/issues/8), [#15](https://github.com/dearlordylord/bend-idea/issues/15) | Quint IDEA |
| [20: Reformat conservatively](https://github.com/dearlordylord/bend-idea/issues/21) | [#20](https://github.com/dearlordylord/bend-idea/issues/20) | Quint IDEA |
| [21: Rename locals and aliases](https://github.com/dearlordylord/bend-idea/issues/22) | [#19](https://github.com/dearlordylord/bend-idea/issues/19) | Quint IDEA |
| [22: Rename declarations and paired laws](https://github.com/dearlordylord/bend-idea/issues/23) | [#22](https://github.com/dearlordylord/bend-idea/issues/22) | Quint IDEA |
| [23: Search workspace symbols](https://github.com/dearlordylord/bend-idea/issues/24) | [#17](https://github.com/dearlordylord/bend-idea/issues/17) | Native additions |
| [24: Show parameter information](https://github.com/dearlordylord/bend-idea/issues/25) | [#18](https://github.com/dearlordylord/bend-idea/issues/18) | Native additions |
| [25: Improve semantic reading and selection](https://github.com/dearlordylord/bend-idea/issues/26) | [#16](https://github.com/dearlordylord/bend-idea/issues/16), [#17](https://github.com/dearlordylord/bend-idea/issues/17) | Native additions |
| [26: Show optional parameter-name hints](https://github.com/dearlordylord/bend-idea/issues/27) | [#25](https://github.com/dearlordylord/bend-idea/issues/25) | Native additions |
| [27: Select and check proof roots](https://github.com/dearlordylord/bend-idea/issues/28) | [#14](https://github.com/dearlordylord/bend-idea/issues/14) | Bend workflows |
| [28: Navigate between laws, proofs and holes](https://github.com/dearlordylord/bend-idea/issues/29) | [#19](https://github.com/dearlordylord/bend-idea/issues/19), [#28](https://github.com/dearlordylord/bend-idea/issues/28) | Bend workflows |
| [29: Generate a law implementation skeleton](https://github.com/dearlordylord/bend-idea/issues/30) | [#29](https://github.com/dearlordylord/bend-idea/issues/29) | Bend workflows |
| [30: Generate simple match cases](https://github.com/dearlordylord/bend-idea/issues/31) | [#25](https://github.com/dearlordylord/bend-idea/issues/25) | Bend workflows |
| [31: Browse proof progress](https://github.com/dearlordylord/bend-idea/issues/32) | [#29](https://github.com/dearlordylord/bend-idea/issues/29) | Bend workflows |
| [32: Run Bend explicitly](https://github.com/dearlordylord/bend-idea/issues/33) | [#13](https://github.com/dearlordylord/bend-idea/issues/13) | Bend workflows |
| [33: Build and inspect generated output](https://github.com/dearlordylord/bend-idea/issues/34) | [#33](https://github.com/dearlordylord/bend-idea/issues/33) | Bend workflows |
| [34: Complete and navigate foreign imports](https://github.com/dearlordylord/bend-idea/issues/35) | [#17](https://github.com/dearlordylord/bend-idea/issues/17) | Bend workflows |
| [35: Move and rename source files](https://github.com/dearlordylord/bend-idea/issues/36) | [#23](https://github.com/dearlordylord/bend-idea/issues/23), [#35](https://github.com/dearlordylord/bend-idea/issues/35) | Bend workflows |
| [36: Import a selected symbol explicitly](https://github.com/dearlordylord/bend-idea/issues/37) | [#24](https://github.com/dearlordylord/bend-idea/issues/24) | Bend workflows |
| [37: Inspect static dependencies](https://github.com/dearlordylord/bend-idea/issues/38) | [#19](https://github.com/dearlordylord/bend-idea/issues/19), [#24](https://github.com/dearlordylord/bend-idea/issues/24) | Bend workflows |
| [38: Create conventional files from templates](https://github.com/dearlordylord/bend-idea/issues/39) | [#4](https://github.com/dearlordylord/bend-idea/issues/4), [#30](https://github.com/dearlordylord/bend-idea/issues/30) | Bend workflows |
| [39: Spellcheck comments and strings](https://github.com/dearlordylord/bend-idea/issues/40) | [#3](https://github.com/dearlordylord/bend-idea/issues/3) | Bend workflows |
| [40: Show structured compiler diagnostics](https://github.com/dearlordylord/bend-idea/issues/41) | [#13](https://github.com/dearlordylord/bend-idea/issues/13) | Semantic assistance |
| [41: Inspect compiler-provided goals and context](https://github.com/dearlordylord/bend-idea/issues/42) | [#29](https://github.com/dearlordylord/bend-idea/issues/29), [#41](https://github.com/dearlordylord/bend-idea/issues/41) | Semantic assistance |
| [42: Inspect actual expression types](https://github.com/dearlordylord/bend-idea/issues/43) | [#18](https://github.com/dearlordylord/bend-idea/issues/18), [#41](https://github.com/dearlordylord/bend-idea/issues/41) | Semantic assistance |
| [43: Complete against an expected type](https://github.com/dearlordylord/bend-idea/issues/44) | [#10](https://github.com/dearlordylord/bend-idea/issues/10), [#42](https://github.com/dearlordylord/bend-idea/issues/42), [#43](https://github.com/dearlordylord/bend-idea/issues/43) | Semantic assistance |
| [44: Explain resource availability](https://github.com/dearlordylord/bend-idea/issues/45) | [#42](https://github.com/dearlordylord/bend-idea/issues/42), [#43](https://github.com/dearlordylord/bend-idea/issues/43) | Semantic assistance |
| [45: Try simple proof edits](https://github.com/dearlordylord/bend-idea/issues/46) | [#30](https://github.com/dearlordylord/bend-idea/issues/30), [#42](https://github.com/dearlordylord/bend-idea/issues/42) | Semantic assistance |
| [46: Normalize an expression explicitly](https://github.com/dearlordylord/bend-idea/issues/47) | [#41](https://github.com/dearlordylord/bend-idea/issues/41), [#43](https://github.com/dearlordylord/bend-idea/issues/43) | Semantic assistance |
