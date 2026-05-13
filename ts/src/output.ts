// Output formatting. Mechanical port of OutputFormatter.java.
//
// Writes are accumulated in an `out` array of strings (each entry already
// includes any newline). The caller (CLI or interpret() API) joins and
// directs to stdout / a string buffer.

import { Dam, Edge, River, RiverSystem } from "./runtime.ts";

export class Output {
  constructor(private readonly out: string[]) {}

  println(s = ""): void {
    this.out.push(s + "\n");
  }
  print(s: string): void {
    this.out.push(s);
  }
}

export function printHelp(o: Output): void {
  o.println("=== FLOX SYNTAX REFERENCE ===\n");
  o.println("Declarations:");
  o.println("  river Name { base: N, peak: N, decay: N }   (decay optional, default 0.7)");
  o.println("  dam   Name { capacity: N, threshold: N%, normalRate: N, openRate: N }");
  o.println("  outlet Name;                                (zero-flow accumulator)\n");
  o.println("Connections:");
  o.println("  A -> B -> C;                                (bare names; declared anywhere)\n");
  o.println("Commands:");
  o.println("  system Name;                  - Create a system");
  o.println("  list Name;                    - Show connections list");
  o.println("  map Name;                     - Show network diagram");
  o.println("  print Name;                   - Simulate with 1mm rainfall on day 1");
  o.println("  print Name N;                 - Simulate with N mm on day 1");
  o.println("  print Name [r1, r2, r3, ...]; - Per-day rainfall series in mm");
  o.println("  help;                         - Show this message\n");
}

export function printList(o: Output, system: RiverSystem): void {
  o.println("");
  o.println("=== RIVER SYSTEM: " + system.name + " ===\n");
  o.println("Connections:");
  for (const [from, edges] of system.connections) {
    for (const e of edges) {
      const fromLabel = system.dams.has(from) ? `${from} [DAM]` : from;
      const toLabel = system.dams.has(e.target) ? `${e.target} [DAM]` : e.target;
      o.println("  " + padRight(fromLabel, 22) + " →  " + toLabel);
    }
  }
  o.println("");
}

export function printMap(o: Output, system: RiverSystem): void {
  o.println("");
  o.println("=== RIVER SYSTEM MAP: " + system.name + " ===\n");

  const finalOutlet = system.getFinalOutlet();
  for (const root of system.roots) {
    const path: string[] = [];
    tracePath(system, root, path, finalOutlet);
    printPath(o, system, path);
  }
  o.println("");
}

function tracePath(
  system: RiverSystem,
  node: string,
  path: string[],
  finalOutlet: string | null,
): void {
  path.push(node);
  if (node === finalOutlet) return;
  const downstream = system.connections.get(node);
  if (downstream && downstream.length > 0) {
    tracePath(system, downstream[0]!.target, path, finalOutlet);
  }
}

function printPath(o: Output, system: RiverSystem, path: string[]): void {
  for (let i = 0; i < path.length; i++) {
    const node = path[i]!;
    const isDam = system.dams.has(node);
    const river = system.rivers.get(node);
    const isOutlet = river ? river.isTerminator() : false;
    const indent = "  ".repeat(i);
    let label = node;
    if (isDam) label += " [DAM]";
    if (isOutlet) label += " [OUTLET]";
    if (i === 0) {
      o.println(indent + label + " (source)");
    } else {
      o.println(indent + "└──▶ " + label);
    }
  }
  o.println("");
}

