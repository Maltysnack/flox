// Runtime domain types: River, Dam, RiverSystem.
// Mechanical port of River.java, Dam.java, RiverSystem.java.

export class River {
  dailyFlow: number[] = new Array(10).fill(0);
  totalAccumulated = 0;

  constructor(
    readonly name: string,
    readonly baseFlow: number,
    readonly peakMultiplier: number,
    readonly decayRate: number,
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

  processFlow(inflowLps: number): number {
    const inflowVolumeMl = inflowLps * Dam.L_PER_S_TO_ML_PER_DAY;
    this.currentLevel += inflowVolumeMl;

    let outflowLps: number;
    if (this.currentLevel >= this.capacity * this.threshold) {
      outflowLps = inflowLps * this.openRate;
    } else {
      outflowLps = inflowLps * this.normalRate;
    }

    let outflowVolumeMl = outflowLps * Dam.L_PER_S_TO_ML_PER_DAY;
    if (outflowVolumeMl > this.currentLevel) {
      outflowVolumeMl = this.currentLevel;
      outflowLps = outflowVolumeMl / Dam.L_PER_S_TO_ML_PER_DAY;
    }

    this.currentLevel -= outflowVolumeMl;
    if (this.currentLevel > this.capacity) this.currentLevel = this.capacity;

    return outflowLps;
  }

  reset(): void {
    this.currentLevel = this.capacity * 0.5;
  }
}

export type Mode = "river" | "markov";

export interface Edge {
  target: string;
  weight: number;
}

export class RiverSystem {
  mode: Mode = "river";
  rivers: Map<string, River> = new Map();
  dams: Map<string, Dam> = new Map();
  connections: Map<string, Edge[]> = new Map();
  roots: string[] = [];

  // Markov state
  initialMass: Map<string, number> = new Map();
  markovHistory: Map<string, number[]> = new Map();
  markovTicksRun = 0;
  markovConverged = false;

  constructor(readonly name: string) {}

  addRiver(river: River): void {
    this.rivers.set(river.name, river);
  }

  addDam(dam: Dam): void {
    this.dams.set(dam.name, dam);
  }

  addConnection(from: string, to: string, weight: number): void {
    let downstream = this.connections.get(from);
    if (!downstream) {
      downstream = [];
      this.connections.set(from, downstream);
    }
    downstream.push({ target: to, weight });
  }

  identifyRoots(): void {
    this.roots = [];
    const hasUpstream = new Set<string>();
    for (const edges of this.connections.values()) {
      for (const e of edges) hasUpstream.add(e.target);
    }
    for (const r of this.rivers.values()) {
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
        let flow = 0;

        for (const [from, edges] of this.connections) {
          for (const e of edges) {
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
    const nodes: string[] = [...this.rivers.keys(), ...this.dams.keys()];

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
          const d = (inDegree.get(e.target) ?? 0) - 1;
          inDegree.set(e.target, d);
          if (d === 0) queue.push(e.target);
        }
      }
    }
    return order;
  }
}
