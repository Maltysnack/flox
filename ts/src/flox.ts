// Public entry point. Run a Flox program and capture its output.

import { Interpreter } from "./interpreter.ts";
import { STRATEGIES } from "./modes.ts";
import { Parser } from "./parser.ts";
import { Mode, River, RiverSystem } from "./runtime.ts";
import { Scanner } from "./scanner.ts";

export interface InterpretResult {
  stdout: string;
  stderr: string;
  hadError: boolean;
}

// Visual-layer types returned by parseGraph(). Decoupled from the
// runtime classes so the playground can render without importing
// River/Dam/RiverSystem directly.
export type NodeKind =
  | "river"      // river-mode source / intermediate
  | "dam"        // river-mode dam
  | "outlet"     // river-mode terminator
  | "state"      // markov-mode state
  | "generator"  // grid-mode supply > 0
  | "load"       // grid-mode supply < 0
  | "relay"      // grid-mode supply == 0 with edges
  | "income"     // money-mode income node
  | "account"    // money-mode account node
  | "bill"       // money-mode bill node
  | "pot"        // money-mode pot node
  | "person";    // settle-mode person node

export interface GraphNode {
  name: string;
  kind: NodeKind;
  supply: number;
  disabled: boolean;
  // River-mode only: per-day flow series captured from the simulator
  // (10 entries by default). Populated by parseGraph so the playground
  // can draw a hydrograph alongside the topology. Undefined for
  // non-river modes or when the simulation could not be run.
  series?: number[];
}

export interface GraphEdge {
  from: string;
  to: string;
  weight: number;
  directed: boolean;
  disabled: boolean;
}

export type GraphOverride =
  | { kind: "disable-node"; name: string }
  | { kind: "disable-edge"; from: string; to: string }
  | { kind: "supply"; name: string; value: number };

export interface GraphScenario {
  label: string;
  overrides: GraphOverride[];
  // Per-scenario simulation results. Populated by parseGraph after the
  // scenarios are evaluated; lets the renderer label nodes with their
  // equilibrium level and edges with their flow magnitude.
  levels?: Record<string, number>;
  flows?: Record<string, number>;
  ticksRun?: number;
  converged?: boolean;
}

export interface SystemGraph {
  name: string;
  mode: Mode;
  nodes: GraphNode[];
  edges: GraphEdge[];
  // Populated when the source contains `compare` statements for this
  // system. The renderer uses this to draw N small graphs instead of
  // one big one.
  scenarios?: GraphScenario[];
}

export interface ParseGraphResult {
  systems: SystemGraph[];
  hadError: boolean;
  stderr: string;
}

export function interpret(source: string): InterpretResult {
  const out: string[] = [];
  const errs: string[] = [];

  const scanner = new Scanner(source, errs);
  const tokens = scanner.scanTokens();
  if (errs.length > 0) {
    return { stdout: out.join(""), stderr: errs.join(""), hadError: true };
  }

  const parser = new Parser(tokens, errs);
  const ast = parser.parse();
  if (errs.length > 0) {
    return { stdout: out.join(""), stderr: errs.join(""), hadError: true };
  }

  const interp = new Interpreter(out, errs);
  interp.interpret(ast);

  return {
    stdout: out.join(""),
    stderr: errs.join(""),
    hadError: errs.length > 0,
  };
}

