# Manual test guide: issues #34–#35

This guide covers the features in [PR #60](https://github.com/dearlordylord/bend-idea/pull/60).

- Worktree: `/Users/firfi/work/formal-proofs/bend-idea-p4`
- Branch: `codex/implement-through-p4`

For concrete project examples, open `/Users/firfi/work/bend/dnd` as the project.
Treat that project as read-only. All files under its `bend-idea/` directory are
disposable manual-test fixtures; perform edits, refactors, generated fills, and
build output only there. Paths in the table are relative to `/Users/firfi/work/bend/dnd`.

## Prepare the IDE

1. Check out the branch above and launch the plugin sandbox with `./gradlew runIde` using JDK 21.
2. In the sandbox, open a project containing `.bend` files. Editing features work without a Bend installation.
3. For checking, running, and building, open **Settings → Languages & Frameworks → Bend** and configure the Bend executable and Base source. The page detects the compiler version in the background; use **Detect** after changing the executable. Proof-root checks require a compiler that supports `--check-only`.
4. Native output requires the Bend compiler's native backend and `clang`; C and JavaScript source emission do not require that backend.

## Feature checklist

| Issue                                                       | Feature | Files to open/check | Manual test | Feedback |
|-------------------------------------------------------------|---|---|---|---|
| [#34](https://github.com/dearlordylord/bend-idea/issues/34) | Build and inspect generated output | Source: `bend-idea/run/main.bend`; output directory: `bend-idea/generated/` | Add **Bend Build**, use its browse button to select the fixture root, and set output path under `bend-idea/generated/`. Try C or JavaScript output, then open the generated source from the console link. Native output requires the compiler's native backend and `clang`. |
| [#35](https://github.com/dearlordylord/bend-idea/issues/35) | Complete and navigate foreign imports | `bend-idea/foreign/main.bend`; targets: `bend-idea/foreign/ffi/native.c` and `bend-idea/foreign/ffi/native.js` | Keep the foreign definition's direct `IO(Nat)` return and import-only body. Shorten a quoted import to `import "./ffi/"`, invoke completion, and choose an asset. Use Go to Declaration (`⌘B` / `Ctrl+B`, or Ctrl-click) on the path. |

## Follow-up UI regression checks

- [ ] Confirm the Bend settings page appears under **Settings → Languages & Frameworks → Bend**.
- [ ] Confirm the settings page detects and displays the Bend compiler version without blocking the UI; **Detect** refreshes the result after changing the executable.
- [ ] Confirm Bend actions appear under **Tools → Bend**. Open Tools with `bend-idea/signatures.bend` active and from a tool window; opening **New → Bend Module** on `bend-idea/templates-output/` should not produce `virtualFile`/`psi.File` EDT access errors.
- [ ] In `bend-idea/proofs/LAWS.bend`, place the caret on a law and confirm **Option+Enter** (macOS) / **Alt+Enter** (Windows/Linux) offers **Generate Bend Law Fill**.
- [ ] Right-click inside a `.bend` editor and confirm **Bend → Check Current Bend File**, **Next Bend Proof Hole**, and **Previous Bend Proof Hole** appear.
