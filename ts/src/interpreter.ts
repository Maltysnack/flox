// Two-pass interpreter. Mechanical port of Interpreter.java.

import { AstNode, Chain } from "./ast.ts";
import { STRATEGIES } from "./modes.ts";
import { Output, printHelp, printList } from "./output.ts";
import { Dam, Mode, River, RiverSystem } from "./runtime.ts";
import { Token } from "./token.ts";

enum Phase {
  COLLECT,
  EXECUTE,
}

export class Interpreter {
  private readonly systems = new Map<string, RiverSystem>();
  private readonly pendingChains = new Map<string, Chain[]>();
  private currentSystem: RiverSystem | null = null;
  private phase: Phase = Phase.COLLECT;
  private fileMode: Mode = "river";

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
    for (const s of this.systems.values()) s.mode = this.fileMode;
    this.resolveChains();
    for (const s of this.systems.values()) STRATEGIES[s.mode].validate(s);
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
        else {
          throw new Error(
            `Unknown mode '${name}' (line ${stmt.mode.line}). Known modes: river, markov, grid.`,
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
