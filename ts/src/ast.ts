// AST node definitions. Mechanical port of Ast.java.
//
// Discriminated union: every node has a `kind` field. Visitor pattern is
// not idiomatic TypeScript; switch-on-kind is.

import { Token } from "./token.ts";

export type AstNode =
  | SystemDecl
  | ModeDecl
  | RiverDecl
  | DamDecl
  | OutletDecl
  | NodeDecl
  | Chain
  | StartStmt
  | DisableStmt
  | CompareStmt
  | MapStmt
  | ListStmt
  | PrintStmt
  | HelpStmt;

export interface SystemDecl {
  readonly kind: "SystemDecl";
  readonly name: Token;
}

export interface RiverDecl {
  readonly kind: "RiverDecl";
  readonly name: Token;
  readonly baseFlow: number;
  readonly peakMultiplier: number;
  readonly decayRate: number;
  readonly supply: number;
}

export interface DamDecl {
  readonly kind: "DamDecl";
  readonly name: Token;
  readonly capacity: number;
  readonly threshold: number; // fraction (0.8 = 80%)
  readonly normalRate: number;
  readonly openRate: number;
}

export interface OutletDecl {
  readonly kind: "OutletDecl";
  readonly name: Token;
}

export interface NodeDecl {
  readonly kind: "NodeDecl";
  readonly name: Token;
  readonly supply: number;
}

export interface ChainEdge {
  readonly forward: boolean;
  readonly weight: number | null; // null = unweighted (default 1.0)
  readonly op: Token;
}

export interface Chain {
  readonly kind: "Chain";
  readonly nodes: Token[];
  readonly arrows: ChainEdge[]; // length nodes.length - 1
}

export interface ModeDecl {
  readonly kind: "ModeDecl";
  readonly mode: Token; // identifier: "river" or "markov"
}

export interface StartStmt {
  readonly kind: "StartStmt";
  readonly node: Token;
  readonly mass: number;
}

export interface DisableStmt {
  readonly kind: "DisableStmt";
  readonly first: Token;
  readonly second: Token | null;  // null = node disable
  readonly forward: boolean;      // ignored if second is null
}

export type OverrideSpec =
  | { kind: "disable-node"; name1: Token }
  | { kind: "disable-edge"; name1: Token; name2: Token; forward: boolean }
  | { kind: "supply"; name1: Token; value: number };

export interface Scenario {
  readonly label: string;
  readonly overrides: OverrideSpec[];
}

export interface CompareStmt {
  readonly kind: "CompareStmt";
  readonly systemName: Token;
  readonly ticks: number | null;
  readonly scenarios: Scenario[];
}

export interface MapStmt {
  readonly kind: "MapStmt";
  readonly systemName: Token;
}

export interface ListStmt {
  readonly kind: "ListStmt";
  readonly systemName: Token;
}

export interface PrintStmt {
  readonly kind: "PrintStmt";
  readonly systemName: Token;
  readonly rainfall: number[] | null; // null means default
}

export interface HelpStmt {
  readonly kind: "HelpStmt";
}
