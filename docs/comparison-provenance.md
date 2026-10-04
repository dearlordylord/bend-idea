# Comparison-driven implementation provenance

This record covers the offline syntax documentation and regression coverage proposed in the comparison specs. All new implementation, descriptions and fixture inputs in these slices are independently authored. No community hover catalogue, grammar, scanner, test body or golden output was copied or substantially adapted. The proposals supply requirements; upstream examples identify risks rather than supply our expected output.

## Offline syntax documentation

Original destination paths:

- `src/main/scala/com/dearlordylord/bend/idea/features/documentation/BendSyntaxDocumentation.scala`
- Changes to `src/main/scala/com/dearlordylord/bend/idea/features/documentation/BendDocumentationProvider.scala`
- New fixtures in `src/test/scala/com/dearlordylord/bend/idea/features/documentation/BendDocumentationTest.scala`

The catalogue uses the proposal's original descriptions, checked against [the official guide at the selected working-tree Bend 2.0.35 revision](https://github.com/bendlang/bend/blob/79df8d9c40722ee9507a1e253f283b51025f9d6c/guide/GUIDE.md) and existing lexer token identities. The committed baseline still pins Bend 2.0.25; the selected Bend 2.0.35 migration (`79df8d9c40722ee9507a1e253f283b51025f9d6c`) remains pending and uncommitted. The actual `ci/bend-test-toolchain.properties` used by each validation invocation is the compiler oracle; this record neither approves nor commits the migration. [Community LSP analysis](https://github.com/don2e4/bend2-lsp/blob/d85e556febcd3e787c48f4558b5f89a34729626d/src/test/analysis.test.ts) supplies prior-art motivation for syntax hover; its strings and fixture bodies are not used. Token ranges, literal/comment suppression, offline dispatch and unsaved edit/repair scenarios derive from the native provider's contract and OH1–OH7 acceptance criteria.

Production classes enter the plugin package. Fixtures enter source distributions, not the plugin. No new third-party license text or NOTICE is required for this original material.

## Regression coverage

The regression coverage map is pending integration; its final path and method inventory must be linked here when it lands. It will identify existing and new native test methods, each variant's observable invariant and preventive mutation or observed failure. New programs are minimal original cases for adjacency, layout, editable recovery and root/diagnostic identity. Expected formatting follows Bend IDEA's shared layout policy; compiler judgments use `ci/bend-test-toolchain.properties`, not community parser acceptance.

Pinned motivation sources:

- [Community formatter tests](https://github.com/don2e4/bend2-lsp/blob/d85e556febcd3e787c48f4558b5f89a34729626d/src/test/formatter.test.ts): formatting and token adjacency risk.
- [Tree-sitter numeric boundaries](https://github.com/Soulthym/tree-sitter-bend2/blob/55e699af7e136f54a88939c9a3b7cd2eaad500bf/test/corpus/numeric-boundaries.txt) and [boundaries](https://github.com/Soulthym/tree-sitter-bend2/blob/55e699af7e136f54a88939c9a3b7cd2eaad500bf/test/corpus/boundaries.txt): larger token and malformed-buffer risk.
- [Official compiler](https://github.com/bendlang/bend/blob/79df8d9c40722ee9507a1e253f283b51025f9d6c/bend2/bend.ts): language recognition and physical-layout research at the selected working-tree 2.0.35 revision; acceptance still follows the actual test-toolchain properties.

Fixtures are source/test material and are not plugin runtime payloads. Existing tests retained by the coverage audit are evidence for their stated invariants; this record does not reclassify historical material or claim an upstream test execution. See [reuse policy](reuse-policy.md) for any future adaptation and required archive verification.
