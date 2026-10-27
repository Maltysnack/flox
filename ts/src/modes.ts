// Per-mode strategies. Each mode declares how to validate, run, and
// render a system of its kind. The interpreter dispatches via
// STRATEGIES[system.mode] instead of branching on the mode string.
//
// Mirror of the Mode enum in src/flox/RiverSystem.java.

import {
  Output,
  printFlowTable,
  printGridMap,
  printGridState,
  printMap as printRiverMap,
  printMarkovDistribution,
  printMarkovMap,
  printMoneyMap,
  printMoneyResult,
  printSettleMap,
  printSettleResult,
} from "./output.ts";
import { Mode, RiverSystem } from "./runtime.ts";

export interface ModeStrategy {
  validate(s: RiverSystem, errs: string[]): void;
  run(s: RiverSystem, arg: number[]): void;
  defaultArg(): number[];
  printMap(o: Output, s: RiverSystem): void;
  printResult(o: Output, s: RiverSystem, arg: number[]): void;
}

const river: ModeStrategy = {
  validate(_s, _errs) { /* no-op */ },
  run(s, arg) { s.simulate(arg); },
  defaultArg() { return [1.0]; },
  printMap(o, s) { printRiverMap(o, s); },
  printResult(o, s, arg) { printFlowTable(o, s, arg); },
};

const markov: ModeStrategy = {
  validate(s, errs) {
    for (const name of s.initialMass.keys()) {
      if (!s.rivers.has(name) && !s.dams.has(name)) {
        throw new Error(
          `start references undeclared node '${name}' in system '${s.name}'.`,
        );
      }
    }
    let totalInitial = 0;
    for (const [name, m] of s.initialMass) {
      if (s.disabledNodes.has(name)) continue;
      totalInitial += m;
    }
    if (totalInitial === 0) {
      throw new Error(
        `Markov system '${s.name}' has no initial mass on any active node. Use 'start NodeName 1.0;' to set one.`,
      );
    }
    // Per-source sum-to-1, considering only enabled edges. If any
    // element is disabled the user is exploring a scenario; soften
    // mismatches to a warning rather than an error.
    let softened = s.scenarioRun || s.disabledNodes.size > 0;
    if (!softened) {
      for (const edges of s.connections.values()) {
        for (const e of edges) if (!e.enabled) { softened = true; break; }
        if (softened) break;
      }
    }
    for (const [src, edges] of s.connections) {
      if (s.disabledNodes.has(src)) continue;
      let sum = 0;
      let anyEnabled = false;
      const lines: number[] = [];
      for (const e of edges) {
        if (!e.enabled) continue;
        anyEnabled = true;
        sum += e.weight;
        if (e.op) lines.push(e.op.line);
      }
      if (!anyEnabled) continue;
      if (Math.abs(sum - 1.0) > 1e-6) {
        const where = lines.length > 0
          ? ` (${lines.map(l => "line " + l).join(", ")})`
          : "";
        const msg = `Markov system '${s.name}': outgoing weights from '${src}'${where} sum to ${sum}, not 1.0.`;
        if (softened) {
          errs.push(`Note (scenario): ${msg}\n`);
        } else {
          throw new Error(msg);
        }
      }
    }
  },
  run(s, arg) {
    const ticks = arg.length > 0 ? Math.max(1, Math.round(arg[0]!)) : 30;
    s.simulateMarkov(ticks);
  },
  defaultArg() { return [30]; },
  printMap(o, s) { printMarkovMap(o, s); },
  printResult(o, s, _arg) { printMarkovDistribution(o, s); },
};

const grid: ModeStrategy = {
  validate(s, errs) {
    let total = 0;
    let hasSupply = false;
    for (const r of s.rivers.values()) {
      if (s.disabledNodes.has(r.name)) continue;
      total += r.supply;
      if (r.supply !== 0) hasSupply = true;
    }
    if (!hasSupply) {
      throw new Error(
        `Grid system '${s.name}' has no supply set on any active node. ` +
        `Use 'node X { supply: N }' for generators (+) or loads (-).`,
      );
    }
    let softened = s.scenarioRun || s.disabledNodes.size > 0;
    if (!softened) {
      for (const edges of s.connections.values()) {
        for (const e of edges) if (!e.enabled) { softened = true; break; }
        if (softened) break;
      }
    }
    if (Math.abs(total) > 1e-6) {
      const msg = `Grid system '${s.name}': supplies do not balance (sum = ${formatTotal(total)} after disabling). ` +
        `Generators (+) and loads (-) must sum to zero.`;
      if (softened) {
        errs.push(`Note (scenario): ${msg}\n`);
      } else {
        throw new Error(msg);
      }
    }
  },
  run(s, arg) {
    const ticks = arg.length > 0 ? Math.max(1, Math.round(arg[0]!)) : 500;
    s.simulateGrid(ticks);
  },
  defaultArg() { return [500]; },
  printMap(o, s) { printGridMap(o, s); },
  printResult(o, s, _arg) { printGridState(o, s); },
};

const money: ModeStrategy = {
  validate(s, errs) {
    const accounts = new Set<string>();
    for (const [name, m] of s.moneyMeta) {
      if (m.nodeType === "account") accounts.add(name);
    }
    // A node is connected to an account if it points to one (income/pot)
    // or an account points to it (bill).
    for (const [name, m] of s.moneyMeta) {
      if (m.nodeType === "account") continue;
      let connected = false;
      const outs = s.connections.get(name);
      if (outs) for (const e of outs) if (accounts.has(e.target)) { connected = true; break; }
      if (!connected) {
        for (const [from, edges] of s.connections) {
          if (!accounts.has(from)) continue;
          for (const e of edges) if (e.target === name) { connected = true; break; }
          if (connected) break;
        }
      }
      if (!connected) {
        errs.push(
          `Note: ${m.nodeType} '${name}' in budget '${s.name}' is not connected to any account.\n`,
        );
      }
    }
  },
  run(s, arg) {
    const days = arg.length > 0 ? arg[0]! : 30;
    s.simulateMoney(days);
  },
  defaultArg() { return [30]; },
  printMap(o, s) { printMoneyMap(o, s); },
  printResult(o, s, _arg) { printMoneyResult(o, s); },
};

const settle: ModeStrategy = {
  validate(_s, _errs) { /* no hard requirement */ },
  run(s, _arg) { s.simulateSettle(); },
  defaultArg() { return []; },
  printMap(o, s) { printSettleMap(o, s); },
  printResult(o, s, _arg) { printSettleResult(o, s); },
};

// Java prints "-100.0" for a whole double; mirror that in TS so the
// stderr messages compare byte-identical across backends.
function formatTotal(n: number): string {
  return Number.isInteger(n) ? n.toFixed(1) : String(n);
}

export const STRATEGIES: Record<Mode, ModeStrategy> = { river, markov, grid, money, settle };
