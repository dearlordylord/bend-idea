# Issue #16 generated PSI experiment

Run from the repository root with full JDK 21:

```sh
./gradlew -p design/parser-experiment generateParser
```

This isolated Grammar-Kit grammar tries declarations, match/case/patterns and do forms. It intentionally omits handwritten layout predicates so its generated parser shows what generation alone provides. The experiment is not part of the plugin build. The generated Java parser and PSI are written under `build/generated` and are not production plugin code.

Generation succeeds with IntelliJ Platform Gradle Plugin 2.13.1. The repository's 2.12.0 version has a Grammar-Kit dependency-resolution bug fixed in 2.13.0, so the experiment pins 2.13.1 without changing the plugin toolchain.

The generated `case_clause` and `do_expr` loops consume `body_item` without an indentation or declaration-boundary check. On the existing fold fixture's shape, a same-level expression after a case can be included in that case's range. A column-zero declaration after an incomplete header also requires explicit recovery. This minimal candidate was inspected after generation, not installed as a second runtime parser.

The supplied sibling checkout `bend-idea-psi-experiment` tests the stronger approach: a generated grammar with Scala 3 layout hooks and a test lexer adapter. Its `BendPsiExperimentTest` and `architectureTest` passed here on JDK 21. Its fixtures cover nested and sibling cases, empty and nested do blocks, malformed neighboring declarations, strings/comments, and constructor/law nodes. The custom layout hooks still own column-zero recovery, sibling case boundaries, and do statement boundaries. The prototype is not wired to the production Structure View or FoldingBuilder. Since the existing handwritten parser and current editor fixtures already supply this slice's ranges, generated PSI does not yet reduce the shared code enough to justify a second token/PSI vocabulary. Keep the production parser handwritten for #16; reassess when later expression PSI consumers need broader typed nodes.
