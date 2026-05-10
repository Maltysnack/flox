// Two-pass interpreter. Mechanical port of Interpreter.java.

import { AstNode, Chain } from "./ast.ts";
import {
  Output,
  printFlowTable,
  printHelp,
  printList,
  printMap,
  printMarkovDistribution,
  printMarkovMap,
} from "./output.ts";
import { Dam, Edge, Mode, River, RiverSystem } from "./runtime.ts";
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
      this.phase = Phase.COLLECT;
      this.currentSystem = null;
      for (const stmt of statements) {
        if (stmt) this.execute(stmt);
      }

      // Apply file mode to every system before resolution.
      for (const s of this.systems.values()) s.mode = this.fileMode;

      this.resolveChains();

      // Per-mode validation pass.
      for (const s of this.systems.values()) {
        if (s.mode === "markov") this.validateMarkov(s);
      }

      for (const s of this.systems.values()) s.identifyRoots();

      this.phase = Phase.EXECUTE;
      this.currentSystem = null;
      for (const stmt of statements) {
        if (stmt) this.execute(stmt);
      }
    } catch (e) {
      const msg = e instanceof Error ? e.message : String(e);
      this.errs.push("Runtime error: " + msg + "\n");
    }
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
        else {
          throw new Error(
            `Unknown mode '${name}' (line ${stmt.mode.line}). Known modes: river, markov.`,
          );
        }
        return;
      }

      case "RiverDecl": {
        if (this.phase !== Phase.COLLECT) return;
        const sys = this.requireSystem(stmt.name);
        if (sys.rivers.has(stmt.name.lexeme) || sys.dams.has(stmt.name.lexeme)) {
          throw new Error(
            `Node '${stmt.name.lexeme}' is declared more than once in system '${sys.name}'.`,
          );
        }
        sys.addRiver(
          new River(
            stmt.name.lexeme,
            stmt.baseFlow,
            stmt.peakMultiplier,
            stmt.decayRate,
          ),
        );
        return;
      }

      case "DamDecl": {
        if (this.phase !== Phase.COLLECT) return;
        const sys = this.requireSystem(stmt.name);
        if (sys.rivers.has(stmt.name.lexeme) || sys.dams.has(stmt.name.lexeme)) {
          throw new Error(
            `Node '${stmt.name.lexeme}' is declared more than once in system '${sys.name}'.`,
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

      case "OutletDecl": {
        if (this.phase !== Phase.COLLECT) return;
        const sys = this.requireSystem(stmt.name);
        if (sys.rivers.has(stmt.name.lexeme) || sys.dams.has(stmt.name.lexeme)) {
          throw new Error(
            `Node '${stmt.name.lexeme}' is declared more than once in system '${sys.name}'.`,
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
        if (sys.mode === "markov") {
          printMarkovMap(new Output(this.out), sys);
        } else {
          printMap(new Output(this.out), sys);
        }
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
        if (sys.mode === "markov") {
          let ticks = 30;
          if (stmt.rainfall != null && stmt.rainfall.length > 0) {
            ticks = Math.max(1, Math.round(stmt.rainfall[0]!));
          }
          sys.simulateMarkov(ticks);
          printMarkovDistribution(new Output(this.out), sys);
        } else {
          const rainfall = stmt.rainfall ?? [1.0];
          sys.simulate(rainfall);
          printFlowTable(new Output(this.out), sys, rainfall);
        }
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
          system.addConnection(from.lexeme, to.lexeme, weight);
        }
      }
    }
  }

  private validateMarkov(system: RiverSystem): void {
    for (const name of system.initialMass.keys()) {
      if (!this.nodeDefined(system, name)) {
        throw new Error(
          `start references undeclared node '${name}' in system '${system.name}'.`,
        );
      }
    }
    let totalInitial = 0;
    for (const m of system.initialMass.values()) totalInitial += m;
    if (totalInitial === 0) {
      throw new Error(
        `Markov system '${system.name}' has no initial mass. Use 'start NodeName 1.0;' to set one.`,
      );
    }
    for (const [src, edges] of system.connections) {
      let sum = 0;
      for (const e of edges) sum += e.weight;
      if (Math.abs(sum - 1.0) > 1e-6) {
        throw new Error(
          `Markov system '${system.name}': outgoing weights from '${src}' sum to ${sum}, not 1.0.`,
        );
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
