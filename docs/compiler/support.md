# Compiler support and maintenance

Bend2 supports tested compiler/capability pairings. It does not promise compatibility with every future Bend release or with the in-progress compiler rewrite. The plugin remains Scala 3; the compiler's implementation language does not determine its external protocol.

| Pairing | Evidence and scope | Checking transport | Semantic operations |
| --- | --- | --- | --- |
| Bend 2.0.25, `ff7a40cc9070a34c78399ecd2bbe46a044ad9b4b`, Bun 1.4.2 (`744846f84`) | Approved full-suite compiler pin | Guarded text CLI; optional check protocol 1 helper | Only independently negotiated, hash-pinned helper operations and their documented location limits |
| Published Bend 2.0.26–2.0.34 | Historical basic Linux release smoke passes, recorded on `bend-release-results`; these predate the broader editor corpus | Guarded text CLI | No claim of pinned-helper compatibility |
| Published releases passing `editor-compatibility-v2` | Candidate compatibility evidence for the recorded plugin commit, archive digest and source commit; inspect result records | Negotiated protocol when supplied, otherwise guarded text CLI | No new semantic capability implied |
| Unseen/failed releases, compiler development branches, rewritten compiler | Untested or failed, according to the result record | Unsupported assumptions remain unavailable | Unavailable until a compatible implementation is demonstrated |

The broader corpus also passed locally against the published **Bend 2.0.34 Linux ARM64 archive**, SHA-256 `416a17d282a9fd05ab9637a238b51d5ca508114d9773c37d1c11cad595440ed1`, source commit `7d8a3eb036042c6549461054d25a10f26d361c5c`. It exposed and then verified a correction to alphabetic naming of temporary dependency files. This sample does not establish all newer loader rules: 2.0.34 also changed namespace/path policy, while the shared source loader still targets the approved pin. Neither this local pass nor a future monitor pass establishes full source-model compatibility with that changed policy.

The exact approved inputs live in `ci/bend-test-toolchain.properties`. Updating them requires running the full editor/compiler/architecture gates with the new pair, updating helper assumptions and source hashes where relevant, and recording the supported matrix. A release-monitor pass alone does not authorize advancing the pin.

The broader release corpus checks compiler complete/incomplete/error/reliance states, source parsing and navigation, conservative formatting with before/after compiler judgments, imported current-buffer source, and root diagnostics. It is a bounded compatibility sample, not exhaustive language conformance, all-platform support or validation of every semantic operation. Previously recorded basic passes must be rerun under the broader suite before they count as broader evidence.

Release monitoring runs every six hours, subject to GitHub scheduling availability; manually retry a failed tag or run the backlog when needed. Record suite identity and tested plugin commit so a result's scope can be assessed. A compiler release failure should produce a focused compatibility ticket with its failing scenario, compiler tag/commit, archive digest and plugin commit. Keep editor-only behavior usable when optional compiler capabilities are unavailable.

For a new language construct, add a small representative source/editor scenario and compiler comparison at the existing public integration seams. Avoid rebuilding a second checker in the plugin. Prioritize a stable external check contract over adding more dependencies on compiler internals during self-hosting.
