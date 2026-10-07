// Original optional prototype rule. These are successful checker observations,
// not an inferred remaining-resource state and not proof completion.
export const rules = [{
  id: "idea/compiler-observation",
  facts: { scope: "program", kinds: ["Var"] },
  run(cx) {
    const findings = [];
    for (const fact of cx.facts.values()) {
      if (fact.inst || !fact.spn) continue;
      const demand = fact.qt.$ === "None" ? "erased" : "live";
      findings.push(cx.diag({
        severity: "information",
        message: `Compiler type: ${cx.show(fact, fact.ty).slice(0, 256)}; checked demand: ${demand}`,
        spn: fact.spn,
        fact,
      }));
      if (findings.length === 128) break;
    }
    return findings;
  },
}];
