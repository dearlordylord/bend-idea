# Bend check protocol 1

Status: implemented by Bend2's optional hash-pinned Bend 2.0.25 helper. This is a plugin-side contract and an upstream integration proposal, not a claim that published Bend binaries implement it. The compiler rewrite can implement the same transport without exposing TypeScript internals. No changes to the trusted compiler checkout are required.

## Negotiation and commands

The selected executable receives `--idea-check-capabilities` with no source argument. A compatible response is one JSON object, exit status 0:

```json
{"checkProtocol":1,"compiler":"bend-2.0.25-pinned","checkOnly":true,"locations":"compiler-source-utf16","operations":["check"]}
```

`checkProtocol` is an integer, `checkOnly` is a boolean, and `compiler` is a nonempty installation identity. This identity need not use the helper's spelling. `locations` names the source-coordinate convention. The check operation must load and validate source without evaluating `main`, importing foreign implementations, building artifacts or publishing packages. Optional goals/types/resources/normalization retain their separate capability negotiation; check support implies none of them.

After successful negotiation, the only source operation is `--idea-check <materialized-root>`. Return one JSON object with exit status 0 for a successfully transported result, including a failed compiler judgment:

```json
{"checkProtocol":1,"outcome":"success","completeness":"complete","reliance":"none","details":"Bend check complete.","diagnostics":[]}
```

- `outcome`: `success`, `failed`, or `unavailable`.
- `completeness`: `complete`, `incomplete`, or `unknown`.
- `reliance`: `none`, `unsafe-or-foreign`, or `unknown`.
- `details`: display text; its wording never determines the verdict.
- `diagnostics`: ordered array, at most 256 entries, each with a string `message` and optional `span`.
- `incompleteKind`: present only for an incomplete failed judgment; `todo` or `named-hole`. Unknown values are incompatible.

A successful judgment must be complete, have known reliance, contain no diagnostics, and have no incomplete kind. A failed judgment must carry a diagnostic. Incomplete judgments require a known incomplete kind. Transport errors, timeout, cancellation, malformed required fields and unknown protocol versions never produce success. An advertised incompatible check protocol does not fall back to prose matching.

## Source mapping and isolation

A span has `{ "source": "compiler input text", "start": 0, "end": 1 }`: zero-based UTF-16 offsets with an exclusive end. `source` is the exact text after the loader blanks import lines, not an original editor buffer or a filename. The adapter accepts an exact span only when one captured compiler source matches and the existing compiler-to-copy-to-original range mapping succeeds. Duplicate source texts, rewritten import paths, unknown sources and invalid ranges cannot produce a guessed editor location. Their diagnostics remain root-level where possible.

This initial convention matches the real pinned compiler's source spans. A future upstream protocol can supply canonical source IDs/digests to disambiguate identical files, but that requires a new negotiated coordinate convention and mapping support; filenames alone must not be treated as proof of identity.

The adapter retains selected-root and snapshot provenance, exact executable/Base freshness, matching Base validation, closed graph materialization, isolated package cache and offline hub, sibling-LAWS guard, cancellation, process-tree termination and temporary-directory cleanup. Protocol output and capability output are capped at 256 KiB. Capability negotiation has a three-second limit and checking a fifteen-second limit. The helper's pinned book validation, completeness and reliance walk mirror the pinned CLI; compiler-source hashes gate those assumptions. The helper never calls run/build entry points.

## Transition and upstream adoption

1. Use the pinned helper for protocol-backed checking today; ordinary published binaries continue through the guarded text `--check-only` fallback.
2. During the Bend self-hosting rewrite, agree on the commands, envelope, check-only guarantee, coordinate convention and optional capability names above. The upstream implementation owns genuine compiler judgments and any safe recovery needed for multiple diagnostics (#75).
3. Add a real compiler pairing to the integration matrix before advertising native protocol support. Exercise complete proofs, TODO/named holes, unsafe/foreign reliance, ordinary failures, source mapping and side-effecting `main`.
4. Retain older text compatibility while it is explicitly tested. A new compiler release or a passed basic smoke test does not advance the approved pin or establish semantic-helper support.

Upstream coordination/adoption remains outstanding; this repository contains the reviewable proposal and working pinned adapter, not an upstream commitment.
