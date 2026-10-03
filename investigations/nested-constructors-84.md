# Nested qualified constructor highlighting — issue #84

Observed on 2026-10-03 in IntelliJ IDEA Community 2025.1, build `IC-251.23774.435`, running the locally built Bend2 plugin `0.1.10`. The copied plugin JAR used for this observation had SHA-256 `1d8425caa6ec90009640d2f37eada8c64be702a9243ab368afcf31271e283242`. This identifies the observed working-tree build; subsequent formatter work may produce a different archive hash.

The existing resolver/annotator supports the exact nested pattern. No production highlighting change was needed. In the [captured editor](nested-constructors-84/editor.png), `Cr`, `Alive`, `Ec`, the pattern `True`, and the body `True` are visibly magenta and bold. Their `T` aliases are cyan; dots and pattern field binders retain ordinary text colors. Existing resolved body binders (`actor`, negative-control `field`) are yellow. The unresolved `Missing` member stays ordinary text rather than acquiring constructor color.

The [Issue84 scheme](nested-constructors-84/Issue84.icls) derives from Darcula and explicitly differentiates constructor (`FF80FF`, bold), alias (`60E8FF`), and binder (`FFE080`) attributes. This verifies visible semantic distinctions under that scheme; it does not change or make a claim about default theme contrast.

## Reproduce the visual observation

1. Build the current plugin with JDK 21 using `./gradlew prepareSandbox --no-configuration-cache` and launch IDEA with that sandbox plugin.
2. Open a project containing [main.bend](nested-constructors-84/main.bend) and [creature.bend](nested-constructors-84/creature.bend). Use an empty local Base, as the editor fixture does, to avoid unrelated library declarations.
3. Import the supplied `Issue84.icls` scheme and select it through Editor Color Scheme. Allow indexing to finish. Hide inlay hints with “Toggle Inlay Hints Globally” to view the exact source.
4. Compare the nested pattern, the separate body `True`, and unresolved `T.Missing` with the screenshot. The negative control deliberately has no constructor declaration.

The observation used a real IDEA GUI on a local Xvfb display, with xdotool for interaction and Java AWT Robot for screen capture. IDEA logged `Loaded custom plugins: Bend2 (0.1.10)`. The editor's failed-check/error indicator is not compiler acceptance evidence; this fixture is a source-resolution/highlighting reproducer containing the intentionally unresolved negative control.

## Automated evidence and self-review

`./gradlew test --tests '*BendSemanticReadingTest' --no-configuration-cache` passed: 4 tests, no failures/errors/skips, including `testNestedQualifiedConstructorPatternUsesExactResolvedRanges`. The new test calls native `doHighlighting`, asserts exact half-open Alias/Constructor highlight ranges and dot exclusion, verifies native alias/member reference ranges and intended physical source targets, checks both `True` occurrences independently, and excludes constructor color from field binders, wildcard, and unresolved member. The existing category policy remains authoritative (A1–A4, A8); this adds evidence at its existing editor seam.

`./gradlew prepareSandbox --no-configuration-cache` also passed. This is implementer self-review under `REVIEWER.md`, not an independent review or a claim that the full repository gates have run.
