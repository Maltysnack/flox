// Two-pass interpreter. Mechanical port of Interpreter.java.

import { AstNode, Chain } from "./ast.ts";
import { Output, printFlowTable, printHelp, printList, printMap } from "./output.ts";
import { Dam, River, RiverSystem } from "./runtime.ts";
import { Token } from "./token.ts";

enum Phase {
  COLLECT,
  EXECUTE,
}

export class Interpreter {
  private readonly systems = new Map<string, RiverSystem>();
  // Per-system pending chains, kept between phases.
  private readonly pendingChains = new Map<string, Token[][]>();
  private currentSystem: RiverSystem | null = null;
  private phase: Phase = Phase.COLLECT;

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
      this.resolveChains();
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
        // Outlets stored as zero-flow rivers (matches Java's River.isTerminator()).
        sys.addRiver(new River(stmt.name.lexeme, 0, 0, 0.7));
        return;
      }

      case "Chain": {
        if (this.phase !== Phase.COLLECT) return;
        const sys = this.requireSystem(
          stmt.nodes.length > 0 ? stmt.nodes[0]! : null,
        );
        const list = this.pendingChains.get(sys.name)!;
        list.push(stmt.nodes);
        return;
      }

      case "MapStmt": {
        if (this.phase !== Phase.EXECUTE) return;
        const sys = this.lookupSystem(stmt.systemName);
        printMap(new Output(this.out), sys);
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
        const rainfall = stmt.rainfall ?? [1.0];
        sys.simulate(rainfall);
        printFlowTable(new Output(this.out), sys, rainfall);
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
        for (const name of chain) {
          if (!this.nodeDefined(system, name.lexeme)) {
            throw new Error(
              `Chain references undeclared node '${name.lexeme}' (line ${name.line}) in system '${system.name}'.`,
            );
          }
        }
        for (let i = 0; i + 1 < chain.length; i++) {
          system.addConnection(chain[i]!.lexeme, chain[i + 1]!.lexeme);
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