export function printFlowTable(
  o: Output,
  system: RiverSystem,
  rainfall: number[],
): void {
  o.println("");
  o.println(
    "=== FLOW SIMULATION: " +
      system.name +
      " (" +
      formatRainfall(rainfall) +
      ") ===\n",
  );
  o.println("(Flow values represent water volume per day in L/s)\n");

  const finalOutlet = system.getFinalOutlet();

  // Pre-scan to compute column widths so big numbers and long node
  // names don't break the grid alignment.
  let valWidth = 5; // minimum, supports up to "999.9"
  let locWidth = 8; // minimum, supports "Location"
  for (const r of system.rivers.values()) {
    if (r.name === finalOutlet) continue;
    if (system.disabledNodes.has(r.name)) continue;
    if (r.name.length > locWidth) locWidth = r.name.length;
    for (let day = 0; day < 10; day++) {
      const w = fmtFixed(r.dailyFlow[day]!, 1).length;
      if (w > valWidth) valWidth = w;
    }
  }
  for (const d of system.dams.values()) {
    if (system.disabledNodes.has(d.name)) continue;
    if (d.name.length > locWidth) locWidth = d.name.length;
    for (let day = 0; day < 10; day++) {
      const w = fmtFixed(d.dailyOutflow[day]!, 1).length;
      if (w > valWidth) valWidth = w;
    }
  }
  if (finalOutlet !== null) {
    const outlet = system.rivers.get(finalOutlet);
    if (outlet) {
      const labelLen = (outlet.name + " [OUTLET]").length;
      if (labelLen > locWidth) locWidth = labelLen;
      let cumulative = 0;
      for (let day = 0; day < 10; day++) {
        cumulative += outlet.dailyFlow[day]!;
        const w = formatCumulative(cumulative).length;
        if (w > valWidth) valWidth = w;
      }
    }
  }

  // Each cell between pipes: 2 leading spaces + value + 1 trailing.
  const interior = valWidth + 3;
  const dashes = "-".repeat(interior);
  const locDashes = "-".repeat(locWidth);

  // Header row
  o.print(padRight("Location", locWidth));
  for (let day = 1; day <= 10; day++) {
    const dayStr = "Day " + padLeft(String(day), 2);
    o.print("|" + padCenter(dayStr, interior));
  }
  o.println("|");

  // Top separator
  o.print(locDashes + "|");
  for (let i = 0; i < 10; i++) o.print(dashes + "|");
  o.println("");

  for (const r of system.rivers.values()) {
    if (r.name === finalOutlet) continue;
    if (system.disabledNodes.has(r.name)) continue;
    o.print(padRight(r.name, locWidth));
    for (let day = 0; day < 10; day++) {
      o.print("|  " + padLeft(fmtFixed(r.dailyFlow[day]!, 1), valWidth) + " ");
    }
    o.println("|");
  }

  for (const d of system.dams.values()) {
    if (system.disabledNodes.has(d.name)) continue;
    o.print(padRight(d.name, locWidth));
    for (let day = 0; day < 10; day++) {
      o.print("|  " + padLeft(fmtFixed(d.dailyOutflow[day]!, 1), valWidth) + " ");
    }
    o.println("|");
  }

  if (finalOutlet !== null) {
    const outlet = system.rivers.get(finalOutlet);
    if (outlet) {
      o.print(locDashes + "|");
      for (let i = 0; i < 10; i++) o.print(dashes + "|");
      o.println("");
      o.println("Cumulative Outlet (converted to actual volume):");

      o.print(padRight(outlet.name + " [OUTLET]", locWidth));
      let cumulative = 0;
      for (let day = 0; day < 10; day++) {
        cumulative += outlet.dailyFlow[day]!;
        o.print("|  " + padLeft(formatCumulative(cumulative), valWidth) + " ");
      }
      o.println("|");

      const totalLitersPerDay = cumulative * 86400;
      const totalGigaliters = totalLitersPerDay / 1_000_000_000;
      o.println("");
      if (totalGigaliters >= 1.0) {
        o.println(
          "Total water accumulated at outlet: " +
            fmtFixed(totalGigaliters, 2) +
            " GL (gigaliters)",
        );
      } else if (totalGigaliters >= 0.001) {
        const totalMegaliters = totalLitersPerDay / 1_000_000;
        o.println(
          "Total water accumulated at outlet: " +
            fmtFixed(totalMegaliters, 2) +
            " ML (megaliters)",
        );
      } else if (totalGigaliters >= 0.000001) {
        const totalKiloliters = totalLitersPerDay / 1_000;
        o.println(
          "Total water accumulated at outlet: " +
            fmtFixed(totalKiloliters, 2) +
            " KL (kiloliters)",
        );
      } else {
        o.println(
          "Total water accumulated at outlet: " +
            fmtFixed(totalLitersPerDay, 1) +
            " L (liters)",
        );
      }
    }
  }

  o.println("");
}

