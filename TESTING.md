# Manual test guide: issues #33–#40

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
4. For spelling, enable the IDE's optional **Spellchecker** plugin. For native output, the Bend compiler's native backend and `clang` are needed; C and JavaScript source emission do not require that backend.

## Feature checklist

| Issue                                                       | Feature | Files to open/check | Manual test | Feedback |
|-------------------------------------------------------------|---|---|---|---|
| [#33](https://github.com/dearlordylord/bend-idea/issues/33) | Run Bend explicitly | `bend-idea/run/main.bend`; larger real demo: `battle.bend` | Open **Run → Edit Configurations** and add **Bend Run**. Use the browse button to select `bend-idea/run/main.bend`; the root field should not accept manual typing. Run it and inspect the Run console output. |
| [#34](https://github.com/dearlordylord/bend-idea/issues/34) | Build and inspect generated output | Source: `bend-idea/run/main.bend`; output directory: `bend-idea/generated/` | Add **Bend Build**, use its browse button to select the fixture root, and set output path under `bend-idea/generated/`. Try C or JavaScript output, then open the generated source from the console link. Native output requires the compiler's native backend and `clang`. |
| [#35](https://github.com/dearlordylord/bend-idea/issues/35) | Complete and navigate foreign imports | `bend-idea/foreign/main.bend`; targets: `bend-idea/foreign/ffi/native.c` and `bend-idea/foreign/ffi/native.js` | In the fixture source, shorten a quoted import to `import "ffi/"`, invoke completion, and choose an asset. Use Go to Declaration (`⌘B` / `Ctrl+B`, or Ctrl-click) on the path. |
| [#36](https://github.com/dearlordylord/bend-idea/issues/36) | Move and rename source files | `bend-idea/refactor/app/main.bend`; target: `bend-idea/refactor/library/dep_for_move.bend`; foreign targets: `bend-idea/refactor/ffi/native.c` and `bend-idea/refactor/ffi/native.js` | Use **Refactor → Move** or **Rename** from the project tree; inspect the preview, apply it, and verify module and foreign paths update. Undo and check that the original paths return. Keep all changes inside `bend-idea/refactor/`. |
| [#37](https://github.com/dearlordylord/bend-idea/issues/37) | Import a selected symbol explicitly | `bend-idea/imports/main.bend` (`bend_idea_target()` unresolved); candidate: `bend-idea/imports/library.bend` | Put the caret on the unresolved call and use the context-action shortcut (**Option+Enter** on macOS, **Alt+Enter** on Windows/Linux). Choose **Import Bend symbol**; it should add the import directly for one candidate or offer a chooser for several. Undo should restore the fixture. The **Tools → Bend → Import Bend Symbol at Caret** command remains available too. | btw generally, EACH file has warning at first symbol: "This Bend compiler does not document --check-only; no source was checked.".  now, to the 37: it should be tool. in TS I usually have hotkeys like cmd+enter or something like that. before I use hotkey, editor highlights the non-defined thing as error. after I use hotkey, there's usually a choice where import from OR just import happens if no choice. I think it works like that, you should check. it's not only ts but any language I used. |
| [#38](https://github.com/dearlordylord/bend-idea/issues/38) | Inspect static dependencies | `battle/reducer/attack.bend` → `battle/attack.bend`; unresolved call: `bend-idea/imports/main.bend` | Put the caret on a declaration and run **Tools → Inspect Bend Dependencies**. Check incoming/outgoing calls and imports; use the unresolved fixture call to check that unknown dependencies are reported. Select a row to navigate. | Inspect bend dependencies simply hanged whole IDE |
| [#39](https://github.com/dearlordylord/bend-idea/issues/39) | Create files from templates | Destination: `bend-idea/templates-output/` | Right-click that directory and choose **New → Bend Module**, then **New → Bend Law and Proof Pair**. Check generated files and caret placement in `?TODO`. Repeat to verify filename collisions are reported without overwriting. | works but the options have no icon. must have bend icons |
| [#40](https://github.com/dearlordylord/bend-idea/issues/40) | Spellcheck comments and strings | `bend-idea/spellcheck.bend` (`commment`, `teh`, `strng`) | With the IDE Spellchecker plugin enabled, check the comment and ordinary string for spelling highlights and use **Alt+Enter** for a correction. Identifiers and foreign asset paths should not be flagged. | NO RESULT |

## Follow-up UI regression checks

- [ ] Confirm the Bend settings page appears under **Settings → Languages & Frameworks → Bend**.
- [ ] Confirm the settings page detects and displays the Bend compiler version without blocking the UI; **Detect** refreshes the result after changing the executable.
- [ ] Confirm Bend actions appear under **Tools → Bend**. Open Tools with `bend-idea/signatures.bend` active and from a tool window; opening **New → Bend Module** on `bend-idea/templates-output/` should not produce `virtualFile`/`psi.File` EDT access errors.
- [ ] In `bend-idea/proofs/LAWS.bend`, place the caret on a law and confirm **Option+Enter** (macOS) / **Alt+Enter** (Windows/Linux) offers **Generate Bend Law Fill**.
- [ ] Right-click inside a `.bend` editor and confirm **Bend → Check Current Bend File**, **Next Bend Proof Hole**, and **Previous Bend Proof Hole** appear.
- [ ] Confirm the Run configuration choices are clearly named **Bend Run**, **Bend Build**, and **Bend Native**, and that Run/Build roots are selected with the `.bend` file chooser using `bend-idea/run/main.bend`.
