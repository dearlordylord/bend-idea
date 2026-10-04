# Changelog

User-visible behavior changes to the Bend2 IDEA plugin are recorded here. Add entries
under **Unreleased** when the plugin changes. Website, documentation, and promotional
edits do not need entries unless they also change plugin behavior. Keep released
entries as a record of what shipped; do not put unreleased work in the currently
published plugin notes.

When preparing a release, move its entries into a versioned section, update
`pluginVersion` in `gradle.properties`, and write a concise summary of the same
changes in `src/main/resources/META-INF/plugin.xml`'s `<change-notes>` for that
version. Use the versioned entry when writing GitHub release notes.

## Unreleased

- Preserve lambda binding identity through typed local-let continuations, so completion, navigation and rename agree.
- Expand selection using Bend's special grouping for glued less-than comparisons, while retaining ordinary precedence for spaced comparisons.

- Explain supported Bend syntax tokens in offline Quick Documentation and quick navigation, including incomplete and unsaved code.

## 0.1.16

- Fix declaration rename silently stalling in larger projects. Discover Bend
  sources through the file index, preserve current editor text, and show a
  refactoring error when a complete source inventory is unavailable or a related
  law is read-only.
- Restore the file structure view on IntelliJ IDEA 2026.2 using the supported
  tree-element interface.

## 0.1.15

- Wrap long equality and inequality propositions in laws, binders and result
  types, including nested calls, constructors and parenthesized conjunctions.
  Reformat Code and the offline formatter share the same conservative policy.

## 0.1.14

- Use the installed `bend-format` name in CLI version and help output; add
  `--help`/`-h` (including subcommand help), actionable argument and file errors,
  and `--` for dash-prefixed filenames.
- Check all tracked Bend files from any repository subdirectory; document
  installed-tool project checks, safe hook arguments and pinned consumer CI.

## 0.1.13

- Format tuples and list literals, including calls containing multiline lists,
  and recognize definition result arrows continued on an indented line.
  The IDE and offline formatter share the same conservative checks.

## 0.1.12

- Ship ready-to-run formatter archives for Linux, macOS and Windows with a
  bundled runtime and the `bend-format` command.
- Apply local EditorConfig settings in the standalone formatter on Windows;
  preserve literal backslashes in Unix filenames.

## 0.1.11

- Preserve multiline string contents containing header-like text during
  formatting, and handle `<` comparisons with uppercase operands.

- Respect IntelliJ’s EditorConfig enable setting for Bend typing and formatting.
  Keep space indentation independent of tab width, support tab indentation with
  remaining spaces, and ignore invalid standard indentation properties while
  retaining other valid settings. Keep typing conservative when tab normalization
  would change Bend’s physical body ownership.

- Apply wrapping when Reformat Code selects all file content but omits the final
  newline or surrounding whitespace. Partial selections retain safe spacing.

- Wrap long constructor patterns, constructor expressions, calls and dependent
  definition signatures in Reformat Code and the offline formatter. Reflow
  recognized overlong multiline lists while preserving fitting grouping.
- Add the shared plugin-specific EditorConfig `bend_max_line_length` setting:
  a 100-column soft default, positive widths and `off`, including width-only
  sections and inherited overrides. Unsafe formatting leaves that file intact.

- The standalone formatter reports its packaged build version; formatter hook and
  CI examples select exactly one built artifact without a release-number literal.

## 0.1.10

- Parameter-name hints retry once when dependency discovery or an edit changes
  the captured revision, avoiding an empty first result while rejecting stale
  signatures and continuously changing sources.

- A clickable Bend status-bar indicator shows checking, incomplete, stale and
  failed results, including unsafe/foreign reliance. Its popup displays compiler
  output and offers Check again; Show Bend Check Status opens the same details.

- The optional pinned Bend 2.0.25 helper supplies versioned check-only verdicts,
  completeness, reliance and mapped diagnostics. Incompatible advertised check
  protocols remain unavailable; ordinary compilers retain guarded CLI checking.
- Imported-root snapshots use compiler-compatible generated filenames, fixing
  checks rejected by Bend 2.0.34's plain import-name restriction.

## 0.1.9

- Keep function and constructor field hints responsive in large Bend files by
  sharing source parsing and signature resolution across the editor pass. Hints
  refresh when the source, an imported declaration, or loading settings change.

## 0.1.8

- Enter, Backspace, and Reformat Code use a shared Bend layout policy with the
  effective file indent size, tab style, and tab width. Unsafe formatting requests
  are left unchanged. (#77–#79)
- Add an optional offline `check`/`fix` formatter with explicit conforming,
  would-change, and unavailable results. Add a staged pre-commit hook and an
  all-tracked-files CI example. (#80–#81)
- Point the plugin's site link to the Bend2 website.
- Highlight uppercase Unicode escapes, decode them in foreign paths, and reject
  overlong Unicode escapes, matching the pinned Bend parser.

## 0.1.7

### Improved

- Build configurations recognize `.cjs` as JavaScript output and reject it for
  native builds. (#34)
- Compiler checks now recognize Bend 2.0.32's check-only capability and proof
  verdicts, including complete, incomplete, failed, and unsafe/foreign results.
- Expand Selection shares syntax tokens and angle context per invocation, making large and
  incomplete files more responsive while preserving the existing range steps. (#69)
- Goal, resource, reflexivity and normalization actions now share current editor-location checks, so moved carets and changed source cannot show an outdated compiler result. (#68)
- Explicit and background checks now capture one current selected-root graph, including unsaved dependencies and sibling laws, and reject results after source or loading inputs change. Proof and resource previews validate isolated candidates against that captured graph. (#70–#72)
- Compatible Bend 2.0.25 helper installations can highlight the compiler's
  first error at its exact source range in a checked dependency. CLI check
  verdicts and conservative fallback remain available. (#41)
- Inspect Bend Goal shows the expected type and compiler local context at the
  first reachable named hole for that helper pairing, with stale and unsupported
  locations reported as unavailable. (#42)
- Goal and resource actions keep the live document revision for saved open files,
  including files reached through another canonical path. (#42, #45)
- Quick Documentation can show compiler-derived expression types at exactly
  mapped locations after a successful check with the pinned helper. Source
  signatures remain separate, and stale or unsupported spans show no type. (#43)
- After inspecting a named hole, completion ranks local binders that Bend
  itself checked against the goal's expected type. Other source candidates stay
  available, and a missing or timed-out comparison falls back to source
  completion. (#44)
- Explain Bend Resources shows compiler binder quantities and bounded
  whole-root replacement probes at the first named hole. It distinguishes
  accepted, rejected, partial and unavailable results without guessing
  consumption from occurrence counts. (#45)
- Normalize Bend Expression explicitly displays the compiler's result for a
  closed, exactly mapped checked expression. Evaluation is isolated and bounded;
  edits cancel the request, and no result is inserted into source. (#47)
- Try Bend Reflexivity previews a compiler-checked replacement of a named proof
  hole with `{==}`, then applies it as one undoable edit. It distinguishes a
  complete selected root from a local step followed by TODO holes. (#46)
- Expand Selection now offers Bend-aware steps for operator and type expressions,
  calls, chained applications, and type arguments. (#62, #63)
- Expand Selection now steps through tuple, list, array, index, and indexed-write
  components, as well as nested bodies and statements. (#64, #65)
- Expand Selection now recognizes complete marked atoms, qualified-name parts,
  and quoted literals. (#66)

## 0.1.6

- Tested with Bend 2.0.25.
