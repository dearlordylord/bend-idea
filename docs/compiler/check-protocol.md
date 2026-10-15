# Bend check protocol 1

Status: implemented by Bend2's optional hash-pinned Bend 2.0.35 helper. This is a plugin-side contract and an upstream integration proposal, not a claim that published Bend binaries implement it. The compiler rewrite can implement the same transport without exposing TypeScript internals. No changes to the trusted compiler checkout are required.

## Negotiation and commands

The selected executable receives `--idea-check-capabilities` with no source argument. A compatible response is one JSON object, exit status 0:

```json
{"checkProtocol":1,"compiler":"bend-2.0.35-pinned","checkOnly":true,"locations":"compiler-source-namespace-sha256-utf16-v1","operations":["check"]}
```

`checkProtocol` is an integer, `checkOnly` is a boolean, and `compiler` is a nonempty installation identity. This identity need not use the helper's spelling. `locations` names the source-coordinate convention. The check operation must load and validate source without evaluating `main`, importing foreign implementations, building artifacts or publishing packages. Optional goals/types/resources/normalization retain their separate capability negotiation; check support implies none of them.

After successful negotiation, the only source operation is `--idea-check <materialized-root>`. Return one JSON object with exit status 0 for a successfully transported result, including a failed compiler judgment:

```json
{"checkProtocol":1,"locations":"compiler-source-namespace-sha256-utf16-v1","outcome":"success","completeness":"complete","reliance":"none","details":"Bend check complete.","diagnostics":[]}
```

- `outcome`: `success`, `failed`, or `unavailable`.
- `completeness`: `complete`, `incomplete`, or `unknown`.
- `reliance`: `none`, `unsafe-or-foreign`, or `unknown`.
- `details`: display text; its wording never determines the verdict.
- `diagnostics`: ordered array, at most 256 entries, each with a string `message` and optional `span`.
- `incompleteKind`: present only for an incomplete failed judgment; `todo` or `named-hole`. Unknown values are incompatible.

A successful judgment must be complete, have known reliance, contain no diagnostics, and have no incomplete kind. A failed judgment must carry a diagnostic. Incomplete judgments require a known incomplete kind. Transport errors, timeout, cancellation, malformed required fields and unknown protocol versions never produce success. An advertised incompatible check protocol does not fall back to prose matching.

## Source mapping and isolation

The bundled helper negotiates `compiler-source-namespace-sha256-utf16-v1`. Its check response repeats this `locations` value. A span has `{ "source": "compiler input text", "namespace": "loaded namespace", "sha256": "lowercase SHA-256 of UTF-8 source", "start": 0, "end": 1 }`: zero-based UTF-16 offsets with an exclusive end. The namespace comes directly from the pinned compiler's `Span.file.ns`, and the source from `Span.file.str` after import blanking. It is not a path or an original editor buffer. The check adapter projects the materialized compiler namespace from each copied path relative to the copied root directory (the selected root is the empty namespace), following the pinned loader; original loaded-graph namespaces remain unchanged. The adapter requires exact materialized namespace, digest and compiler-source text matches against one captured mapping before composing compiler-to-copy-to-original ranges. Digest or namespace mismatches and ambiguous matches remain root-level; the adapter never derives a filename from a namespace. Root and Base both use the empty namespace. Empty-namespace spans additionally require `rootSource: true`; the helper sets this only when the compiler parser file directory equals the materialized root directory and differs from the built-in Base directory. Missing directory evidence, Base spans and ambiguous shared directories remain root-level even when source text and digest equal the root. Base is intentionally absent from editor materialization mappings.

External protocol-1 implementations advertising the older `compiler-source-utf16` convention remain supported. Their response omits `locations` and spans carry only `source`, `start` and `end`; one exact compiler-source text match is required. Duplicate sources remain root-level. A response cannot downgrade the newly negotiated convention by omitting its identity fields or coordinate convention. Unknown conventions are incompatible. Rewritten imports, invalid ranges and unknown sources cannot produce guessed editor locations.

The pinned compiler does not expose a stable physical path in every span. This extension therefore uses its actual namespace plus content provenance, rather than claiming path provenance. Adding canonical file IDs requires a separately negotiated convention and genuine compiler support.

The adapter retains selected-root and snapshot provenance, exact executable/Base freshness, matching Base validation, closed graph materialization, isolated package cache and offline hub, sibling-LAWS guard, cancellation, process-tree termination and temporary-directory cleanup. Protocol output and capability output are capped at 256 KiB. Capability negotiation has a three-second limit and checking a fifteen-second limit. The helper's pinned book validation, completeness and reliance walk mirror the pinned CLI; compiler-source hashes gate those assumptions. The helper never calls run/build entry points.

## Transition and upstream adoption

Compiler identities are opaque installation identities, not a release whitelist. The independent semantic capability command `--idea-structured-capabilities` likewise requires numeric `protocol: 1`, a nonempty string `compiler`, and an `operations` array advertising each requested operation. Diagnostic, goal, expression-type, binder-comparison and normalization consumers use this shared negotiation; check support alone enables none of them.

The bundled helper advertises no check protocol when compiler-source hashes do not match: it returns a non-JSON unavailable message with a nonzero exit code to capability probing. The launcher then remains eligible for guarded CLI checking. This absence differs from advertising an incompatible JSON protocol, which still blocks fallback. Source-hash checks continue to protect optional private-API operations. CI pins the integration baseline independently of runtime negotiation or plugin release cadence.

1. Use the pinned helper for protocol-backed checking today; ordinary published binaries continue through the guarded text `--check-only` fallback.
2. During the Bend self-hosting rewrite, agree on the commands, envelope, check-only guarantee, coordinate convention and optional capability names above. The upstream implementation owns genuine compiler judgments and any safe recovery needed for multiple diagnostics (#75).
3. Add a real compiler pairing to the integration matrix before advertising native protocol support. Exercise complete proofs, TODO/named holes, unsafe/foreign reliance, ordinary failures, source mapping and side-effecting `main`.
4. Retain older text compatibility while it is explicitly tested. A new compiler release or a passed basic smoke test does not advance the approved pin or establish semantic-helper support.

Upstream coordination/adoption remains outstanding; this repository contains the reviewable proposal and working pinned adapter, not an upstream commitment.
