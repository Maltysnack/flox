// Two-pass interpreter. Mechanical port of Interpreter.java.

import { AstNode, Chain, CompareStmt, DisableStmt, OverrideSpec } from "./ast.ts";
import { STRATEGIES } from "./modes.ts";
import { Output, printGridComparison, printHelp, printList } from "./output.ts";
import { Dam, Mode, River, RiverSystem } from "./runtime.ts";
import { Token } from "./token.ts";

enum Phase {
  COLLECT,
  EXECUTE,
}

export class Interpreter {
  private readonly systems = new Map<string, RiverSystem>();
  private readonly pendingChains = new Map<string, Chain[]>();
  private readonly pendingDisables = new Map<string, DisableStmt[]>();
  private currentSystem: RiverSystem | null = null;
  private phase: Phase = Phase.COLLECT;
  private fileMode: Mode = "river";
  private readonly fileUnits = new Map<string, string>();

  constructor(
    private readonly out: string[],
    private readonly errs: string[],
  ) {}

  interpret(statements: AstNode[]): void {
    try {
      this.collectAndResolve(statements);
      this.executeAll(statements);
    } catch (e) {
      const msg = e instanceof Error ? e.message : String(e);
      this.errs.push("Runtime error: " + msg + "\n");
    }
  }

  // Phase 1: collect declarations, apply file mode, resolve chain edges,
  // validate per-mode, identify roots. Stops before any list/map/print
  // executes. Used by parseGraph() to extract the network structure
  // without running the simulator.
  collectAndResolve(statements: AstNode[]): void {
    this.phase = Phase.COLLECT;
    this.currentSystem = null;
    for (const stmt of statements) {
      if (stmt) this.execute(stmt);
    }
    for (const s of this.systems.values()) {
      s.mode = this.fileMode;
      for (const [k, v] of this.fileUnits) s.unitLabels.set(k, v);
    }
    this.resolveChains();
    this.applyDisables();
    for (const s of this.systems.values()) STRATEGIES[s.mode].validate(s, this.errs);
    for (const s of this.systems.values()) s.identifyRoots();
  }

  executeAll(statements: AstNode[]): void {
    this.phase = Phase.EXECUTE;
    this.currentSystem = null;
    for (const stmt of statements) {
      if (stmt) this.execute(stmt);
    }
  }

  // Read-only access to the systems map after collectAndResolve.
  // Used by parseGraph(); not meant for general consumption.
  getSystems(): ReadonlyMap<string, RiverSystem> {
    return this.systems;
  }

