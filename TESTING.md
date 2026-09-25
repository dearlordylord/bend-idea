# Manual test guide: issues #25–#40

This guide covers the features in [PR #60](https://github.com/dearlordylord/bend-idea/pull/60).

- Worktree: `/Users/firfi/work/formal-proofs/bend-idea-p4`
- Branch: `codex/implement-through-p4`

## Prepare the IDE

1. Check out the branch above and launch the plugin sandbox with `./gradlew runIde` using JDK 21.
2. In the sandbox, open a project containing `.bend` files. Editing features work without a Bend installation.
3. For checking, running, and building, open **Settings → Languages & Frameworks → Bend** and configure the Bend executable and Base source. Proof-root checks require a compiler that supports `--check-only`.
4. For spelling, enable the IDE's optional **Spellchecker** plugin. For native output, the Bend compiler's native backend and `clang` are needed; C and JavaScript source emission do not require that backend.

## Feature checklist

| Issue | Feature | Manual test |
|---|---|---|
| [#25](https://github.com/dearlordylord/bend-idea/issues/25) | Parameter information | Define `combine(left: Nat, right: Nat)` and call `combine(1, 2)`. Put the caret in an argument and invoke **Parameter Info** (`⌘P` on macOS, `Ctrl+P` on Windows/Linux). Check that the popup shows the signature and tracks the active argument. Edit the signature without saving and confirm the popup refreshes. |
| [#26](https://github.com/dearlordylord/bend-idea/issues/26) | Semantic reading and selection | Open a file with a function, local, datatype, constructor, law, and import alias. Check the semantic colors. Enable breadcrumbs if hidden, then place the caret inside nested `match`/`case`/`do` blocks and check the breadcrumb trail. Use **Extend Selection** (`⌥↑` on macOS, `Ctrl+W` on Windows/Linux) to expand from a token to its call, block, or declaration. |
| [#27](https://github.com/dearlordylord/bend-idea/issues/27) | Optional parameter-name hints | In **Settings → Editor → Inlay Hints → Bend**, enable **Show Bend parameter names**. Call a function with non-obvious arguments, such as `combine(1, 2)`. Check that parameter names appear inline; turn the option off and confirm they disappear. |
| [#28](https://github.com/dearlordylord/bend-idea/issues/28) | Select and check proof roots | Create two `PROOF.bend` files. Run **Tools → Bend → Check Bend Proof Root**, choose one, then choose the other. Check the notification/status for each selected root. Also select a root when checking is unavailable and confirm the notification explains why. |
| [#29](https://github.com/dearlordylord/bend-idea/issues/29) | Navigate laws, proofs, and holes | Add `PROOF.bend` importing `LAWS.bend` and `proofs/core.bend`; have `LAWS.bend` import `laws/core.bend as Core` and `proofs/core.bend` import `../laws/core.bend as L` with `def L.claim(): ?TODO`. Click the gutter links beside `claim` in both law and proof files. Confirm navigation resolves to the nested counterpart and does not freeze the editor while searching. Use **Tools → Bend → Next Bend Proof Hole** / **Previous Bend Proof Hole** to navigate holes; strings and comments should be skipped. |
| [#30](https://github.com/dearlordylord/bend-idea/issues/30) | Generate a law implementation skeleton | Open **Tools → Bend** with no law selected; **Generate Bend Law Fill** should remain visible but disabled with a usage hint. Put the caret on a law and choose it. If prompted, choose a proof root. Check that the fill is inserted there with its parameters and an explicit `?TODO`; it should remain incomplete until you replace the hole. |
| [#31](https://github.com/dearlordylord/bend-idea/issues/31) | Generate match cases | Define a datatype with multiple constructors, then write a `match` on a binder whose datatype is known. Put the caret in the match and choose **Tools → Generate Match Cases**. Check that missing constructors are added without duplicating existing cases, with unfinished bodies marked `?TODO`. |
| [#32](https://github.com/dearlordylord/bend-idea/issues/32) | Browse proof progress | Select a proof root with **Check Bend Proof Root**, then open **View → Tool Windows → Bend Proof Progress**. Check that the panel lists laws, candidate fills, and holes for that root. Click an entry to navigate to its source. |
| [#33](https://github.com/dearlordylord/bend-idea/issues/33) | Run Bend explicitly | Open **Run → Edit Configurations** and add **Bend Run**. Use the browse button to select a `.bend` root; the root field should not accept manual typing. Set the compiler executable, working directory, arguments, and environment as needed. Run it and inspect the Run console output. |
| [#34](https://github.com/dearlordylord/bend-idea/issues/34) | Build and inspect generated output | Add **Bend Build**, use its browse button to select a root, then set output path and kind. Try C or JavaScript output first; run the build, then open the generated source from the console link. Native output requires the compiler's native backend and `clang`. |
| [#35](https://github.com/dearlordylord/bend-idea/issues/35) | Complete and navigate foreign imports | Place `ffi/one.c` or `ffi/two.js` in the project and type a quoted import inside a function body, such as `import "ffi/"`. Invoke completion and choose a file. Use Go to Declaration (`⌘B` / `Ctrl+B`, or Ctrl-click) on the path to open the asset. |
| [#36](https://github.com/dearlordylord/bend-idea/issues/36) | Move and rename source files | Add project sources that import a `.bend` module and a `.c` or `.js` file by relative path. Use **Refactor → Move** or **Rename** from the project tree; inspect the preview, apply it, and verify the paths update. Undo and check that the original paths return. |
| [#37](https://github.com/dearlordylord/bend-idea/issues/37) | Import a selected symbol explicitly | Put the caret on an unresolved name such as `choose()` where a project module defines `choose`. Run **Tools → Import Bend Symbol at Caret** and select the candidate if asked. Check that an import is added and the use is qualified; undo should restore the source. |
| [#38](https://github.com/dearlordylord/bend-idea/issues/38) | Inspect static dependencies | Create a helper and a caller that imports and calls it. Put the caret on either declaration and run **Tools → Inspect Bend Dependencies**. Check incoming/outgoing calls and imports; add an unresolved call to see it reported as unknown. Select a row to navigate. |
| [#39](https://github.com/dearlordylord/bend-idea/issues/39) | Create files from templates | Right-click a project directory and choose **New → Bend Module**, then **New → Bend Law and Proof Pair**. Check the generated files and caret placement in the pair's `?TODO`. Run the pair action again to verify it reports the filename collision instead of overwriting files. |
| [#40](https://github.com/dearlordylord/bend-idea/issues/40) | Spellcheck comments and strings | With the IDE Spellchecker plugin enabled, put a misspelled word in a comment and ordinary string text. Check for a spelling highlight and use **Alt+Enter** for a correction. Identifiers and foreign asset paths should not be flagged. |

## Follow-up UI regression checks

- [ ] Confirm the Bend settings page appears under **Settings → Languages & Frameworks → Bend**.
- [ ] Confirm Bend actions appear under **Tools → Bend**. Open Tools from the editor and a tool window; opening the menu or **New → Bend Module** should not produce `virtualFile`/`psi.File` EDT access errors.
- [ ] Confirm the Run configuration choices are clearly named **Bend Run**, **Bend Build**, and **Bend Native**, and that Run/Build roots are selected with the `.bend` file chooser.