export function printMarkovMap(o: Output, system: RiverSystem): void {
  o.println("");
  o.println("=== MARKOV CHAIN: " + system.name + " ===\n");

  const states = [...system.rivers.keys(), ...system.dams.keys()];
  o.println("States (" + states.length + "):");
  o.println("  " + states.join(", "));
  o.println("");

  o.println("Transitions:");
  for (const [src, outs] of system.connections) {
    for (let i = 0; i < outs.length; i++) {
      const e = outs[i]!;
      const w = formatWeight(e.weight);
      if (i === 0) {
        o.println("  " + src + " ─[" + w + "]─▶ " + e.target);
      } else {
        const pad = " ".repeat(src.length + 2);
        o.println(pad + "└[" + w + "]─▶ " + e.target);
      }
    }
  }
  o.println("");

  o.println("Outgoing weights per state:");
  for (const src of states) {
    const outs = system.connections.get(src);
    if (!outs || outs.length === 0) {
      o.println("  " + src + " absorbing");
      continue;
    }
    let sum = 0;
    for (const e of outs) sum += e.weight;
    const mark = Math.abs(sum - 1.0) < 1e-6 ? "  ✓" : "  ✗";
    o.println("  " + src + " → " + sum.toFixed(3) + mark);
  }
  o.println("");
}

export function printMarkovDistribution(o: Output, system: RiverSystem): void {
  const tickWord = system.markovTicksRun === 1 ? "tick" : "ticks";
  const status = system.markovConverged ? ", converged" : ", not yet converged";
  o.println("");
  o.println(
    "=== MARKOV DISTRIBUTION: " + system.name +
    " (" + system.markovTicksRun + " " + tickWord + status + ") ===\n",
  );

  let locWidth = 5;
  for (const s of system.markovHistory.keys()) {
    if (s.length > locWidth) locWidth = s.length;
  }

  o.println("Final distribution:");
  let total = 0;
  for (const [state, hist] of system.markovHistory) {
    const v = hist[system.markovTicksRun]!;
    total += v;
    o.println("  " + padRight(state, locWidth) + "  " + v.toFixed(4));
  }
  o.println("");
  o.println("Total mass: " + total.toFixed(4));
  o.println("");
}

export function printGridMap(o: Output, system: RiverSystem): void {
  o.println("");
  o.println("=== GRID: " + system.name + " ===\n");

  let nameWidth = 4;
  for (const n of system.rivers.keys()) {
    if (n.length > nameWidth) nameWidth = n.length;
  }

  o.println("Nodes (" + system.rivers.size + "):");
  for (const r of system.rivers.values()) {
    let tag: string;
    if (r.supply > 0) tag = "generator  " + signedFmt(r.supply);
    else if (r.supply < 0) tag = "load       " + signedFmt(r.supply);
    else tag = "relay";
    const suffix = system.disabledNodes.has(r.name) ? "  (disabled)" : "";
    o.println("  " + padRight(r.name, nameWidth) + "  " + tag + suffix);
  }
  o.println("");

  // Reduce parallel edges; ignore arrow direction. Edges incident on a
  // disabled node, or marked disabled, render with a "(disabled)" tag.
  const edgeC = new Map<string, number>();
  const edgeEnds = new Map<string, [string, string]>();
  const edgeDisabled = new Map<string, boolean>();
  for (const [from, edges] of system.connections) {
    for (const e of edges) {
      const key = from <= e.target ? `${from}|${e.target}` : `${e.target}|${from}`;
      edgeC.set(key, (edgeC.get(key) ?? 0) + e.weight);
      if (!edgeEnds.has(key)) edgeEnds.set(key, [from, e.target]);
      const disabled = !e.enabled
        || system.disabledNodes.has(from)
        || system.disabledNodes.has(e.target);
      edgeDisabled.set(key, (edgeDisabled.get(key) ?? false) || disabled);
    }
  }
  o.println("Edges (" + edgeC.size + "):");
  for (const [key, ends] of edgeEnds) {
    const c = edgeC.get(key)!;
    const suffix = edgeDisabled.get(key) ? "   (disabled)" : "";
    o.println(
      "  " + padRight(ends[0], nameWidth) + " <-> " +
      padRight(ends[1], nameWidth) + "   c=" + formatWeight(c) + suffix,
    );
  }
  o.println("");

  let sum = 0;
  for (const r of system.rivers.values()) {
    if (system.disabledNodes.has(r.name)) continue;
    sum += r.supply;
  }
  const tag = Math.abs(sum) < 1e-6 ? " ✓ (balanced)" : " ✗ (unbalanced)";
  o.println("Net supply: " + formatWeight(sum) + tag);
  o.println("");
}

