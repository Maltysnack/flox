// Runtime domain types: River, Dam, RiverSystem.
// Mechanical port of River.java, Dam.java, RiverSystem.java.

import { Token } from "./token.ts";

export class River {
  dailyFlow: number[] = new Array(10).fill(0);
  totalAccumulated = 0;

  constructor(
    readonly name: string,
    readonly baseFlow: number,
    readonly peakMultiplier: number,
    readonly decayRate: number,
    readonly supply: number = 0,
  ) {}

  // Flow on a given simulation day, given the full rainfall series so far.
  // Each non-zero entry rainfall[d] kicks off its own hydrograph; flow is
  // the superposition of every event that has happened up to (and including)
  // day. response(0) is the rising limb (0.3 of peak), response(1) is the
  // peak itself, response(k>=1) is decay^(k-1) of the peak.
  calculateFlow(day: number, rainfall: number[]): number {
    let total = this.baseFlow;
    for (let d = 0; d <= day && d < rainfall.length; d++) {
      const r = rainfall[d]!;
      if (r === 0) continue;
      const k = day - d;
      const factor = k === 0 ? 0.3 : Math.pow(this.decayRate, k - 1);
      total += r * this.peakMultiplier * factor;
    }
    return total;
  }

  isTerminator(): boolean {
    return this.baseFlow === 0 && this.peakMultiplier === 0;
  }
}

export class Dam {
  // 1 L/s sustained for one day = 86400 L = 0.0864 ML.
  // Storage is in ML; flow is in L/s. Convert at the boundary.
  private static readonly L_PER_S_TO_ML_PER_DAY = 0.0864;

  currentLevel: number;
  dailyOutflow: number[] = new Array(10).fill(0);

  constructor(
    readonly name: string,
    readonly capacity: number,
    readonly threshold: number, // fraction (0.8 = 80%)
    readonly normalRate: number,
    readonly openRate: number,
  ) {
    this.currentLevel = capacity * 0.5; // Start at 50% full
  }

  // Outflow is a fixed daily volume (ML/day): normalRate below
  // threshold, openRate at or above. Independent of inflow, matching
  // how a real reservoir releases on a schedule.
  processFlow(inflowLps: number): number {
    const inflowVolumeMl = inflowLps * Dam.L_PER_S_TO_ML_PER_DAY;
    this.currentLevel += inflowVolumeMl;

    let outflowVolumeMl = (this.currentLevel >= this.capacity * this.threshold)
      ? this.openRate
      : this.normalRate;
    if (outflowVolumeMl > this.currentLevel) outflowVolumeMl = this.currentLevel;

    this.currentLevel -= outflowVolumeMl;
    if (this.currentLevel > this.capacity) this.currentLevel = this.capacity;

    return outflowVolumeMl / Dam.L_PER_S_TO_ML_PER_DAY;
  }

  reset(): void {
    this.currentLevel = this.capacity * 0.5;
  }
}

export type Mode = "river" | "markov" | "grid" | "money" | "settle";

// Per-node typed metadata for money/settle modes. River nodes carry the
// graph topology (so existing iteration/edges work); this map holds the
// money/settle-specific fields keyed by node name.
export interface MoneyMeta {
  nodeType: "income" | "account" | "bill" | "pot";
  amount: number; // NaN when absent
  on: number;     // NaN when absent
  balance: number; // NaN when absent
  target: number;  // NaN when absent
  spread: boolean;
}

export interface MoneyAccountSummary {
  name: string;
  start: number;
  end: number;
  low: number;
  lowDay: number;
  overdraft: boolean;
}

export interface MoneyPotSummary {
  name: string;
  start: number;
  end: number;
  target: number;
}

export interface SettleTransfer {
  from: string;
  to: string;
  amount: number;
}

export interface Edge {
  target: string;
  weight: number;
  op: Token | null; // arrow token that declared this edge, for diagnostics
  enabled: boolean;
}

export class RiverSystem {
  mode: Mode = "river";
  rivers: Map<string, River> = new Map();
  dams: Map<string, Dam> = new Map();
  connections: Map<string, Edge[]> = new Map();
  roots: string[] = [];
  // Nodes explicitly disabled via 'disable' statements. Skipped by every
  // simulator; remain in rivers/dams so the visualisation can dim them.
  disabledNodes: Set<string> = new Set();
  // Marker set by `compare` so per-mode validators know to soften their
  // requirements (the run is exploring a scenario, not the canonical one).
  scenarioRun = false;
  unitLabels: Map<string, string> = new Map();

