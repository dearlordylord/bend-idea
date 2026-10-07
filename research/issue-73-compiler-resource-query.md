# Issue #73: pinned Bend resource query feasibility

Researched 2026-09-27 against Bend commit `ff7a40cc9070a34c78399ecd2bbe46a044ad9b4b` and Bun `1.4.2+744846f84` (the pair recorded in [`ci/bend-test-toolchain.properties`](../ci/bend-test-toolchain.properties)). The supplied Bend checkout was read only. The Bun binary and probe files were placed under `/tmp`.

## Decision

**No go for a source-correlated measured-use/demand capability with the pinned exported interface.** The checker computes those facts during recursive judgments, but its public success result retains only a typed term and the *outward* usage map; binder use is removed when a binder closes. The first-hole error retains context, expected type and source span, but neither ambient demand nor usage accumulated in sibling expressions. The checked book stores the typed term, not the usage map. A helper could recover these facts only by replaying/reconstructing checker traversal or by a new upstream compiler observation API. Neither is the supported source-correlated compiler contract required by #73. This conclusion is about the pinned compiler API, not a claim that Bend's checker lacks resource accounting.

Issue #73's explicit no-go branch asks for this evidence and an upstream contract. It does **not** unblock #45's general resource explanation.

Follow-up, 2026-10-07: [MattCozendey/bend-lint](https://github.com/MattCozendey/bend-lint) collects demand and outward-use observations by instrumenting private checker functions at module load time. See the [architecture and feature comparison](bend-lint-architecture-and-features.md) for its mechanism and compatibility evidence. This is useful prior art for an upstream observation contract, but does not establish a supported resource API or remaining-resource information at incomplete holes; the historical no-go above remains scoped to its pinned exported interface.

## Source evidence

- [`bend2/bend.ts`](https://github.com/bendlang/bend/blob/ff7a40cc9070a34c78399ecd2bbe46a044ad9b4b/bend2/bend.ts#L3318-L3335) defines `qt` as demand (`None` dead, `Lone` live) and `us` as measured use. [`Infer`/`Check`](https://github.com/bendlang/bend/blob/ff7a40cc9070a34c78399ecd2bbe46a044ad9b4b/bend2/bend.ts#L347-L353) return `us`; [`Check()`](https://github.com/bendlang/bend/blob/ff7a40cc9070a34c78399ecd2bbe46a044ad9b4b/bend2/bend.ts#L474-L480) wraps the typed term with an annotation but does not attach `us` to that term.
- [`term_check`'s lambda and let rules](https://github.com/bendlang/bend/blob/ff7a40cc9070a34c78399ecd2bbe46a044ad9b4b/bend2/bend.ts#L3539-L3594) measure bound variables, validate them with `quant_used`, then remove their entries with `uses_del` before returning. The top-level [`def_check`](https://github.com/bendlang/bend/blob/ff7a40cc9070a34c78399ecd2bbe46a044ad9b4b/bend2/bend.ts#L3764-L3785) returns only `.tm`; [`book_valid`](https://github.com/bendlang/bend/blob/ff7a40cc9070a34c78399ecd2bbe46a044ad9b4b/bend2/bend.ts#L3920-L3945) saves that as `Def.e`. Thus traversing `Def.e` can recover type annotations and some source spans, but not the local measured-use judgments.
- Demand varies by rule: an application checks its argument at `quant_dem(functionQuantity, qt)` and adds usage ([`bend.ts`](https://github.com/bendlang/bend/blob/ff7a40cc9070a34c78399ecd2bbe46a044ad9b4b/bend2/bend.ts#L3448-L3471)); equality components are checked dead ([`bend.ts`](https://github.com/bendlang/bend/blob/ff7a40cc9070a34c78399ecd2bbe46a044ad9b4b/bend2/bend.ts#L3486-L3495)); match branches join usage, rather than summing it ([`bend.ts`](https://github.com/bendlang/bend/blob/ff7a40cc9070a34c78399ecd2bbe46a044ad9b4b/bend2/bend.ts#L3637-L3702)). Reconstructing demand from a typed tree would mean implementing these checker rules again outside the core.
- The named-hole rule throws [`Err(book, ctx, ty, tm, tm.s, lhs.def)`](https://github.com/bendlang/bend/blob/ff7a40cc9070a34c78399ecd2bbe46a044ad9b4b/bend2/bend.ts#L3718-L3726). The [`Err` type](https://github.com/bendlang/bend/blob/ff7a40cc9070a34c78399ecd2bbe46a044ad9b4b/bend2/bend.ts#L351-L353) has no `qt` or `us`. The error's `ctx` records declared binder quantities through [`Ann`](https://github.com/bendlang/bend/blob/ff7a40cc9070a34c78399ecd2bbe46a044ad9b4b/bend2/bend.ts#L316-L318), not how much has been consumed.
- [`Span`](https://github.com/bendlang/bend/blob/ff7a40cc9070a34c78399ecd2bbe46a044ad9b4b/bend2/bend.ts#L331-L336) contains source text plus offsets, but no source path. The loader reads file text and handles imported source ([`bend.ts`](https://github.com/bendlang/bend/blob/ff7a40cc9070a34c78399ecd2bbe46a044ad9b4b/bend2/bend.ts#L1027-L1065)); the parser creates spans from its source string ([`bend.ts`](https://github.com/bendlang/bend/blob/ff7a40cc9070a34c78399ecd2bbe46a044ad9b4b/bend2/bend.ts#L1586-L1592)). Source-to-file identity must therefore be correlated by the plugin snapshot mapper, with ambiguity rejected; a future compiler event cannot be trusted on span alone.
- The optional helper advertises diagnostic, first-hole goal, checked expression types, binder comparison and closed normalization, with no resource operation ([`bend-structured-helper.ts`](../src/main/resources/semantic/bend-structured-helper.ts#L19-L41)). Its first-hole payload exposes context quantity/type and span ([same file](../src/main/resources/semantic/bend-structured-helper.ts#L199-L223)); comparison checks one binder against the hole's expected type and context ([same file](../src/main/resources/semantic/bend-structured-helper.ts#L180-L198)). Neither captures previous sibling use.

## Real compiler probe

I downloaded Bun release `bun-v1.4.2/bun-darwin-aarch64.zip` outside the repo and verified `bun --version` = `1.4.2`, `bun --revision` = `1.4.2+744846f84`. I ran the repository's unchanged structured helper and the pinned `bend2/main.ts --check-only` with `BEND_NO_TELEMETRY=1` against these two roots:

```bend
import Base
def test(x: U32) -> U32 & U32:
  (x, ?need)
```

```bend
import Base
def test(x: U32) -> U32 & U32:
  (0, ?need)
```

Both `goal` replies reported `expected: "U32"`, `context: [{name:"x",quantity:"",type:"U32"}]`, and the exact `?need` span (`start:38,end:43`) in their respective source strings. Both `compare` replies reported `compatible:["x"]`. The only distinguishing output was the source text embedded in the span/message, not a checker resource fact. The helper's `capabilities` reply listed no resource operation.

Replacing `?need` with `x` and checking the complete root produced different authoritative results:

| Root body | `--check-only` result |
| --- | --- |
| `(x, x)` | Exit 1: `expected : x`; `observed : x (consumed more than once)` |
| `(0, x)` | Exit 0: `All terms check.` |

This establishes the missing information concretely: a binder that appears equally available in the goal context and local comparison may already be consumed elsewhere. A candidate replacement verdict can test one proposed edit, but cannot supply a measured state for the original source location.

To repeat the probe, place the two source blocks above in `used.bend` and `unused.bend` in a temporary directory, copy the pinned `bend2/base.bend` there, and create `used-filled.bend` and `unused-filled.bend` by replacing `?need` with `x`. With `BUN` set to the verified Bun 1.4.2 executable, `BEND` set to the pinned checkout's `bend2` directory, and `PROBES` set to that temporary directory, run:

```sh
BEND_TEST_COMPILER_DIR="$(dirname "$BEND")" BEND_TEST_BUN="$BUN" bash ci/verify-bend-test-inputs.sh
"$BUN" src/main/resources/semantic/bend-structured-helper.ts capabilities "$BEND"
for name in used unused; do
  "$BUN" src/main/resources/semantic/bend-structured-helper.ts goal "$BEND" "$PROBES/$name.bend"
  "$BUN" src/main/resources/semantic/bend-structured-helper.ts compare "$BEND" "$PROBES/$name.bend"
done
for name in used-filled unused-filled; do
  BEND_NO_TELEMETRY=1 "$BUN" "$BEND/main.ts" "$PROBES/$name.bend" --check-only
done
```

## Required upstream contract for a go

The compiler needs an observation/query operation in its checker, keyed to a specific parsed source node within one loaded root. It should emit the demand passed *into* that node and compiler-measured usage for precisely defined scope (for example, the node's outward usage and each binder before deletion at close), with declaration quantity kept separate. It must define whether a value describes the selected node, its enclosing expression or a point before/after sibling composition, and must identify unsupported or unvisited nodes. The event should include an unambiguous source identity (or a correlation token supplied by the loader) plus exact span. Then the optional helper can negotiate a versioned resource operation and the plugin can map the result to its root/snapshot provenance, reject ambiguous spans, and preserve check-only process limits. The compiler's ordinary whole-root verdict remains authoritative.

Until such an upstream interface exists, do not derive general remaining resources from source occurrences, `Def.e` type annotations, hole context quantity, or candidate acceptance. The existing bounded candidate action retains its narrower meaning.
