// Public entry point. Run a Flox program and capture its output.

import { Interpreter } from "./interpreter.ts";
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
  | "relay";     // grid-mode supply == 0 with edges

export interface GraphNode {
  name: string;
  kind: NodeKind;
  supply: number;
  disabled: boolean;
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

  // Walk the AST once to collect compare scenarios per system. Compare
  // is an EXECUTE-phase statement so collectAndResolve hasn't visited
  // it yet; we just pull the data out of the parsed AST directly.
  const scenariosBySystem = new Map<string, GraphScenario[]>();
  for (const stmt of ast) {
    if (stmt && stmt.kind === "CompareStmt") {
      const sysName = stmt.systemName.lexeme;
      const sceneList = scenariosBySystem.get(sysName) ?? [];
      for (const sc of stmt.scenarios) {
        sceneList.push({
          label: sc.label,
          overrides: sc.overrides.map(o => {
            if (o.kind === "disable-node") return { kind: "disable-node", name: o.name1.lexeme };
            if (o.kind === "disable-edge") {
              const from = o.forward ? o.name1.lexeme : o.name2.lexeme;
              const to   = o.forward ? o.name2.lexeme : o.name1.lexeme;
              return { kind: "disable-edge", from, to };
            }
            return { kind: "supply", name: o.name1.lexeme, value: o.value };
          }),
        });
      }
      scenariosBySystem.set(sysName, sceneList);
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
  for (const r of sys.rivers.values()) {
    nodes.push({
      name: r.name,
      kind: classifyRiver(r, sys.mode),
      supply: r.supply,
      disabled: sys.disabledNodes.has(r.name),
    });
  }
  for (const d of sys.dams.values()) {
    nodes.push({
      name: d.name,
      kind: "dam",
      supply: 0,
      disabled: sys.disabledNodes.has(d.name),
    });
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

function classifyRiver(r: River, mode: Mode): NodeKind {
  if (mode === "grid") {
    if (r.supply > 0) return "generator";
    if (r.supply < 0) return "load";
    return "relay";
  }
  if (mode === "markov") return "state";
  // river mode
  if (r.baseFlow === 0 && r.peakMultiplier === 0) return "outlet";
  return "river";
}