  // Markov state
  initialMass: Map<string, number> = new Map();
  markovHistory: Map<string, number[]> = new Map();
  markovTicksRun = 0;
  markovConverged = false;

  // Grid state
  gridLevel: Map<string, number> = new Map();
  gridFlow: Map<string, number> = new Map();   // "A->B" -> flow A→B
  gridTicksRun = 0;
  gridConverged = false;

  // Money state
  moneyMeta: Map<string, MoneyMeta> = new Map();
  moneySeries: Map<string, number[]> = new Map(); // per-day balance, accounts + pots
  moneyAccounts: MoneyAccountSummary[] = [];
  moneyPots: MoneyPotSummary[] = [];
  moneyDays = 0;
  moneyOverdraft = false;

  // Settle state
  people: string[] = [];
  settleNet: Map<string, number> = new Map();
  settleTransfers: SettleTransfer[] = [];

  constructor(readonly name: string) {}

  addMoneyMeta(name: string, meta: MoneyMeta): void {
    this.moneyMeta.set(name, meta);
  }

  addRiver(river: River): void {
    this.rivers.set(river.name, river);
  }

  addDam(dam: Dam): void {
    this.dams.set(dam.name, dam);
  }

  addConnection(from: string, to: string, weight: number, op: Token | null): void {
    let downstream = this.connections.get(from);
    if (!downstream) {
      downstream = [];
      this.connections.set(from, downstream);
    }
    downstream.push({ target: to, weight, op, enabled: true });
  }

  identifyRoots(): void {
    this.roots = [];
    const hasUpstream = new Set<string>();
    for (const edges of this.connections.values()) {
      for (const e of edges) {
        if (!e.enabled) continue;
        hasUpstream.add(e.target);
      }
    }
    for (const r of this.rivers.values()) {
      if (this.disabledNodes.has(r.name)) continue;
      if (!hasUpstream.has(r.name) && !r.isTerminator()) {
        this.roots.push(r.name);
      }
    }
  }

  getFinalOutlet(): string | null {
    for (const river of this.rivers.values()) {
      if (river.isTerminator()) {
        const downstream = this.connections.get(river.name);
        if (!downstream || downstream.length === 0) return river.name;
      }
    }
    return null;
  }

  simulate(rainfall: number[]): void {
    for (const dam of this.dams.values()) dam.reset();
    for (const river of this.rivers.values()) river.totalAccumulated = 0;

    for (let day = 0; day < 10; day++) {
      const flowThisDay = new Map<string, number>();

      for (const node of this.getTopologicalOrder()) {
        if (this.disabledNodes.has(node)) continue;
        let flow = 0;

        for (const [from, edges] of this.connections) {
          for (const e of edges) {
            if (!e.enabled) continue;
            if (e.target === node) {
              flow += (flowThisDay.get(from) ?? 0) * e.weight;
            }
          }
        }

        const river = this.rivers.get(node);
        if (river && !river.isTerminator()) {
          flow += river.calculateFlow(day, rainfall);
        }

        const dam = this.dams.get(node);
        if (dam) {
          flow = dam.processFlow(flow);
          dam.dailyOutflow[day] = flow;
        } else if (river) {
          river.dailyFlow[day] = flow;
          if (river.isTerminator()) river.totalAccumulated += flow;
        }

        flowThisDay.set(node, flow);
      }
    }
  }

