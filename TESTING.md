# Manual test guide: issues #28–#40

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
3. For checking, running, and building, open **Settings → Languages & Frameworks → Bend** and configure the Bend executable and Base source. Proof-root checks require a compiler that supports `--check-only`.
4. For spelling, enable the IDE's optional **Spellchecker** plugin. For native output, the Bend compiler's native backend and `clang` are needed; C and JavaScript source emission do not require that backend.

## Feature checklist

| Issue | Feature | Files to open/check | Manual test |
|---|---|---|---|
| [#28](https://github.com/dearlordylord/bend-idea/issues/28) | Select and check proof roots | `PROOF.bend` (full read-only root); `bend-idea/proofs/PROOF.bend` (small writable root); `bend-idea/proofs/LAWS.bend` | Run **Tools → Bend → Check Bend Proof Root** and select each root in turn. Check the root in the notification/status. With the compiler unconfigured, select the small fixture root and confirm the notification explains why checking is unavailable. |
| [#29](https://github.com/dearlordylord/bend-idea/issues/29) | Navigate laws, proofs, and holes | `LAWS.bend` → `laws/core.bend`; `PROOF.bend` → `proofs/core.bend` → `laws/core.bend`; hole fixture: `bend-idea/proofs/holes.bend` (imported by fixture `PROOF.bend`) | Select only the full-project `PROOF.bend` root. Click the gutter links for `d20_all_to_nat` from both `laws/core.bend` and `proofs/core.bend` (`L.d20_all_to_nat`). Confirm the nested aliases resolve promptly in both directions. Use **Next/Previous Bend Proof Hole** in `holes.bend`; it has named and TODO holes plus comment/string lookalikes that should be skipped. |
| [#30](https://github.com/dearlordylord/bend-idea/issues/30) | Generate a law implementation skeleton | `bend-idea/proofs/LAWS.bend` (`self_equal`, with no fill); destination/root: `bend-idea/proofs/PROOF.bend` | Open **Tools → Bend** with no law selected; **Generate Bend Law Fill** should remain visible but disabled with a usage hint. Put the caret on `self_equal`, choose the action and fixture root, then check the generated parameters and explicit `?TODO` in the writable proof fixture. |
| [#31](https://github.com/dearlordylord/bend-idea/issues/31) | Generate match cases | `bend-idea/match/Maybe.bend`; `bend-idea/match/main.bend` (currently missing the `Some` branch) | Put the caret in `inspect`'s match and choose **Tools → Generate Match Cases**. Check that the missing constructor is added without duplicating `None`, with an unfinished body marked `?TODO`. |
| [#32](https://github.com/dearlordylord/bend-idea/issues/32) | Browse proof progress | `bend-idea/proofs/PROOF.bend`, `bend-idea/proofs/LAWS.bend`, and imported `bend-idea/proofs/holes.bend`; full-project alternative: `PROOF.bend` and `proofs/core.bend` | Select the small root with **Check Bend Proof Root**, then open **View → Tool Windows → Bend Proof Progress**. Check that the panel lists its law, missing fill, and holes from `holes.bend`; click entries to navigate to their source. Use the full root for a larger progress view. |
| [#33](https://github.com/dearlordylord/bend-idea/issues/33) | Run Bend explicitly | `bend-idea/run/main.bend`; larger real demo: `battle.bend` | Open **Run → Edit Configurations** and add **Bend Run**. Use the browse button to select `bend-idea/run/main.bend`; the root field should not accept manual typing. Run it and inspect the Run console output. |
| [#34](https://github.com/dearlordylord/bend-idea/issues/34) | Build and inspect generated output | Source: `bend-idea/run/main.bend`; output directory: `bend-idea/generated/` | Add **Bend Build**, use its browse button to select the fixture root, and set output path under `bend-idea/generated/`. Try C or JavaScript output, then open the generated source from the console link. Native output requires the compiler's native backend and `clang`. |
| [#35](https://github.com/dearlordylord/bend-idea/issues/35) | Complete and navigate foreign imports | `bend-idea/foreign/main.bend`; targets: `bend-idea/foreign/ffi/native.c` and `bend-idea/foreign/ffi/native.js` | In the fixture source, shorten a quoted import to `import "ffi/"`, invoke completion, and choose an asset. Use Go to Declaration (`⌘B` / `Ctrl+B`, or Ctrl-click) on the path. |
| [#36](https://github.com/dearlordylord/bend-idea/issues/36) | Move and rename source files | `bend-idea/refactor/app/main.bend`; target: `bend-idea/refactor/library/dep_for_move.bend`; foreign targets: `bend-idea/refactor/ffi/native.c` and `bend-idea/refactor/ffi/native.js` | Use **Refactor → Move** or **Rename** from the project tree; inspect the preview, apply it, and verify module and foreign paths update. Undo and check that the original paths return. Keep all changes inside `bend-idea/refactor/`. |
| [#37](https://github.com/dearlordylord/bend-idea/issues/37) | Import a selected symbol explicitly | `bend-idea/imports/main.bend` (`bend_idea_target()` unresolved); candidate: `bend-idea/imports/library.bend` | Put the caret on the unresolved call and run **Tools → Import Bend Symbol at Caret**. Select the candidate if asked; check that an import is added and the use is qualified. Undo should restore the fixture. |
| [#38](https://github.com/dearlordylord/bend-idea/issues/38) | Inspect static dependencies | `battle/reducer/attack.bend` → `battle/attack.bend`; unresolved call: `bend-idea/imports/main.bend` | Put the caret on a declaration and run **Tools → Inspect Bend Dependencies**. Check incoming/outgoing calls and imports; use the unresolved fixture call to check that unknown dependencies are reported. Select a row to navigate. |
| [#39](https://github.com/dearlordylord/bend-idea/issues/39) | Create files from templates | Destination: `bend-idea/templates-output/` | Right-click that directory and choose **New → Bend Module**, then **New → Bend Law and Proof Pair**. Check generated files and caret placement in `?TODO`. Repeat to verify filename collisions are reported without overwriting. |
| [#40](https://github.com/dearlordylord/bend-idea/issues/40) | Spellcheck comments and strings | `bend-idea/spellcheck.bend` (`commment`, `teh`, `strng`) | With the IDE Spellchecker plugin enabled, check the comment and ordinary string for spelling highlights and use **Alt+Enter** for a correction. Identifiers and foreign asset paths should not be flagged. |

## Follow-up UI regression checks

- [ ] Confirm the Bend settings page appears under **Settings → Languages & Frameworks → Bend**.
- [ ] Confirm Bend actions appear under **Tools → Bend**. Open Tools with `bend-idea/signatures.bend` active and from a tool window; opening **New → Bend Module** on `bend-idea/templates-output/` should not produce `virtualFile`/`psi.File` EDT access errors.
- [ ] Confirm the Run configuration choices are clearly named **Bend Run**, **Bend Build**, and **Bend Native**, and that Run/Build roots are selected with the `.bend` file chooser using `bend-idea/run/main.bend`.
