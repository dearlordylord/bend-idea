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

- Expand Selection now offers Bend-aware steps for operator and type expressions,
  calls, chained applications, and type arguments. (#62, #63)
- Expand Selection now steps through tuple, list, array, index, and indexed-write
  components, as well as nested bodies and statements. (#64, #65)
- Expand Selection now recognizes complete marked atoms, qualified-name parts,
  and quoted literals. (#66)

## 0.1.6

- Tested with Bend 2.0.25.