  // Markov-mode simulation: row-stochastic transition matrix induced by
  // edge weights, applied iteratively to the state vector. Halts at
  // maxTicks or when the state stops changing within EPSILON.
  simulateMarkov(maxTicks: number): void {
    const EPSILON = 1e-9;
    const nodes: string[] = [];
    for (const n of this.rivers.keys()) if (!this.disabledNodes.has(n)) nodes.push(n);
    for (const n of this.dams.keys())   if (!this.disabledNodes.has(n)) nodes.push(n);

    let mass = new Map<string, number>();
    for (const n of nodes) mass.set(n, this.initialMass.get(n) ?? 0);

    this.markovHistory.clear();
    for (const n of nodes) {
      const hist: number[] = new Array(maxTicks + 1).fill(0);
      hist[0] = mass.get(n)!;
      this.markovHistory.set(n, hist);
    }
    this.markovTicksRun = 0;
    this.markovConverged = false;

    for (let t = 1; t <= maxTicks; t++) {
      const next = new Map<string, number>();
      for (const n of nodes) next.set(n, 0);

      for (const src of nodes) {
        const outs = this.connections.get(src);
        if (!outs || outs.length === 0) {
          next.set(src, (next.get(src) ?? 0) + (mass.get(src) ?? 0));
          continue;
        }
        for (const e of outs) {
          if (!e.enabled) continue;
          if (!next.has(e.target)) continue;
          next.set(e.target, (next.get(e.target) ?? 0) + (mass.get(src) ?? 0) * e.weight);
        }
      }

      let maxDiff = 0;
      for (const n of nodes) {
        maxDiff = Math.max(maxDiff, Math.abs(next.get(n)! - mass.get(n)!));
        this.markovHistory.get(n)![t] = next.get(n)!;
      }
      mass = next;
      this.markovTicksRun = t;

      if (maxDiff < EPSILON) {
        this.markovConverged = true;
        break;
      }
    }
  }

  // Grid-mode simulation. Each undirected edge {A,B} with conductance c
  // carries flow (level[A] - level[B]) * c. Each node has a 'supply'
  // (positive = injects, negative = draws). Levels integrate
  // (supply + net_inflow) over time with a damping factor dt.
  simulateGrid(maxTicks: number): void {
    const EPSILON = 1e-7;

    const nodes: string[] = [];
    for (const n of this.rivers.keys()) if (!this.disabledNodes.has(n)) nodes.push(n);
    for (const n of this.dams.keys())   if (!this.disabledNodes.has(n)) nodes.push(n);

    // Reduce parallel edges and ignore arrow direction (grid mode is
    // bidirectional). edgeKey = "alphabetically-first|alphabetically-second".
    const edgeC = new Map<string, number>();
    const edgeEnds = new Map<string, [string, string]>();
    for (const [from, edges] of this.connections) {
      for (const e of edges) {
        if (!e.enabled) continue;
        const key = from <= e.target ? `${from}|${e.target}` : `${e.target}|${from}`;
        edgeC.set(key, (edgeC.get(key) ?? 0) + e.weight);
        if (!edgeEnds.has(key)) edgeEnds.set(key, [from, e.target]);
      }
    }

    // Adaptive damping. Jacobi iteration is stable when dt < 2 / max
    // Laplacian eigenvalue; that eigenvalue is bounded by 2 * max
    // row-sum (sum of conductances incident on any node). So
    // dt = 0.5 / maxNodeSum is safely stable.
    const nodeSum = new Map<string, number>();
    for (const [key, ends] of edgeEnds) {
      const c = edgeC.get(key)!;
      nodeSum.set(ends[0], (nodeSum.get(ends[0]) ?? 0) + c);
      nodeSum.set(ends[1], (nodeSum.get(ends[1]) ?? 0) + c);
    }
    let maxNodeSum = 0;
    for (const v of nodeSum.values()) maxNodeSum = Math.max(maxNodeSum, v);
    const DT = maxNodeSum > 0 ? Math.min(0.1, 0.5 / maxNodeSum) : 0.1;

    this.gridLevel.clear();
    for (const n of nodes) this.gridLevel.set(n, 0);
    this.gridTicksRun = 0;
    this.gridConverged = false;

    for (let t = 1; t <= maxTicks; t++) {
      const delta = new Map<string, number>();
      for (const n of nodes) delta.set(n, 0);

      for (const n of nodes) {
        const r = this.rivers.get(n);
        const supply = r ? r.supply : 0;
        delta.set(n, delta.get(n)! + supply);
      }

      for (const [key, ends] of edgeEnds) {
        const c = edgeC.get(key)!;
        const flow = (this.gridLevel.get(ends[0])! - this.gridLevel.get(ends[1])!) * c;
        delta.set(ends[0], delta.get(ends[0])! - flow);
        delta.set(ends[1], delta.get(ends[1])! + flow);
      }

      let maxDiff = 0;
      for (const n of nodes) {
        const d = DT * delta.get(n)!;
        maxDiff = Math.max(maxDiff, Math.abs(d));
        this.gridLevel.set(n, this.gridLevel.get(n)! + d);
      }
      this.gridTicksRun = t;

      if (maxDiff < EPSILON) {
        this.gridConverged = true;
        break;
      }
    }

    this.gridFlow.clear();
    for (const [key, ends] of edgeEnds) {
      const c = edgeC.get(key)!;
      const flow = (this.gridLevel.get(ends[0])! - this.gridLevel.get(ends[1])!) * c;
      this.gridFlow.set(`${ends[0]}->${ends[1]}`, flow);
    }
  }