export function printGridComparison(
  o: Output,
  original: RiverSystem,
  runs: RiverSystem[],
  labels: string[],
): void {
  o.println("");
  o.println("=== COMPARE: " + original.name + " ===\n");

  let labelW = "Scenarios".length;
  for (const l of labels) labelW = Math.max(labelW, l.length);
  o.println("Scenarios:");
  for (let i = 0; i < runs.length; i++) {
    const r = runs[i]!;
    const status = r.gridConverged ? "converged" : "not yet converged";
    o.println(
      "  " + padRight(labels[i]!, labelW) + "  " +
      r.gridTicksRun + " tick" + (r.gridTicksRun === 1 ? "" : "s") + ", " + status,
    );
  }
  o.println("");

  const colW: number[] = [];
  for (let i = 0; i < runs.length; i++) {
    let w = Math.max(8, labels[i]!.length);
    for (const v of runs[i]!.gridLevel.values()) w = Math.max(w, signedFmt(v).length);
    for (const v of runs[i]!.gridFlow.values()) w = Math.max(w, signedFmt(v).length);
    w = Math.max(w, "(off)".length);
    colW.push(w);
  }

  let rowLabelW = "Levels:".length;
  for (const n of original.rivers.keys()) rowLabelW = Math.max(rowLabelW, n.length + 2);
  for (const n of original.dams.keys()) rowLabelW = Math.max(rowLabelW, n.length + 2);
  for (const [from, edges] of original.connections) {
    for (const e of edges) {
      rowLabelW = Math.max(rowLabelW, (from + " -> " + e.target).length + 2);
    }
  }

  o.print(padRight("", rowLabelW));
  for (let i = 0; i < runs.length; i++) {
    o.print("  " + padLeft(labels[i]!, colW[i]!));
  }
  o.println("");

  o.println("Levels:");
  for (const node of original.rivers.keys()) {
    o.print(padRight("  " + node, rowLabelW));
    for (let i = 0; i < runs.length; i++) {
      const r = runs[i]!;
      let cell: string;
      if (r.disabledNodes.has(node)) cell = "(off)";
      else if (r.gridLevel.has(node)) cell = signedFmt(r.gridLevel.get(node)!);
      else cell = "—";
      o.print("  " + padLeft(cell, colW[i]!));
    }
    o.println("");
  }
  o.println("");

  o.println("Flows (positive = first-to-second direction):");
  for (const [from, edges] of original.connections) {
    for (const e of edges) {
      const label = "  " + from + " -> " + e.target;
      o.print(padRight(label, rowLabelW));
      const key = `${from}->${e.target}`;
      for (let i = 0; i < runs.length; i++) {
        const r = runs[i]!;
        let cell: string;
        if (r.disabledNodes.has(from) || r.disabledNodes.has(e.target)) cell = "(off)";
        else if (r.gridFlow.has(key)) cell = signedFmt(r.gridFlow.get(key)!);
        else cell = "(off)";
        o.print("  " + padLeft(cell, colW[i]!));
      }
      o.println("");
    }
  }
  o.println("");
}

export function printGridState(o: Output, system: RiverSystem): void {
  const tickWord = system.gridTicksRun === 1 ? "tick" : "ticks";
  const status = system.gridConverged ? ", converged" : ", not yet converged";
  o.println("");
  o.println(
    "=== GRID STATE: " + system.name +
    " (" + system.gridTicksRun + " " + tickWord + status + ") ===\n",
  );

  let nameWidth = 5;
  for (const n of system.gridLevel.keys()) {
    if (n.length > nameWidth) nameWidth = n.length;
  }

  o.println("Levels:");
  for (const [name, lvl] of system.gridLevel) {
    o.println("  " + padRight(name, nameWidth) + "  " + signedFmt(lvl));
  }
  o.println("");

  o.println("Flows (positive = first-to-second direction):");
  for (const [key, flow] of system.gridFlow) {
    const parts = key.split("->");
    const label = padRight(parts[0]!, nameWidth) + " -> " + padRight(parts[1]!, nameWidth);
    o.println("  " + label + "  " + signedFmt(flow));
  }
  o.println("");
}

