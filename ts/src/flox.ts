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
}

export interface GraphEdge {
  from: string;
  to: string;
  weight: number;
  directed: boolean;
}

export interface SystemGraph {
  name: string;
  mode: Mode;
  nodes: GraphNode[];
  edges: GraphEdge[];
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

  const systems: SystemGraph[] = [];
  for (const sys of interp.getSystems().values()) {
    systems.push(systemToGraph(sys));
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
    });
  }
  for (const d of sys.dams.values()) {
    nodes.push({ name: d.name, kind: "dam", supply: 0 });
  }

  const directed = sys.mode !== "grid";
  const edges: GraphEdge[] = [];
  for (const [from, outEdges] of sys.connections) {
    for (const e of outEdges) {
      edges.push({ from, to: e.target, weight: e.weight, directed });
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