  // Money-mode simulation. Days d = 1..N. Income deposits into the
  // accounts it points to; bills are drawn from the accounts that point
  // to them; pots receive transfers from the account that points to them.
  // Builds a per-day balance series for every account and pot.
  simulateMoney(days: number): void {
    const N = Math.max(1, Math.round(days));
    this.moneyDays = N;
    this.moneySeries.clear();
    this.moneyAccounts = [];
    this.moneyPots = [];
    this.moneyOverdraft = false;

    const meta = this.moneyMeta;
    const accounts: string[] = [];
    const pots: string[] = [];
    for (const [name, m] of meta) {
      if (this.disabledNodes.has(name)) continue;
      if (m.nodeType === "account") accounts.push(name);
      else if (m.nodeType === "pot") pots.push(name);
    }

    // Running balances and per-day series (1-indexed by day; store N entries).
    const balance = new Map<string, number>();
    const series = new Map<string, number[]>();
    const startBal = new Map<string, number>();
    const low = new Map<string, number>();
    const lowDay = new Map<string, number>();

    for (const a of accounts) {
      const b = Number.isNaN(meta.get(a)!.balance) ? 0 : meta.get(a)!.balance;
      balance.set(a, b);
      startBal.set(a, b);
      series.set(a, new Array(N).fill(0));
      low.set(a, Infinity);
      lowDay.set(a, 1);
    }
    for (const p of pots) {
      const b = Number.isNaN(meta.get(p)!.balance) ? 0 : meta.get(p)!.balance;
      balance.set(p, b);
      startBal.set(p, b);
      series.set(p, new Array(N).fill(0));
    }

    // Helper: accounts that an income/pot node connects to, or accounts
    // that connect into a bill node.
    const targetsOf = (node: string): string[] => {
      const outs = this.connections.get(node);
      if (!outs) return [];
      return outs
        .filter(e => e.enabled && accounts.includes(e.target))
        .map(e => e.target);
    };
    const sourcesInto = (node: string): string[] => {
      const res: string[] = [];
      for (const [from, edges] of this.connections) {
        if (!accounts.includes(from)) continue;
        for (const e of edges) {
          if (e.enabled && e.target === node) { res.push(from); break; }
        }
      }
      return res;
    };

    for (let day = 1; day <= N; day++) {
      // Income deposits.
      for (const [name, m] of meta) {
        if (this.disabledNodes.has(name) || m.nodeType !== "income") continue;
        const on = Number.isNaN(m.on) ? 1 : m.on;
        if (on !== day) continue;
        const amount = Number.isNaN(m.amount) ? 0 : m.amount;
        const dests = targetsOf(name);
        if (dests.length === 0) continue;
        const each = amount / dests.length;
        for (const a of dests) balance.set(a, balance.get(a)! + each);
      }

      // Bills (withdrawals).
      for (const [name, m] of meta) {
        if (this.disabledNodes.has(name) || m.nodeType !== "bill") continue;
        const amount = Number.isNaN(m.amount) ? 0 : m.amount;
        let due = 0;
        if (m.spread) {
          due = amount / N;
        } else {
          const on = Number.isNaN(m.on) ? 1 : m.on;
          if (on === day) due = amount;
        }
        if (due === 0) continue;
        const srcs = sourcesInto(name);
        if (srcs.length === 0) continue;
        const each = due / srcs.length;
        for (const a of srcs) balance.set(a, balance.get(a)! - each);
      }

      // Pot transfers (account -> pot).
      for (const [name, m] of meta) {
        if (this.disabledNodes.has(name) || m.nodeType !== "pot") continue;
        const on = Number.isNaN(m.on) ? 1 : m.on;
        if (on !== day) continue;
        const amount = Number.isNaN(m.amount) ? 0 : m.amount;
        if (amount === 0) continue;
        const srcs = sourcesInto(name);
        if (srcs.length === 0) continue;
        const each = amount / srcs.length;
        for (const a of srcs) balance.set(a, balance.get(a)! - each);
        balance.set(name, balance.get(name)! + amount);
      }

      // Snapshot balances for the day; track lows for accounts.
      for (const a of accounts) {
        const v = balance.get(a)!;
        series.get(a)![day - 1] = v;
        if (v < low.get(a)!) { low.set(a, v); lowDay.set(a, day); }
        if (v < 0) this.moneyOverdraft = true;
      }
      for (const p of pots) {
        series.get(p)![day - 1] = balance.get(p)!;
      }
    }

    for (const [name, s] of series) this.moneySeries.set(name, s);
    for (const a of accounts) {
      const overdraft = low.get(a)! < 0;
      this.moneyAccounts.push({
        name: a,
        start: startBal.get(a)!,
        end: balance.get(a)!,
        low: low.get(a)!,
        lowDay: lowDay.get(a)!,
        overdraft,
      });
    }
    for (const p of pots) {
      this.moneyPots.push({
        name: p,
        start: startBal.get(p)!,
        end: balance.get(p)!,
        target: Number.isNaN(meta.get(p)!.target) ? 0 : meta.get(p)!.target,
      });
    }
  }

