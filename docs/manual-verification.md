# PR changes — manual check

Open `../../bend/dnd/bend-idea` in IDEA, then `CHECKLIST.md`. Use **Find Action → Check Current Bend File**. Tick what works; a short note is enough for failures.

**Where to see the result:** after checking, look for **Bend: …** in IDEA's **bottom status bar**, usually on the right. Click that text to open details and compiler output. If the bar is hidden, enable **View → Appearance → Status Bar**. If the Bend widget is hidden, right-click the bar and enable **Bend check status**.

**Use the new plugin build containing commit `1631479`.** The previously installed build has no Bend status-bar widget. You can also open the same details through **Tools → Bend → Show Bend Check Status**.

This checklist covers only this PR's new compiler protocol and broader compatibility checks. #79/#76 are closed; their formatting workflow checks are removed.

## Versioned checking protocol

Use the pinned Bend 2.0.25 structured helper as your configured compiler, with matching Base. Setup is described in the plugin repository README under **Compiler checking**. Skip this section if that helper is not configured; a plain compiler does not exercise the new protocol.

All fixture paths below are inside `manual/`.

| Done | Action | Expected result |
|---|---|---|
| ☐ | Check `editor.bend` | Bottom status bar: **Bend: Check passed** |
| ☐ | Check `failed.bend` | Bottom status bar: **Bend: Check failed**; first error highlights the wrong string |
| ☐ | Check `incomplete.bend` | Bottom status bar: **Bend: Incomplete** |
| ☐ | Check `goals.bend` | Bottom status bar: **Bend: Incomplete** (the hole may also be red) |
| ☐ | Check `unsafe.bend` and `foreign.bend` | Bottom status bar: **Bend: Check passed · unsafe/foreign** |
| ☐ | Check `effects.bend` | No `MANUAL_RUN_MARKER` printed by program execution |
| ☐ | Change `library.bend` body from `42` to `"wrong"`; immediately check `editor.bend` without saving the dependency | Root check fails; error highlights the dependency's wrong string, not the import line; Undo afterward |

Protocol-version rejection, malformed-response handling, legacy fallback and cancellation are covered by automated tests; no manual setup is needed for those cases.

## Broader compiler compatibility

Select the newer Bend compiler you want to try and its matching Base. This exercises the expanded release-check corpus; it does **not** automatically extend official compiler/helper support.

| Done | Action | Expected result |
|---|---|---|
| ☐ | Check `manual/editor.bend` with its imported library | Complete; generated dependency filenames do not cause a compiler error |
| ☐ | Go to Declaration on `Lib.number()` and inspect the `Choice` match in `editor.bend` | Import navigation and datatype/match parsing work with this compiler's syntax |
| ☐ | Check `editor.bend`, Reformat Code, then check again | Same complete result; second reformat makes no further changes |
| ☐ | Check `incomplete.bend`, Reformat Code, then check again | Still incomplete |
| ☐ | Change library's `42` to `"wrong"`; check editor root immediately, then Undo | Dependency diagnostic appears with current source |
| ☐ | Check `failed.bend`, `goals.bend`, `unsafe.bend`, `foreign.bend` and `effects.bend` | Failure, incomplete hole, reliance warnings and no main execution remain distinct |

Restore the compiler/Base you normally use afterward.

## Support wording

| Done | Check | Expected result |
|---|---|---|
| ☐ | Read [compiler support](compiler/support.md) | Approved pin, sampled newer-release compatibility and pinned-helper limitations are clearly distinguished |

Tell me which rows failed, or “PR checks passed.” No general feature regression sweep is required here.

## New check-status indicator

| Done | Action | Expected result |
|---|---|---|
| ☐ | Check `goals.bend` and `incomplete.bend` | Status bar shows **Bend: Incomplete** for both |
| ☐ | Check `unsafe.bend` or `foreign.bend` | Status bar retains **unsafe/foreign** qualification |
| ☐ | Click the Bend status-bar text | Popup shows the filename, result and compiler output, with **Check again** |
| ☐ | Tools → Bend → Show Bend Check Status | Enabled action opens the same details |
| ☐ | Edit a checked file; switch between files | **Recheck needed** after edits; status follows the active file |
