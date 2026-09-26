# Manual test guide: follow-up UI checks

The numbered feature checklist for [PR #60](https://github.com/dearlordylord/bend-idea/pull/60) is complete. The UI regression checks below remain open.

- Worktree: `/Users/firfi/work/formal-proofs/bend-idea-p4`
- Branch: `codex/implement-through-p4`

For concrete project examples, open `/Users/firfi/work/bend/dnd` as the project.
Treat that project as read-only. All files under its `bend-idea/` directory are
disposable manual-test fixtures; perform edits, refactors, generated fills, and
build output only there. Paths below are relative to `/Users/firfi/work/bend/dnd`.

## Prepare the IDE

1. Check out the branch above and launch the plugin sandbox with `./gradlew runIde` using JDK 21.
2. In the sandbox, open a project containing `.bend` files. Editing features work without a Bend installation.
3. Open **Settings → Languages & Frameworks → Bend** and configure the Bend executable and Base source for the checks below.

## Follow-up UI regression checks

- [ ] Confirm the Bend settings page appears under **Settings → Languages & Frameworks → Bend**.
- [ ] Confirm the settings page detects and displays the Bend compiler version without blocking the UI; **Detect** refreshes the result after changing the executable.
- [ ] Confirm Bend actions appear under **Tools → Bend**. Open Tools with `bend-idea/signatures.bend` active and from a tool window; opening **New → Bend Module** on `bend-idea/templates-output/` should not produce `virtualFile`/`psi.File` EDT access errors.
- [ ] In `bend-idea/proofs/LAWS.bend`, place the caret on a law and confirm **Option+Enter** (macOS) / **Alt+Enter** (Windows/Linux) offers **Generate Bend Law Fill**.
- [ ] Right-click inside a `.bend` editor and confirm **Bend → Check Current Bend File**, **Next Bend Proof Hole**, and **Previous Bend Proof Hole** appear.