  // Settle-mode: compute per-person net (owed - owes) from the declared
  // debt edges, then greedily match the largest creditor with the largest
  // debtor to produce a minimal set of transfers.
  simulateSettle(): void {
    this.settleNet.clear();
    this.settleTransfers = [];

    for (const p of this.people) this.settleNet.set(p, 0);

    for (const [from, edges] of this.connections) {
      for (const e of edges) {
        if (!e.enabled) continue;
        // from owes e.target: from is debtor (-), target is creditor (+).
        if (this.settleNet.has(from)) {
          this.settleNet.set(from, this.settleNet.get(from)! - e.weight);
        }
        if (this.settleNet.has(e.target)) {
          this.settleNet.set(e.target, this.settleNet.get(e.target)! + e.weight);
        }
      }
    }

    // Greedy minimal settlement. Deterministic: sort by net then name.
    const EPS = 1e-9;
    const work = [...this.settleNet.entries()].map(([name, net]) => ({ name, net }));
    const sortStable = () =>
      work.sort((a, b) => (a.net - b.net) || (a.name < b.name ? -1 : a.name > b.name ? 1 : 0));

    while (true) {
      sortStable();
      const debtor = work[0]!;            // most negative
      const creditor = work[work.length - 1]!; // most positive
      if (creditor.net <= EPS || debtor.net >= -EPS) break;
      const amount = Math.min(creditor.net, -debtor.net);
      this.settleTransfers.push({ from: debtor.name, to: creditor.name, amount });
      creditor.net -= amount;
      debtor.net += amount;
    }
  }

  private getTopologicalOrder(): string[] {
    const order: string[] = [];
    const inDegree = new Map<string, number>();
    const allNodes = new Set<string>([
      ...this.rivers.keys(),
      ...this.dams.keys(),
    ]);
    for (const n of allNodes) inDegree.set(n, 0);
    for (const edges of this.connections.values()) {
      for (const e of edges) {
        if (!e.enabled) continue;
        inDegree.set(e.target, (inDegree.get(e.target) ?? 0) + 1);
      }
    }
    const queue: string[] = [];
    for (const [n, d] of inDegree) {
      if (d === 0) queue.push(n);
    }
    while (queue.length > 0) {
      const node = queue.shift()!;
      order.push(node);
      const edges = this.connections.get(node);
      if (edges) {
        for (const e of edges) {
          if (!e.enabled) continue;
          const d = (inDegree.get(e.target) ?? 0) - 1;
          inDegree.set(e.target, d);
          if (d === 0) queue.push(e.target);
        }
      }
    }
    return order;
  }
}
