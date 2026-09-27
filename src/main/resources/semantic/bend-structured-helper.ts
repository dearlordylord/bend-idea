#!/usr/bin/env bun
// Protocol 1 exposes the first error from the pinned Bend 2.0.25 sources.
// The ordinary --check-only result remains the authority for the verdict.
import * as crypto from "node:crypto";
import * as fs from "node:fs";
import * as path from "node:path";
import * as url from "node:url";

const hashes: Record<string, string> = {
  "bend.ts": "93c2a43deeb82c15683e4e25bbc5dec5ac3edff9f54e09acc0975e290fcaeb85",
  "comp.ts": "c19b9be5be69930be218e68880ae5a1aa13e51cda0d2a0547bcb7259fdf96bc3",
  "main.ts": "92dcdb49e82fd59443e3aea10784f7dcf03a93f5a21920666543098b657b6b1e",
};

function emit(value: unknown): void {
  process.stdout.write(JSON.stringify(value) + "\n");
}

async function main(): Promise<void> {
  const [operation, compilerDir, root, requestedDigest, requestedStart, requestedEnd] = process.argv.slice(2);
  if (operation !== "capabilities" && operation !== "diagnostic" && operation !== "goal" && operation !== "types" && operation !== "compare" && operation !== "normalize") {
    emit({ protocol: 1, error: "unsupported operation" });
    return;
  }
  if (!compilerDir || (operation !== "capabilities" && !root)) {
    emit({ protocol: 1, error: "missing compiler directory or root" });
    return;
  }
  const dir = fs.realpathSync(compilerDir);
  const compatible = Object.entries(hashes).every(([name, expected]) => {
    const file = path.join(dir, name);
    return fs.existsSync(file) &&
      crypto.createHash("sha256").update(fs.readFileSync(file)).digest("hex") === expected;
  });
  if (!compatible) {
    emit({ protocol: 1, compiler: "unsupported", operations: [] });
    return;
  }
  if (operation === "capabilities") {
    emit({ protocol: 1, compiler: "bend-2.0.25-pinned", operations: ["diagnostic", "goal-first-named-hole", "expression-types-checked", "goal-binder-comparison", "normalize-closed-expression"] });
    return;
  }
  const Bend = await import(url.pathToFileURL(path.join(dir, "bend.ts")).href);
  const Comp = await import(url.pathToFileURL(path.join(dir, "comp.ts")).href);
  try {
    const book = Bend.book_nil();
    await Bend.book_load(book, root, "", new Map());
    Bend.book_valid(book);
    Comp.book_owned(book, Comp.SYNTH);
    if (operation === "normalize") {
      const start = Number(requestedStart);
      const end = Number(requestedEnd);
      if (!/^[0-9a-f]{64}$/.test(requestedDigest ?? "") || !Number.isInteger(start) ||
          !Number.isInteger(end) || start < 0 || end <= start) {
        emit({ protocol: 1, kind: "unavailable" });
        return;
      }
      const matches: any[] = [];
      function find(term: any, depth = 0, ancestor = ""): void {
        if (!term || depth > 128 || matches.length > 1) return;
        if (term.$ === "Ann") {
          const node = term.x;
          const span = node?.s;
          const key = span && typeof span.src === "string"
            ? `${crypto.createHash("sha256").update(span.src).digest("hex")}:${span.beg}:${span.end}`
            : "";
          if (key && key !== ancestor && key === `${requestedDigest}:${start}:${end}`) matches.push(node);
          find(node, depth + 1, key || ancestor);
        } else if (term.$ === "Lam") {
          find(term.f, depth + 1, ancestor);
        } else {
          for (const key of ["f", "x", "a", "b", "T", "A", "B", "g", "v", "h", "m", "e", "p"]) {
            const child = term[key];
            if (child && typeof child === "object" && typeof child.$ === "string") find(child, depth + 1, ancestor);
            if (Array.isArray(child)) for (const item of child) find(item, depth + 1, ancestor);
          }
        }
      }
      for (const value of Object.values(book.tlds) as any[]) {
        if (matches.length > 1) break;
        if (value.$ === "Def" && value.e) find(value.e);
      }
      function closed(term: any, depth = 0): boolean {
        if (!term || depth > 128 || typeof term.$ !== "string") return false;
        if (term.$ === "Ann") return closed(term.x, depth + 1);
        if (term.$ === "Var" || term.$ === "Lam" || term.$ === "All" ||
            term.$ === "Let" || term.$ === "Sub" || term.$ === "Hol") return false;
        for (const key of ["f", "x", "a", "b", "T", "A", "B", "g", "v", "h", "m", "e", "p"]) {
          const child = term[key];
          if (child && typeof child === "object" && typeof child.$ === "string" && !closed(child, depth + 1)) return false;
          if (Array.isArray(child)) for (const item of child) if (!closed(item, depth + 1)) return false;
        }
        return true;
      }
      if (matches.length !== 1 || !closed(matches[0])) {
        emit({ protocol: 1, kind: "unavailable" });
      } else {
        const normal = Bend.term_show(Bend.term_lower(Bend.term_snf(book, Bend.term_higher(matches[0]))));
        emit(normal.length <= 2048
          ? { protocol: 1, kind: "normal", digest: requestedDigest, start, end, text: normal }
          : { protocol: 1, kind: "unavailable" });
      }
    } else if (operation === "types") {
      // book_valid stores first-order, type-annotated checked bodies in Def.e.
      // Only compiler spans from those bodies can establish expression types.
      const entries: Array<{ span: { sourceIndex: number; start: number; end: number }; type: string }> = [];
      const sources: string[] = [];
      const sourceIndexes = new Map<string, number>();
      let sourceBytes = 0;
      const seen = new Set<string>();
      function visit(term: any, names: string[], depth: number, ancestorSpan = ""): void {
        if (!term || depth > 128 || entries.length >= 256) return;
        if (term.$ === "Ann") {
          const node = term.x;
          const span = node?.s;
          if (node?.$ !== "Lam" && node?.$ !== "All" && span &&
              typeof span.src === "string" && Number.isInteger(span.beg) &&
              Number.isInteger(span.end) && span.beg >= 0 &&
              span.end > span.beg && span.end <= span.src.length &&
              span.end - span.beg <= 16384 && term.T?.$ === "Var" && term.T.v) {
            let sourceIndex = sourceIndexes.get(span.src);
            if (sourceIndex === undefined && sourceBytes + Buffer.byteLength(span.src, "utf8") <= 131072) {
              sourceIndex = sources.length;
              sources.push(span.src);
              sourceIndexes.set(span.src, sourceIndex);
              sourceBytes += Buffer.byteLength(span.src, "utf8");
            }
            const key = `${sourceIndex}\u0000${span.beg}\u0000${span.end}`;
            // A nested partial application can inherit its parent's full span.
            // It is not a separate source expression at that range.
            if (sourceIndex !== undefined && key !== ancestorSpan) {
              try {
                const type = Bend.term_show(Bend.term_lower(term.T.v, names.length), -1, names);
                if (type.length > 0 && type.length <= 256 && !seen.has(`${key}\u0000${type}`)) {
                  seen.add(`${key}\u0000${type}`);
                  entries.push({ span: { sourceIndex, start: span.beg, end: span.end }, type });
                }
              } catch { /* A type without a stable printable form is unavailable. */ }
            }
          }
          const nodeSpan = node?.s;
          const currentSpan = nodeSpan && sourceIndexes.has(nodeSpan.src)
            ? `${sourceIndexes.get(nodeSpan.src)}\u0000${nodeSpan.beg}\u0000${nodeSpan.end}`
            : ancestorSpan;
          visit(node, names, depth + 1, currentSpan);
        } else if (term.$ === "Lam") {
          visit(term.f, [...names, term.k], depth + 1, ancestorSpan);
        } else if (term.$ === "All") {
          visit(term.A, names, depth + 1, ancestorSpan);
          visit(term.B, [...names, term.k], depth + 1, ancestorSpan);
        } else {
          for (const key of ["f", "x", "a", "b", "T", "A", "B", "g", "v", "h", "m", "e", "p"]) {
            const child = term[key];
            if (child && typeof child === "object" && typeof child.$ === "string") visit(child, names, depth + 1, ancestorSpan);
            if (Array.isArray(child)) for (const item of child) visit(item, names, depth + 1, ancestorSpan);
          }
        }
      }
      // Loaded dependencies precede the selected root in the book. Visit the
      // later definitions first so a large Base cannot consume the result cap.
      for (const value of (Object.values(book.tlds) as any[]).reverse()) {
        if (entries.length >= 256) break;
        if (value.$ === "Def" && value.e) visit(value.e, [], 0);
      }
      emit({ protocol: 1, kind: "types", sources, entries });
    } else {
      emit({ protocol: 1, kind: "no-error" });
    }
  } catch (error) {
    if (error && typeof error === "object" && "$" in error && error.$ === "Err") {
      const err = error as {
        bok: unknown; ctx: unknown; exp: unknown; obs?: { $?: string; k?: string };
        spn?: { src: string; beg: number; end: number };
      };
      const span = err.spn && Number.isInteger(err.spn.beg) &&
        Number.isInteger(err.spn.end) && err.spn.beg >= 0 &&
        err.spn.end >= err.spn.beg && err.spn.end <= err.spn.src.length
        ? { source: err.spn.src, start: err.spn.beg, end: err.spn.end }
        : null;
      if (operation === "compare") {
        if (err.obs?.$ !== "Hol" || err.obs.k === "TODO" || !span ||
            typeof err.exp !== "object" || !err.exp || typeof (err as any).def !== "string") {
          emit({ protocol: 1, kind: "unavailable" });
        } else {
          const bindings = Bend.pmap_to_array(err.ctx)
            .sort((a: [number, unknown], b: [number, unknown]) => a[0] - b[0]);
          const depth = bindings.reduce((max: number, [index]: [number, unknown]) => Math.max(max, index + 1), 0);
          const compatible: string[] = [];
          for (const [index, binding] of bindings.slice(0, 32) as Array<[number, { k: string }]>) {
            try {
              Bend.term_check(err.bok, { t: Bend.Ref((err as any).def), n: 0, def: (err as any).def, qs: [] },
                Bend.Var(binding.k, index), Bend.Lone(), err.exp, err.ctx, depth);
              compatible.push(binding.k);
            } catch { /* A rejected candidate receives no ranking boost. */ }
          }
          emit({ protocol: 1, kind: "comparison", hole: err.obs.k,
            message: Bend.err_show(error), span, compatible });
        }
      } else if (operation === "goal") {
        if (err.obs?.$ !== "Hol" || err.obs.k === "TODO" || !span) {
          emit({ protocol: 1, kind: "unavailable" });
        } else {
          const names = Bend.ctx_scope(err.ctx);
          const context = Bend.pmap_to_array(err.ctx)
            .sort((a: [number, unknown], b: [number, unknown]) => a[0] - b[0])
            .map(([index, binding]: [number, { k: string; q: unknown; T: unknown }]) => ({
              name: binding.k,
              quantity: Bend.quant_show(binding.q),
              type: Bend.term_show(
                Bend.term_lower(Bend.term_snf(err.bok, binding.T), index),
                -1,
                names.slice(0, index),
              ),
            }));
          emit({
            protocol: 1,
            kind: "goal",
            hole: err.obs.k,
            message: Bend.err_show(error),
            expected: Bend.expr_show(err.bok, err.exp, names),
            context,
            span,
          });
        }
      } else {
        emit({ protocol: 1, kind: "first-error", message: Bend.err_show(error), span });
      }
    } else {
      emit({ protocol: 1, kind: "unavailable" });
    }
  }
}

main().catch(() => emit({ protocol: 1, kind: "unavailable" }));