  private execute(stmt: AstNode): void {
    switch (stmt.kind) {
      case "SystemDecl": {
        const name = stmt.name.lexeme;
        if (this.phase === Phase.COLLECT) {
          let s = this.systems.get(name);
          if (!s) {
            s = new RiverSystem(name);
            this.systems.set(name, s);
            this.pendingChains.set(name, []);
            this.pendingDisables.set(name, []);
          }
          this.currentSystem = s;
        } else {
          this.currentSystem = this.systems.get(name) ?? null;
        }
        return;
      }

      case "ModeDecl": {
        if (this.phase !== Phase.COLLECT) return;
        const name = stmt.mode.lexeme;
        if (name === "river") this.fileMode = "river";
        else if (name === "markov") this.fileMode = "markov";
        else if (name === "grid") this.fileMode = "grid";
        else if (name === "money") this.fileMode = "money";
        else if (name === "settle") this.fileMode = "settle";
        else {
          throw new Error(
            `Unknown mode '${name}' (line ${stmt.mode.line}). Known modes: river, markov, grid, money, settle.`,
          );
        }
        return;
      }

      case "RiverDecl": {
        if (this.phase !== Phase.COLLECT) return;
        const sys = this.requireSystem(stmt.name);
        if (sys.rivers.has(stmt.name.lexeme) || sys.dams.has(stmt.name.lexeme)) {
          throw new Error(
            `Node '${stmt.name.lexeme}' (line ${stmt.name.line}) is declared more than once in system '${sys.name}'.`,
          );
        }
        sys.addRiver(
          new River(
            stmt.name.lexeme,
            stmt.baseFlow,
            stmt.peakMultiplier,
            stmt.decayRate,
            stmt.supply,
          ),
        );
        return;
      }

      case "DamDecl": {
        if (this.phase !== Phase.COLLECT) return;
        const sys = this.requireSystem(stmt.name);
        if (sys.rivers.has(stmt.name.lexeme) || sys.dams.has(stmt.name.lexeme)) {
          throw new Error(
            `Node '${stmt.name.lexeme}' (line ${stmt.name.line}) is declared more than once in system '${sys.name}'.`,
          );
        }
        sys.addDam(
          new Dam(
            stmt.name.lexeme,
            stmt.capacity,
            stmt.threshold,
            stmt.normalRate,
            stmt.openRate,
          ),
        );
        return;
      }

      case "NodeDecl": {
        if (this.phase !== Phase.COLLECT) return;
        const sys = this.requireSystem(stmt.name);
        if (sys.rivers.has(stmt.name.lexeme) || sys.dams.has(stmt.name.lexeme)) {
          throw new Error(
            `Node '${stmt.name.lexeme}' (line ${stmt.name.line}) is declared more than once in system '${sys.name}'.`,
          );
        }
        sys.addRiver(new River(stmt.name.lexeme, 0, 0, 0.7, stmt.supply));
        return;
      }

      case "MoneyDecl": {
        if (this.phase !== Phase.COLLECT) return;
        const sys = this.requireSystem(stmt.name);
        if (sys.rivers.has(stmt.name.lexeme) || sys.dams.has(stmt.name.lexeme)) {
          throw new Error(
            `Node '${stmt.name.lexeme}' (line ${stmt.name.line}) is declared more than once in system '${sys.name}'.`,
          );
        }
        // Register as a zero-flow River so existing iteration/topology
        // works; keep the typed money fields in moneyMeta.
        sys.addRiver(new River(stmt.name.lexeme, 0, 0, 0.7, 0));
        sys.addMoneyMeta(stmt.name.lexeme, {
          nodeType: stmt.nodeType,
          amount: stmt.amount,
          on: stmt.on,
          balance: stmt.balance,
          target: stmt.target,
          spread: stmt.spread,
        });
        return;
      }

      case "PersonDecl": {
        if (this.phase !== Phase.COLLECT) return;
        const sys = this.requireSystem(stmt.name);
        if (sys.rivers.has(stmt.name.lexeme) || sys.dams.has(stmt.name.lexeme)) {
          throw new Error(
            `Node '${stmt.name.lexeme}' (line ${stmt.name.line}) is declared more than once in system '${sys.name}'.`,
          );
        }
        sys.addRiver(new River(stmt.name.lexeme, 0, 0, 0.7, 0));
        sys.people.push(stmt.name.lexeme);
        return;
      }

      case "OutletDecl": {
        if (this.phase !== Phase.COLLECT) return;
        const sys = this.requireSystem(stmt.name);
        if (sys.rivers.has(stmt.name.lexeme) || sys.dams.has(stmt.name.lexeme)) {
          throw new Error(
            `Node '${stmt.name.lexeme}' (line ${stmt.name.line}) is declared more than once in system '${sys.name}'.`,
          );
        }
        sys.addRiver(new River(stmt.name.lexeme, 0, 0, 0.7));
        return;
      }

      case "Chain": {
        if (this.phase !== Phase.COLLECT) return;
        const sys = this.requireSystem(
          stmt.nodes.length > 0 ? stmt.nodes[0]! : null,
        );
        this.pendingChains.get(sys.name)!.push(stmt);
        return;
      }

      case "DisableStmt": {
        if (this.phase !== Phase.COLLECT) return;
        const sys = this.requireSystem(stmt.first);
        this.pendingDisables.get(sys.name)!.push(stmt);
        return;
      }

      case "UnitsDecl": {
        if (this.phase !== Phase.COLLECT) return;
        for (const [k, v] of Object.entries(stmt.labels)) {
          this.fileUnits.set(k, v);
        }
        return;
      }

      case "StartStmt": {
        if (this.phase !== Phase.COLLECT) return;
        const sys = this.requireSystem(stmt.node);
        sys.initialMass.set(
          stmt.node.lexeme,
          (sys.initialMass.get(stmt.node.lexeme) ?? 0) + stmt.mass,
        );
        return;
      }

      case "MapStmt": {
        if (this.phase !== Phase.EXECUTE) return;
        const sys = this.lookupSystem(stmt.systemName);
        STRATEGIES[sys.mode].printMap(new Output(this.out), sys);
        return;
      }

      case "ListStmt": {
        if (this.phase !== Phase.EXECUTE) return;
        const sys = this.lookupSystem(stmt.systemName);
        printList(new Output(this.out), sys);
        return;
      }

      case "PrintStmt": {
        if (this.phase !== Phase.EXECUTE) return;
        const sys = this.lookupSystem(stmt.systemName);
        const strat = STRATEGIES[sys.mode];
        const arg = stmt.rainfall ?? strat.defaultArg();
        strat.run(sys, arg);
        const o = new Output(this.out);
        strat.printResult(o, sys, arg);
        return;
      }

      case "CompareStmt": {
        if (this.phase !== Phase.EXECUTE) return;
        const original = this.lookupSystem(stmt.systemName);
        if (original.mode !== "grid") {
          throw new Error(
            `'compare' is only supported in grid mode for now (system '${original.name}' is ${original.mode} mode).`,
          );
        }
        const arg = stmt.ticks != null ? [stmt.ticks] : STRATEGIES[original.mode].defaultArg();
        const runs: RiverSystem[] = [];
        const labels: string[] = [];
        for (const sc of stmt.scenarios) {
          const clone = this.scenarioClone(original, sc.overrides);
          STRATEGIES[clone.mode].validate(clone, this.errs);
          clone.identifyRoots();
          STRATEGIES[clone.mode].run(clone, arg);
          runs.push(clone);
          labels.push(sc.label);
        }
        printGridComparison(new Output(this.out), original, runs, labels);
        return;
      }

      case "HelpStmt": {
        if (this.phase !== Phase.EXECUTE) return;
        printHelp(new Output(this.out));
        return;
      }
    }
  }

