# Agda holes as a model for Bend proof progress

Researched 2026-09-26 from official Agda documentation. This note describes Agda's interaction model and draws product inferences for Bend IDEA. It does **not** assume Bend has Agda's type-directed hole API, proof search, or the same compiler guarantees.

## Agda's documented workflow

1. Start with a property and an incomplete proof clause containing `?`. Load the file. Agda type-checks it and converts question marks to numbered goals; the editor shows the list of remaining goals. The [official proof tutorial](https://agda.readthedocs.io/en/v2.6.4/getting-started/a-taste-of-agda.html#holes-and-case-splitting) walks through this exact sequence.
2. Put the cursor inside one goal and ask for its **expected type and local context**. These are the facts needed to decide the next proof step, rather than an inventory of all definitions in the module. The [tutorial](https://agda.readthedocs.io/en/v2.6.4/getting-started/a-taste-of-agda.html#holes-and-case-splitting) and [Emacs mode command reference](https://agda.readthedocs.io/en/latest/tools/emacs-mode.html#commands-in-context-of-a-goal) document this interaction.
3. Work on that goal in place: case split a variable, refine a partial term (which may create subgoals), or give a complete term. Agda also offers type-directed Auto at a hole, with any proposed solution checked by Agda. See the [tutorial's worked proof](https://agda.readthedocs.io/en/v2.6.4/getting-started/a-taste-of-agda.html#holes-and-case-splitting), [goal commands](https://agda.readthedocs.io/en/latest/tools/emacs-mode.html#commands-in-context-of-a-goal), and [Auto documentation](https://agda.readthedocs.io/en/v2.6.4.1/tools/auto.html#usage).
4. Move to the next or previous goal, show all goals when orientation is needed, and reload after editing. The example ends with “All Done” after the last goal is filled and the file is reloaded. See the [global navigation commands](https://agda.readthedocs.io/en/latest/tools/emacs-mode.html#global-commands) and [tutorial conclusion](https://agda.readthedocs.io/en/v2.6.4/getting-started/a-taste-of-agda.html#holes-and-case-splitting).

Agda keeps source-wide definition browsing separate from goal interaction: its command reference lists “Display contents of the given module” and “Search Definitions in Scope” as distinct global commands, while give/refine/case split/type/context operate inside a hole. [Agda Emacs mode command reference](https://agda.readthedocs.io/en/latest/tools/emacs-mode.html#global-commands).

## Product inference for Bend IDEA

The central use case for a **Proof Progress** view should be: choose the proof root, see what remains actionable, jump to one unfinished hole or unfilled law, make a source edit, and see the root's check result and remaining work update. Show the root and check freshness prominently, because source presence alone does not establish a successful proof. A compact law summary can answer “which obligations have candidate fills?”; a long flat list of every law and candidate definition gives little guidance about the next action.

The Agda model suggests ranking **unfinished holes and unresolved or failing obligations** ahead of completed or merely present declarations, grouping related law and candidate fill, and showing full paths only on demand. If Bend cannot report a goal type, local context, or a compiler-confirmed per-law result, the UI should state that limit and avoid presenting source matches as verified progress. Agda's proof commands are a design reference, not evidence that Bend supports those commands.

### Proposed Bend flow with today's capabilities

1. Select the proof root. Keep its path and latest check state visible at the top, with **Check root** beside them. Say when the result is stale, unavailable, incomplete, or failed.
2. Show **Work to do** first: source holes and laws for which this root has no source-matched candidate fill. Summarize counts, but do not call them verified or compute a proof-completion percentage.
3. Clicking a hole opens it at the caret. Clicking an unfilled law opens that declaration and offers the existing **Generate Bend Law Fill** action. Keep next/previous hole navigation in the editor.
4. After an edit, refresh the source inventory and recheck the root. The compiler's root result is the authority for checking; source matching alone only says a candidate exists.
5. Put laws with candidate fills in a collapsed **Source inventory** section, with each law and its fills together. Show full file paths in a tooltip or detail pane. If the root check fails, direct the user to diagnostics rather than painting individual fills as failed without per-law evidence.

A future compiler goal/context capability could add the expected type and local binders for the selected hole, plus checked refinement actions. The current Bend integration does not support Agda-style goal inspection or Auto, so those should not appear as working commands yet.

## Sources

- [Agda, “A Taste of Agda”: holes, case splitting, worked proof](https://agda.readthedocs.io/en/v2.6.4/getting-started/a-taste-of-agda.html#holes-and-case-splitting)
- [Agda, Emacs Mode: global and goal commands](https://agda.readthedocs.io/en/latest/tools/emacs-mode.html)
- [Agda, Automatic Proof Search: usage and checked solutions](https://agda.readthedocs.io/en/v2.6.4.1/tools/auto.html)
