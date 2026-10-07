# Linter integration options for Bend IDEA

Research date: 2026-10-07. Planning evidence only; no integration was implemented or exercised in an IDE. Primary sources were inspected, including source checkouts at the revisions below. Original comparison prose; no upstream code or fixtures copied. Related: [bend-lint assessment](bend-lint-architecture-and-features.md).

**Subsequent user decision:** prototype native support inside Bend IDEA, rather than deliver a user-configured watcher. The generic routes below remain comparison evidence. The current implementation is an explicit native root action over the existing snapshot/check owner, with editor diagnostics and optional compiler observations; see the [prototype setup](../README.md#experimental-bend-lint-integration-prototype-branch). Watcher-first recommendations below describe the earlier planning alternative.

## Do we need native support?

Not just to launch bend-lint. The user has Ultimate and accepts File Watchers, so first test a watcher against a saved, explicit proof root. Native integration earns its cost only if users need unsaved dependency snapshots, trustworthy stale-result rejection or structured fixes.

| Route | What it provides | Limit / decision |
|---|---|---|
| EditorConfig | File-scoped code-style configuration for consumers that implement its properties. | No documented arbitrary linter launcher or diagnostic protocol. It can configure our formatter; it does not itself connect bend-lint. [IDEA docs](https://www.jetbrains.com/help/idea/editorconfig.html) |
| External Tools | Manual action/shortcut, executable/arguments/cwd, console; regex filters turn file/line/column output into navigation links. | Adequate first prototype for saved sources. Filters require absolute paths and positions on the same line. Generic JSON decoding, per-rule intentions and immutable root snapshots are not supplied by this documented mechanism. [Actions](https://www.jetbrains.com/help/idea/configuring-third-party-tools.html), [settings](https://www.jetbrains.com/help/idea/settings-tools-external-tools.html) |
| File Watchers | Change/save triggers; output filters plus **File Watcher Problems** can show messages directly in the editor. | IDEA documentation requires Ultimate. “Auto-save edited files” saves to trigger execution; disabling it moves execution to save/frame deactivation. Bend dependency roots are not among its documented dependency-tracking integrations. [Docs](https://www.jetbrains.com/help/idea/using-file-watchers.html) |
| Native optional adapter | Can use Bend IDEA's captured roots, freshness checks and source maps; native rule diagnostics and eventual guarded edits. | Requires implementation and compiler/linter compatibility support. Choose after the generic routes demonstrate a concrete missing workflow. This is our recommendation, not an upstream fact. |

A watcher is therefore **not merely a console runner**. Conversely, its line-based highlighting does not establish snapshot correctness, rule-specific fixes or source-graph knowledge. Do not assume any generic route supplies an atomic IDE undo command for an external `--fix` disk rewrite; that behavior was not verified.

## Three relevant language integrations

| Integration | Scheduling and configuration | Data and edits | Useful lesson |
|---|---|---|---|
| JetBrains ESLint | Editor feedback while typing; opt-in fix on save; automatic project-local package/config discovery, separate processes for multiple package roots; manual package/cwd/config overrides. | Configured severities, Problems integration, individual and current-file fixes. Transport, cancellation and undo internals were not established by the product docs. | Select the tool/config per relevant root; provide explicit controls for checking versus fixing. [Official docs](https://www.jetbrains.com/help/webstorm/eslint.html) |
| Ruff third-party IntelliJ plugin, native CLI path | External annotator integrates with inspection enable/severity settings; configured/global/project executable support, manual and save fixes. Current plugin also offers LSP; its README recommends built-in Ruff on PyCharm 2025.3+. | Captures PSI text, sends stdin with `--stdin-filename`, parses JSON into ranges and quick fixes, applies fixes through document APIs. | A CLI can support native UX without adopting an LSP. Unsaved-buffer support relies on a real tool input contract; do not infer bend-lint accepts stdin. [Annotator][ruff-annotator], [transport][ruff-cli], [fix][ruff-fix], [README][ruff-readme] |
| RustRover Clippy / historical IntelliJ Rust | Current RustRover offers manual or on-the-fly Cargo Check/Clippy plus extra arguments and status widget. | Results enter Problems. Historical open-source implementation saves all documents before checking; it keys cached requests by toolchain/cwd/arguments, invalidates on PSI modifications, checks cancellation and passes a disposable owner to Cargo execution. | Compiler-backed linters have whole-project and lifecycle constraints. Saving before analysis is one design, not evidence of an unsaved snapshot protocol. [Current docs](https://www.jetbrains.com/help/rust/rust-external-linters.html), [historical runner][rust-runner] |

The Rust source is commit `c6657c02bb62075bf7b7ceb84d000f93dda34dc1` (2024-01-12), from an [officially deprecated project][rust-readme]; it is **not** evidence of current RustRover internals. Ruff source is `3ec4c1890fca7f20e1a038f10a86a985d12ba571`. These source inspections do not establish complete process-tree termination, all cancellation races or undo behavior; those need tests for whichever Bend implementation is chosen.

## Recommended experiment and decision gate

1. **Saved-source baseline:** configure File Watchers for an explicit root and pinned bend-lint/runtime/compiler in the prototype worktree. Verify actual arguments, cwd/config lookup and exit semantics. Start read-only. Check whether text output supports IDEA filters; if not, evaluate a small standalone JSON-to-absolute-path/line/column/message bridge before plugin code. Explicitly select rule files; do not assume CLI defaults enable rules. Retain External Tools as a manual alternative.
2. **Watcher validation:** verify the bridge can produce editor messages. Start with auto-save-on-edit disabled; test save/frame-deactivation triggering and whether a normal lint finding disables the watcher or merely displays output. Distinguish findings, compiler/check failure and invocation/tool failure in the bridge. Do not advertise untested behavior. Keep root selection explicit; a generic per-file watcher is not our proof-root graph scheduler.
3. **Evaluate user value:** run a useful rule beyond formatting on a representative project. If manual/on-save diagnostics are sufficient, ship instructions or the bridge and stop. Native support is justified by a demonstrated requirement for unsaved imported files, source-mapped ranges/fixes, freshness guarantees or IDEA Community availability.
4. **Only if justified, prototype native read-only support:** an explicit root action, existing shared snapshot capture, isolated bounded process, structured lint findings and current-only publication. Distinguish unavailable/failed/incomplete lint runs from zero findings. Keep proof checking status separate. Settings should make runtime, bend-lint, compiler and config identities visible. Measure latency before adding background scheduling.
5. **Later fixes:** validate returned edits against captured sources; apply through the IDE command/undo owner. Preview, unsafe-fix policy and conflicts precede fix-all or on-save rewriting. Reuse our owners rather than importing another plugin's architecture.

Native ownership follows [ARCHITECTURE.md](../ARCHITECTURE.md): analysis owns immutable requests/results and freshness policy; adapters own source capture, transport and processes; inspections/checking/settings consume APIs. Applicable rules: A2–A4 (ownership and pure policy), A5/A8 (root provenance and current buffers), A6 (bounded/offline lifecycle), A7 (honest availability), A9 (future edits). No new shared framework or architecture exception is proposed by this research.

## Validation and remaining gaps

Documentation self-review against [REVIEWER.md](../REVIEWER.md): claims distinguish product docs, inspected source and recommendations; no plugin behavior or contract changed. No IDE fixtures, upstream suites or Gradle checks were run for this research. Generic output filters, watcher severity/exit behavior, cancellation, compatibility and concrete bend-lint configuration remain prototype validation tasks.

[ruff-annotator]: https://github.com/koxudaxi/ruff-pycharm-plugin/blob/3ec4c1890fca7f20e1a038f10a86a985d12ba571/src/com/koxudaxi/ruff/RuffExternalAnnotator.kt
[ruff-cli]: https://github.com/koxudaxi/ruff-pycharm-plugin/blob/3ec4c1890fca7f20e1a038f10a86a985d12ba571/src/com/koxudaxi/ruff/Ruff.kt
[ruff-fix]: https://github.com/koxudaxi/ruff-pycharm-plugin/blob/3ec4c1890fca7f20e1a038f10a86a985d12ba571/src/com/koxudaxi/ruff/RuffQuickFix.kt
[ruff-readme]: https://github.com/koxudaxi/ruff-pycharm-plugin/blob/3ec4c1890fca7f20e1a038f10a86a985d12ba571/README.md
[rust-runner]: https://github.com/intellij-rust/intellij-rust/blob/c6657c02bb62075bf7b7ceb84d000f93dda34dc1/src/main/kotlin/org/rust/ide/annotator/RsExternalLinterUtils.kt
[rust-readme]: https://github.com/intellij-rust/intellij-rust/blob/c6657c02bb62075bf7b7ceb84d000f93dda34dc1/README.md