  private resolveChains(): void {
    for (const [sysName, chains] of this.pendingChains) {
      const system = this.systems.get(sysName)!;
      for (const chain of chains) {
        for (const name of chain.nodes) {
          if (!this.nodeDefined(system, name.lexeme)) {
            throw new Error(
              `Chain references undeclared node '${name.lexeme}' (line ${name.line}) in system '${system.name}'.`,
            );
          }
        }
        for (let i = 0; i + 1 < chain.nodes.length; i++) {
          const arrow = chain.arrows[i]!;
          const a = chain.nodes[i]!;
          const b = chain.nodes[i + 1]!;
          const from = arrow.forward ? a : b;
          const to = arrow.forward ? b : a;
          const weight = arrow.weight != null ? arrow.weight : 1.0;
          system.addConnection(from.lexeme, to.lexeme, weight, arrow.op);
        }
      }
    }
  }

  // Public so parseGraph() can run scenarios and capture state.
  scenarioClone(src: RiverSystem, overrides: OverrideSpec[]): RiverSystem {
    const supplyOverrides = new Map<string, number>();
    const extraDisableNodes = new Set<string>();
    const extraDisableEdges = new Set<string>();
    for (const o of overrides) {
      if (o.kind === "disable-node") extraDisableNodes.add(o.name1.lexeme);
      else if (o.kind === "disable-edge") {
        const from = o.forward ? o.name1.lexeme : o.name2.lexeme;
        const to = o.forward ? o.name2.lexeme : o.name1.lexeme;
        extraDisableEdges.add(`${from}|${to}`);
      } else if (o.kind === "supply") {
        supplyOverrides.set(o.name1.lexeme, o.value);
      }
    }

    const clone = new RiverSystem(src.name);
    clone.mode = src.mode;
    clone.scenarioRun = true;

    for (const r of src.rivers.values()) {
      const supply = supplyOverrides.get(r.name) ?? r.supply;
      clone.addRiver(new River(r.name, r.baseFlow, r.peakMultiplier, r.decayRate, supply));
    }
    for (const d of src.dams.values()) clone.addDam(d);

    for (const n of src.disabledNodes) clone.disabledNodes.add(n);
    for (const n of extraDisableNodes) clone.disabledNodes.add(n);

    for (const [from, edges] of src.connections) {
      for (const e of edges) {
        clone.addConnection(from, e.target, e.weight, e.op);
        const outs = clone.connections.get(from)!;
        const added = outs[outs.length - 1]!;
        added.enabled = e.enabled;
        if (extraDisableEdges.has(`${from}|${e.target}`)) added.enabled = false;
        if (clone.disabledNodes.has(from) || clone.disabledNodes.has(e.target)) {
          added.enabled = false;
        }
      }
    }

    for (const [k, v] of src.initialMass) clone.initialMass.set(k, v);
    return clone;
  }

  private applyDisables(): void {
    for (const [sysName, stmts] of this.pendingDisables) {
      const system = this.systems.get(sysName);
      if (!system) continue;
      for (const stmt of stmts) {
        if (stmt.second === null) {
          // Node disable.
          const name = stmt.first.lexeme;
          if (!this.nodeDefined(system, name)) {
            throw new Error(
              `disable references undeclared node '${name}' (line ${stmt.first.line}) in system '${system.name}'.`,
            );
          }
          system.disabledNodes.add(name);
          const outs = system.connections.get(name);
          if (outs) for (const e of outs) e.enabled = false;
          for (const es of system.connections.values()) {
            for (const e of es) if (e.target === name) e.enabled = false;
          }
        } else {
          // Edge disable.
          const a = stmt.first.lexeme;
          const b = stmt.second.lexeme;
          const from = stmt.forward ? a : b;
          const to   = stmt.forward ? b : a;
          if (!this.nodeDefined(system, from) || !this.nodeDefined(system, to)) {
            throw new Error(
              `disable references undeclared node in edge '${from} -> ${to}' (line ${stmt.first.line}) in system '${system.name}'.`,
            );
          }
          const outs = system.connections.get(from);
          let found = false;
          if (outs) {
            for (const e of outs) {
              if (e.target === to) { e.enabled = false; found = true; }
            }
          }
          if (!found) {
            throw new Error(
              `disable references edge '${from} -> ${to}' (line ${stmt.first.line}) which is not declared in system '${system.name}'.`,
            );
          }
        }
      }
    }
  }

  private nodeDefined(system: RiverSystem, name: string): boolean {
    return system.rivers.has(name) || system.dams.has(name);
  }

  private requireSystem(at: Token | null): RiverSystem {
    if (this.currentSystem === null) {
      const where = at ? ` (line ${at.line})` : "";
      throw new Error("No system declared. Use 'system Name;' first." + where);
    }
    return this.currentSystem;
  }

  private lookupSystem(nameToken: Token): RiverSystem {
    const sys = this.systems.get(nameToken.lexeme);
    if (!sys) {
      throw new Error(
        `System '${nameToken.lexeme}' not found (line ${nameToken.line}).`,
      );
    }
    return sys;
  }
}
