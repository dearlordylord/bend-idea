# Original work and upstream reuse

Prefer Bend IDEA's own style and ideas. Use upstream reports to identify a language risk or user need, then implement it in the existing Scala owner and write a minimal original fixture. Derive expectations from the implementation branch's pinned Bend compiler, architecture and actual IntelliJ behavior. Keep immutable research links so the motivation is traceable. A research link is not evidence that a test passed.

Do not translate an upstream function line by line, reproduce its test suite or rename variables to present an adaptation as original work. This applies to code, grammar/scanner material, fixture inputs and expectations, documentation and hover descriptions. Reference checkouts retain their upstream licenses and must remain untouched.

## Recording an adaptation

Before adding copied or substantially adapted expressive material:

1. Record the upstream project, immutable revision and source path, actual source license, and retained copyright/attribution notices. Inspect file-specific headers, vendored material and any upstream NOTICE; the repository license may not cover every file.
2. Identify the derived destination paths, label the material as an adaptation and describe modifications in its source or provenance record. Distinguish original paths from derived paths.
3. Retain required license text and notices with the material. Apache-2.0 requires supplying the license to recipients, retaining applicable notices, identifying modifications and carrying applicable NOTICE attributions when supplied upstream. MIT requires retaining its copyright and permission notice with copies or substantial portions. A prose credit or URL alone is insufficient; our Apache-2.0 license does not replace another project's copyright notice.
4. Inspect the actual source archive and every plugin/CLI package containing the material to verify notices survived packaging. Test-only material needs source-distribution notices, but its notices need not be added to a runtime artifact that does not contain it. Add a focused archive assertion when introducing an obligation to a packaged artifact.

If ownership or terms are unclear, write an independent case or defer that particular reuse. Original implementation remains authorized. Review under [REVIEWER.md](../REVIEWER.md) must identify provenance and actual copied material, rather than merely check a box that research was consulted.

A provenance record should give destination paths, classification (original or adapted), immutable motivation/source paths, expectation authority, modifications if adapted, required notices and the distributions that contain the material. [Comparison provenance](comparison-provenance.md) records the first comparison-driven slices.

## Distribution boundaries

The plugin packages production classes and `src/main/resources`; test sources and contributor documents are source material, not plugin runtime resources. The formatter JAR packages its selected production classes and runtime libraries; `scripts/package-bend-format.py` carries the project LICENSE, optional NOTICE, existing third-party notices and JAR legal resources into the native formatter archive, alongside runtime legal notices. `scripts/test-bend-format-distribution.py` checks existing distribution notices. Recheck these boundaries when a derived file is introduced; a build success alone does not prove attribution survived.

The comparison-driven slices use original implementation, descriptions and fixtures. They add no upstream expressive material to the plugin or formatter, so they introduce no new upstream notice payload. Existing dependency notices remain necessary. This statement concerns these slices, not a retrospective claim that every historical file has been audited.

## Inspected prior art

These are research sources, not dependencies newly distributed by this work:

- [don2e4/bend2-lsp](https://github.com/don2e4/bend2-lsp/tree/d85e556febcd3e787c48f4558b5f89a34729626d): [Apache-2.0 license](https://github.com/don2e4/bend2-lsp/blob/d85e556febcd3e787c48f4558b5f89a34729626d/LICENSE).
- [Soulthym/tree-sitter-bend2](https://github.com/Soulthym/tree-sitter-bend2/tree/55e699af7e136f54a88939c9a3b7cd2eaad500bf): [MIT license, copyright Thybault Alabarbe](https://github.com/Soulthym/tree-sitter-bend2/blob/55e699af7e136f54a88939c9a3b7cd2eaad500bf/LICENSE).
- [IlyaGulya/bend2-lsp-rs](https://github.com/IlyaGulya/bend2-lsp-rs/tree/1e75117a8d5be38d648f65f25d854c894cffbafd): [Apache-2.0 license](https://github.com/IlyaGulya/bend2-lsp-rs/blob/1e75117a8d5be38d648f65f25d854c894cffbafd/LICENSE).
- [Official Bend research pin](https://github.com/bendlang/bend/tree/565d7fdec289b1f2f5f5037bb58b5afb6738ff1d): [Apache-2.0 license](https://github.com/bendlang/bend/blob/565d7fdec289b1f2f5f5037bb58b5afb6738ff1d/LICENSE). This research pin does not override `ci/bend-test-toolchain.properties` for compiler judgments.

For exact terms consult [Apache-2.0 section 4](https://www.apache.org/licenses/LICENSE-2.0) and the [MIT license](https://opensource.org/license/mit).
