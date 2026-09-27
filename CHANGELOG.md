# Changelog

User-visible changes to the Bend2 IDEA plugin are recorded here. Add entries under
**Unreleased** when the change is made. Keep released entries as a record of what
shipped; do not put unreleased work in the currently published plugin notes.

When preparing a release, move its entries into a versioned section, update
`pluginVersion` in `gradle.properties`, and write a concise summary of the same
changes in `src/main/resources/META-INF/plugin.xml`'s `<change-notes>` for that
version. Use the versioned entry when writing GitHub release notes.

## Unreleased

### Improved

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