function signedFmt(v: number): string {
  if (v > 0) return "+" + fmtFixed(v, 2);
  if (v < 0) return fmtFixed(v, 2);
  return " 0.00";
}

function formatWeight(w: number): string {
  if (w === Math.floor(w)) return w.toFixed(1);
  let s = w.toFixed(3);
  while (s.endsWith("0")) s = s.slice(0, -1);
  if (s.endsWith(".")) s += "0";
  return s;
}

// Render the cumulative outlet volume in the most appropriate unit
// (L, KL, ML, GL). Returns the value with its unit suffix; no padding.
function formatCumulative(cumulativeFlowLps: number): string {
  const litersPerDay = cumulativeFlowLps * 86400;
  const gigaliters = litersPerDay / 1_000_000_000;
  if (gigaliters >= 1.0) return fmtFixed(gigaliters, 2) + "GL";
  if (gigaliters >= 0.001) return fmtFixed(litersPerDay / 1_000_000, 2) + "ML";
  if (gigaliters >= 0.000001) return fmtFixed(litersPerDay / 1_000, 2) + "KL";
  return fmtFixed(litersPerDay, 1) + "L";
}

function padCenter(s: string, n: number): string {
  if (s.length >= n) return s.slice(0, n);
  const pad = n - s.length;
  const left = Math.floor(pad / 2);
  const right = pad - left;
  return " ".repeat(left) + s + " ".repeat(right);
}

function formatRainfall(rainfall: number[]): string {
  if (rainfall.length === 1) {
    return fmtFixed(rainfall[0]!, 1) + "mm rainfall on day 1";
  }
  const parts: string[] = [];
  for (const r of rainfall) parts.push(formatNumber(r));
  return "rainfall mm/day: [" + parts.join(", ") + "]";
}

function formatNumber(v: number): string {
  if (v === Math.floor(v) && isFinite(v)) return String(v);
  return fmtFixed(v, 1);
}

// Java-compatible %f formatter. Java's printf("%.1f", x) rounds the
// shortest round-trippable decimal representation of x (the same string
// you'd get from Double.toString or JS toString), not the underlying
// binary double. JS's toFixed rounds the binary double, which gives
// different results when the double's printed form has a trailing 5
// rounding digit (e.g. 29.449999999... prints as "29.45" but binary-rounds
// to "29.4"). This helper matches Java.
function fmtFixed(n: number, decimals: number): string {
  if (!isFinite(n)) return String(n);
  const s = Math.abs(n).toString();
  const sign = n < 0 ? "-" : "";
  const dotIdx = s.indexOf(".");
  const intStr = dotIdx < 0 ? s : s.slice(0, dotIdx);
  const fracStr = dotIdx < 0 ? "" : s.slice(dotIdx + 1);
  if (fracStr.length <= decimals) {
    return sign + intStr + (decimals > 0 ? "." + fracStr.padEnd(decimals, "0") : "");
  }
  const roundDigit = fracStr.charCodeAt(decimals) - 48; // '0' = 48
  let intPart = BigInt(intStr);
  let fracKept = decimals > 0 ? BigInt(fracStr.slice(0, decimals)) : 0n;
  const mag = 10n ** BigInt(decimals);
  if (roundDigit >= 5) {
    fracKept += 1n;
    if (fracKept >= mag) {
      intPart += 1n;
      fracKept = 0n;
    }
  }
  const fracOut = decimals > 0 ? "." + fracKept.toString().padStart(decimals, "0") : "";
  return sign + intPart.toString() + fracOut;
}

function padRight(s: string, n: number): string {
  if (s.length >= n) return s.slice(0, n);
  return s + " ".repeat(n - s.length);
}

function padLeft(s: string, n: number): string {
  if (s.length >= n) return s;
  return " ".repeat(n - s.length) + s;
}
