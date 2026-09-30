#!/usr/bin/env bun
// Protocol 1 exposes the first error from the pinned Bend 2.0.25 sources.
// Check protocol 1 is independent of the optional semantic protocol.
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
  if (operation !== "capabilities" && operation !== "check-capabilities" && operation !== "diagnostic" && operation !== "goal" && operation !== "types" && operation !== "compare" && operation !== "normalize" && operation !== "check") {
    emit({ protocol: 1, error: "unsupported operation" });
    return;
  }
  if (!compilerDir || (operation !== "capabilities" && operation !== "check-capabilities" && !root)) {
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
    emit(operation === "check-capabilities" || operation === "check"
      ? { checkProtocol: 0, compiler: "unsupported", operations: [] }
      : { protocol: 1, compiler: "unsupported", operations: [] });
    return;
  }
  if (operation === "check-capabilities") {
    emit({ checkProtocol: 1, compiler: "bend-2.0.25-pinned", checkOnly: true,
      locations: "compiler-source-utf16", operations: ["check"] });
    return;
  }
  if (operation === "capabilities") {
    emit({ protocol: 1, compiler: "bend-2.0.25-pinned", operations: ["diagnostic", "goal-first-named-hole", "expression-types-checked", "goal-binder-comparison", "normalize-closed-expression"] });
    return;
  }
  const Bend = await import(url.pathToFileURL(path.join(dir, "bend.ts")).href);
  const Comp = await import(url.pathToFileURL(path.join(dir, "comp.ts")).href);
  if (operation === "check") {
    await check(Bend, Comp, root);
    return;
  }
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

// Mirrors the pinned CLI book_read and reliance walk. Hash negotiation above
// prevents using these private-version assumptions with a different compiler.
async function check(Bend: any, Comp: any, root: string): Promise<void> {
  const report = (outcome: string, completeness: string, reliance: string,
    details: string, diagnostics: unknown[] = [], incompleteKind?: string) =>
    emit({ checkProtocol: 1, outcome, completeness, reliance, details, diagnostics, incompleteKind });
  try {
    const book = Bend.book_nil();
    const seen = new Map<string, string | null>();
    const n0 = await Bend.book_load(book, root, "", seen);
    const laws = path.join(path.dirname(root), "LAWS.bend");
    if (path.basename(root) === "PROOF.bend" && fs.existsSync(laws) && !seen.has(fs.realpathSync(laws))) {
      const message = "PROOF.bend must import ./LAWS.bend";
      report("failed", "unknown", "unknown", message, [{ message }]);
      return;
    }
    Bend.book_valid(book);
    Comp.book_owned(book, Comp.SYNTH);
    if (book.hols + book.open > 0) {
      const message = `${book.hols + book.open} TODOs found. The code is incomplete.`;
      report("failed", "incomplete", "unknown", message, [{ message }], "todo");
      return;
    }
    const own = [...new Set<string>(book.order.slice(n0))];
    const bad = new Set<string>(Object.keys(book.tlds).filter(k => {
      const t = book.tlds[k];
      return t.u === true || (t.i !== undefined && t.b !== true);
    }));
    const uses: Record<string, string[]> = Object.create(null);
    const visited = new Set<string>();
    function refs(term: any, out: Set<string>): void {
      if (typeof term !== "object" || term === null) return;
      if ((term.$ === "Ref" || term.$ === "ADT") && term.k !== undefined) out.add(term.k);
      for (const [key, child] of Object.entries(term)) if (key !== "s") refs(child, out);
    }
    for (const pending = bad.size === 0 ? [] : own.slice(); pending.length > 0;) {
      const name = pending.pop()!;
      const declaration = book.tlds[name];
      if (declaration !== undefined && !visited.has(name)) {
        visited.add(name);
        const references = new Set<string>();
        for (const c of declaration.$ === "ADT" ? declaration.c : [declaration]) refs(Bend.term_lower(c.T), references);
        refs(declaration.$ === "Def" ? declaration.e : undefined, references);
        for (const ref of references) { (uses[ref] ??= []).push(name); pending.push(ref); }
      }
    }
    for (const name of bad) uses[name]?.forEach(user => bad.add(user));
    const reliance = own.some(name => bad.has(name)) ? "unsafe-or-foreign" : "none";
    report("success", "complete", reliance,
      reliance === "none" ? "Bend check complete." : "Bend check complete; relies on unsafe or foreign code.");
  } catch (error) {
    const structured = error && typeof error === "object" && "$" in error && error.$ === "Err";
    const message = structured ? Bend.err_show(error) : String(error);
    const err = error as any;
    const span = structured && err.spn && typeof err.spn.src === "string" &&
      Number.isInteger(err.spn.beg) && Number.isInteger(err.spn.end) && err.spn.beg >= 0 &&
      err.spn.end > err.spn.beg && err.spn.end <= err.spn.src.length
      ? { source: err.spn.src, start: err.spn.beg, end: err.spn.end } : undefined;
    const named = structured && err.obs?.$ === "Hol" && err.obs.k !== "TODO";
    report("failed", named ? "incomplete" : "unknown", "unknown", message, [{ message, span }], named ? "named-hole" : undefined);
  }
}

main().catch(() => emit({ protocol: 1, kind: "unavailable" }));