// Parses and resolves a Flox program without running its simulation.
// Returns the network as a list of systems with classified nodes and
// directed/undirected edges. The playground uses this to render the
// graph view.
export function parseGraph(source: string): ParseGraphResult {
  const out: string[] = [];
  const errs: string[] = [];

  const tokens = new Scanner(source, errs).scanTokens();
  if (errs.length > 0) {
    return { systems: [], hadError: true, stderr: errs.join("") };
  }
  const ast = new Parser(tokens, errs).parse();
  if (errs.length > 0) {
    return { systems: [], hadError: true, stderr: errs.join("") };
  }

  const interp = new Interpreter(out, errs);
  try {
    interp.collectAndResolve(ast);
  } catch (e) {
    const msg = e instanceof Error ? e.message : String(e);
    errs.push("Runtime error: " + msg + "\n");
  }

  // Walk the AST for compare statements. Compare is an EXECUTE-phase
  // statement so collectAndResolve hasn't visited it yet. We pull the
  // scenarios out of the parsed AST and, for each one, clone the base
  // system, apply the overrides, and run the simulator to capture
  // levels and flows. The renderer reads those to label nodes and
  // edges with their equilibrium values per scenario.
  const scenariosBySystem = new Map<string, GraphScenario[]>();
  for (const stmt of ast) {
    if (!stmt || stmt.kind !== "CompareStmt") continue;
    const sysName = stmt.systemName.lexeme;
    const baseSys = interp.getSystems().get(sysName);
    if (!baseSys) continue;
    const strat = STRATEGIES[baseSys.mode];
    const arg = stmt.ticks != null ? [stmt.ticks] : strat.defaultArg();

    const sceneList = scenariosBySystem.get(sysName) ?? [];
    for (const sc of stmt.scenarios) {
      const overrides: GraphOverride[] = sc.overrides.map(o => {
        if (o.kind === "disable-node") return { kind: "disable-node", name: o.name1.lexeme };
        if (o.kind === "disable-edge") {
          const from = o.forward ? o.name1.lexeme : o.name2.lexeme;
          const to   = o.forward ? o.name2.lexeme : o.name1.lexeme;
          return { kind: "disable-edge", from, to };
        }
        return { kind: "supply", name: o.name1.lexeme, value: o.value };
      });

      // Run the scenario to capture state. Swallow validation errors
      // (they get surfaced through interpret() anyway via stderr).
      let levels: Record<string, number> | undefined;
      let flows: Record<string, number> | undefined;
      let ticksRun: number | undefined;
      let converged: boolean | undefined;
      try {
        const clone = interp.scenarioClone(baseSys, sc.overrides);
        const localErrs: string[] = [];
        strat.validate(clone, localErrs);
        clone.identifyRoots();
        strat.run(clone, arg);
        if (baseSys.mode === "grid") {
          levels = Object.fromEntries(clone.gridLevel);
          flows = Object.fromEntries(clone.gridFlow);
          ticksRun = clone.gridTicksRun;
          converged = clone.gridConverged;
        } else if (baseSys.mode === "markov") {
          levels = {};
          for (const [k, hist] of clone.markovHistory) {
            levels[k] = hist[clone.markovTicksRun] ?? 0;
          }
          ticksRun = clone.markovTicksRun;
          converged = clone.markovConverged;
        }
      } catch (_e) {
        // Validation threw a hard error (rare in scenarios; softened by
        // scenarioRun marker). Leave state undefined; renderer falls
        // back to the topology-only view for this scenario.
      }

      sceneList.push({ label: sc.label, overrides, levels, flows, ticksRun, converged });
    }
    scenariosBySystem.set(sysName, sceneList);
  }

  // For river-mode systems, run the simulator once with a default 1mm
  // pulse so the renderer can show a hydrograph (per-day flow per node).
  // Wrapped in try/catch: a malformed program shouldn't block the
  // topology view.
  for (const sys of interp.getSystems().values()) {
    if (sys.mode !== "river") continue;
    try {
      const strat = STRATEGIES["river"];
      sys.identifyRoots();
      strat.run(sys, strat.defaultArg());
    } catch (_e) {
      // leave series unset; renderer falls back to topology only
    }
  }

  // For money-mode systems, run a default 30-day simulation so the
  // renderer can show per-day balance series for accounts and pots.
  for (const sys of interp.getSystems().values()) {
    if (sys.mode !== "money") continue;
    try {
      sys.identifyRoots();
      sys.simulateMoney(30);
    } catch (_e) {
      // leave series unset; renderer falls back to topology only
    }
  }

  const systems: SystemGraph[] = [];
  for (const sys of interp.getSystems().values()) {
    const graph = systemToGraph(sys);
    const scenarios = scenariosBySystem.get(sys.name);
    if (scenarios && scenarios.length > 0) graph.scenarios = scenarios;
    systems.push(graph);
  }

  return { systems, hadError: errs.length > 0, stderr: errs.join("") };
}

function systemToGraph(sys: RiverSystem): SystemGraph {
  const nodes: GraphNode[] = [];
  const isRiver = sys.mode === "river";
  const isMoney = sys.mode === "money";
  for (const r of sys.rivers.values()) {
    const node: GraphNode = {
      name: r.name,
      kind: classifyNode(r, sys),
      supply: r.supply,
      disabled: sys.disabledNodes.has(r.name),
    };
    if (isRiver && r.dailyFlow && r.dailyFlow.length > 0) {
      node.series = r.dailyFlow.slice();
    }
    if (isMoney) {
      const s = sys.moneySeries.get(r.name);
      if (s && s.length > 0) node.series = s.slice();
    }
    nodes.push(node);
  }
  for (const d of sys.dams.values()) {
    const node: GraphNode = {
      name: d.name,
      kind: "dam",
      supply: 0,
      disabled: sys.disabledNodes.has(d.name),
    };
    if (isRiver && d.dailyOutflow && d.dailyOutflow.length > 0) {
      node.series = d.dailyOutflow.slice();
    }
    nodes.push(node);
  }

  const directed = sys.mode !== "grid";
  const edges: GraphEdge[] = [];
  for (const [from, outEdges] of sys.connections) {
    for (const e of outEdges) {
      const incidentDisabled =
        sys.disabledNodes.has(from) || sys.disabledNodes.has(e.target);
      edges.push({
        from,
        to: e.target,
        weight: e.weight,
        directed,
        disabled: !e.enabled || incidentDisabled,
      });
    }
  }

  return { name: sys.name, mode: sys.mode, nodes, edges };
}

function classifyNode(r: River, sys: RiverSystem): NodeKind {
  const mode = sys.mode;
  if (mode === "grid") {
    if (r.supply > 0) return "generator";
    if (r.supply < 0) return "load";
    return "relay";
  }
  if (mode === "markov") return "state";
  if (mode === "money") {
    const meta = sys.moneyMeta.get(r.name);
    return meta ? meta.nodeType : "relay";
  }
  if (mode === "settle") return "person";
  // river mode
  if (r.baseFlow === 0 && r.peakMultiplier === 0) return "outlet";
  return "river";
}
