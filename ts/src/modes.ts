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
} from "./output.ts";
import { Mode, RiverSystem } from "./runtime.ts";

export interface ModeStrategy {
  validate(s: RiverSystem): void;
  run(s: RiverSystem, arg: number[]): void;
  defaultArg(): number[];
  printMap(o: Output, s: RiverSystem): void;
  printResult(o: Output, s: RiverSystem, arg: number[]): void;
}

const river: ModeStrategy = {
  validate(_s) { /* no-op */ },
  run(s, arg) { s.simulate(arg); },
  defaultArg() { return [1.0]; },
  printMap(o, s) { printRiverMap(o, s); },
  printResult(o, s, arg) { printFlowTable(o, s, arg); },
};

const markov: ModeStrategy = {
  validate(s) {
    for (const name of s.initialMass.keys()) {
      if (!s.rivers.has(name) && !s.dams.has(name)) {
        throw new Error(
          `start references undeclared node '${name}' in system '${s.name}'.`,
        );
      }
    }
    let totalInitial = 0;
    for (const m of s.initialMass.values()) totalInitial += m;
    if (totalInitial === 0) {
      throw new Error(
        `Markov system '${s.name}' has no initial mass. Use 'start NodeName 1.0;' to set one.`,
      );
    }
    for (const [src, edges] of s.connections) {
      let sum = 0;
      const lines: number[] = [];
      for (const e of edges) {
        sum += e.weight;
        if (e.op) lines.push(e.op.line);
      }
      if (Math.abs(sum - 1.0) > 1e-6) {
        const where = lines.length > 0
          ? ` (${lines.map(l => "line " + l).join(", ")})`
          : "";
        throw new Error(
          `Markov system '${s.name}': outgoing weights from '${src}'${where} sum to ${sum}, not 1.0.`,
        );
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
  validate(s) {
    let total = 0;
    let hasSupply = false;
    for (const r of s.rivers.values()) {
      total += r.supply;
      if (r.supply !== 0) hasSupply = true;
    }
    if (!hasSupply) {
      throw new Error(
        `Grid system '${s.name}' has no supply set on any node. ` +
        `Use 'node X { supply: N }' for generators (+) or loads (-).`,
      );
    }
    if (Math.abs(total) > 1e-6) {
      throw new Error(
        `Grid system '${s.name}': supplies do not balance (sum = ${total}). ` +
        `Generators (+) and loads (-) must sum to zero.`,
      );
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

export const STRATEGIES: Record<Mode, ModeStrategy> = { river, markov, grid };
