// Parser. Mechanical port of Parser.java.

import {
  AstNode,
  Chain,
  ChainEdge,
  CompareStmt,
  DamDecl,
  DisableStmt,
  OverrideSpec,
  Scenario,
  HelpStmt,
  ListStmt,
  MapStmt,
  ModeDecl,
  NodeDecl,
  OutletDecl,
  PrintStmt,
  RiverDecl,
  StartStmt,
  SystemDecl,
} from "./ast.ts";
import { Token, TokenType } from "./token.ts";

class ParseError extends Error {}

export class Parser {
  private current = 0;

  constructor(
    private readonly tokens: Token[],
    private readonly errs: string[],
  ) {}

  parse(): AstNode[] {
    const statements: AstNode[] = [];
    while (!this.isAtEnd()) {
      const stmt = this.declaration();
      if (stmt !== null) statements.push(stmt);
    }
    return statements;
  }

  private declaration(): AstNode | null {
    try {
      if (this.match(TokenType.MODE))   return this.modeDeclaration();
      if (this.match(TokenType.SYSTEM)) return this.systemDeclaration();
      if (this.match(TokenType.RIVER))  return this.riverDeclaration();
      if (this.match(TokenType.DAM))    return this.damDeclaration();
      if (this.match(TokenType.OUTLET)) return this.outletDeclaration();
      if (this.match(TokenType.NODE))   return this.nodeDeclaration();
      if (this.match(TokenType.START))  return this.startStatement();
      if (this.match(TokenType.DISABLE)) return this.disableStatement();
      if (this.match(TokenType.COMPARE)) return this.compareStatement();
      if (this.match(TokenType.MAP))    return this.mapStatement();
      if (this.match(TokenType.LIST))   return this.listStatement();
      if (this.match(TokenType.PRINT))  return this.printStatement();
      if (this.match(TokenType.HELP))   return this.helpStatement();
      return this.chain();
    } catch (e) {
      if (e instanceof ParseError) {
        this.synchronize();
        return null;
      }
      throw e;
    }
  }

  private modeDeclaration(): ModeDecl {
    const name = this.consume(TokenType.IDENTIFIER, "Expect mode name (e.g. river, markov).");
    this.consume(TokenType.SEMICOLON, "Expect ';' after mode name.");
    return { kind: "ModeDecl", mode: name };
  }

  private compareStatement(): CompareStmt {
    const name = this.consume(TokenType.IDENTIFIER, "Expect system name after 'compare'.");
    let ticks: number | null = null;
    if (this.match(TokenType.NUMBER)) {
      ticks = this.previous().literal as number;
    }
    this.consume(TokenType.AS, "Expect 'as' before the scenario list.");
    const scenarios: Scenario[] = [this.scenario()];
    while (this.match(TokenType.COMMA)) scenarios.push(this.scenario());
    this.consume(TokenType.SEMICOLON, "Expect ';' after compare statement.");
    return { kind: "CompareStmt", systemName: name, ticks, scenarios };
  }

  private scenario(): Scenario {
    const label = this.consume(TokenType.STRING, "Expect scenario label string.");
    const overrides: OverrideSpec[] = [];
    while (this.match(TokenType.WITH)) overrides.push(this.overrideClause());
    return { label: label.lexeme, overrides };
  }

  private overrideClause(): OverrideSpec {
    if (this.match(TokenType.DISABLE)) {
      const first = this.consume(TokenType.IDENTIFIER, "Expect node name after 'disable'.");
      if (this.match(TokenType.ARROW)) {
        const second = this.consume(TokenType.IDENTIFIER, "Expect node name after '->'.");
        return { kind: "disable-edge", name1: first, name2: second, forward: true };
      } else if (this.match(TokenType.LARROW)) {
        const second = this.consume(TokenType.IDENTIFIER, "Expect node name after '<-'.");
        return { kind: "disable-edge", name1: first, name2: second, forward: false };
      }
      return { kind: "disable-node", name1: first };
    }
    const name = this.consume(TokenType.IDENTIFIER, "Expect node name or 'disable' in override clause.");
    this.consume(TokenType.DOT, "Expect '.' after node name in property override.");
    const attr = this.consume(TokenType.IDENTIFIER, "Expect attribute name.");
    if (attr.lexeme !== "supply") {
      throw this.errorAt(attr, `Only 'supply' is supported as a property override (got '${attr.lexeme}').`);
    }
    this.consume(TokenType.EQUALS, "Expect '=' after attribute name in override.");
    const negate = this.match(TokenType.DASH);
    let value = this.consumeNumber("Expect number on right-hand side of override.");
    if (negate) value = -value;
    return { kind: "supply", name1: name, value };
  }

  private disableStatement(): DisableStmt {
    const first = this.consume(TokenType.IDENTIFIER, "Expect node name after 'disable'.");
    let second: Token | null = null;
    let forward = true;
    if (this.match(TokenType.ARROW)) {
      forward = true;
      second = this.consume(TokenType.IDENTIFIER, "Expect node name after '->' in 'disable'.");
    } else if (this.match(TokenType.LARROW)) {
      forward = false;
      second = this.consume(TokenType.IDENTIFIER, "Expect node name after '<-' in 'disable'.");
    }
    this.consume(TokenType.SEMICOLON, "Expect ';' after disable statement.");
    return { kind: "DisableStmt", first, second, forward };
  }

  private startStatement(): StartStmt {
    const node = this.consume(TokenType.IDENTIFIER, "Expect node name after 'start'.");
    const mass = this.consumeNumber("Expect initial mass after node name in 'start'.");
    this.consume(TokenType.SEMICOLON, "Expect ';' after start statement.");
    return { kind: "StartStmt", node, mass };
  }

  private systemDeclaration(): SystemDecl {
    const name = this.consume(TokenType.IDENTIFIER, "Expect system name.");
    this.consume(TokenType.SEMICOLON, "Expect ';' after system name.");
    return { kind: "SystemDecl", name };
  }

  private mapStatement(): MapStmt {
    const name = this.consume(TokenType.IDENTIFIER, "Expect system name.");
    this.consume(TokenType.SEMICOLON, "Expect ';' after system name.");
    return { kind: "MapStmt", systemName: name };
  }

  private listStatement(): ListStmt {
    const name = this.consume(TokenType.IDENTIFIER, "Expect system name.");
    this.consume(TokenType.SEMICOLON, "Expect ';' after system name.");
    return { kind: "ListStmt", systemName: name };
  }

  private printStatement(): PrintStmt {
    const name = this.consume(TokenType.IDENTIFIER, "Expect system name.");
    let rainfall: number[] | null = null;
    if (this.match(TokenType.NUMBER)) {
      rainfall = [this.previous().literal as number];
    } else if (this.match(TokenType.LEFT_BRACKET)) {
      const values: number[] = [];
      if (!this.check(TokenType.RIGHT_BRACKET)) {
        values.push(this.consumeNumber("Expect number in rainfall series."));
        while (this.match(TokenType.COMMA)) {
          values.push(
            this.consumeNumber("Expect number after ',' in rainfall series."),
          );
        }
      }
      this.consume(
        TokenType.RIGHT_BRACKET,
        "Expect ']' to close rainfall series.",
      );
      rainfall = values;
    }
    this.consume(TokenType.SEMICOLON, "Expect ';' after print statement.");
    return { kind: "PrintStmt", systemName: name, rainfall };
  }

  private helpStatement(): HelpStmt {
    this.consume(TokenType.SEMICOLON, "Expect ';' after 'help'.");
    return { kind: "HelpStmt" };
  }

  private riverDeclaration(): RiverDecl {
    const name = this.consume(TokenType.IDENTIFIER, "Expect river name.");
    const attrs = this.parseBlock(name);
    this.consumeOptional(TokenType.SEMICOLON);
    this.validateAttrs(name, attrs, ["base", "peak"], ["decay", "supply"]);
    return {
      kind: "RiverDecl",
      name,
      baseFlow: attrs.get("base")!,
      peakMultiplier: attrs.get("peak")!,
      decayRate: attrs.get("decay") ?? 0.7,
      supply: attrs.get("supply") ?? 0,
    };
  }

  private damDeclaration(): DamDecl {
    const name = this.consume(TokenType.IDENTIFIER, "Expect dam name.");
    const attrs = this.parseBlock(name);
    this.consumeOptional(TokenType.SEMICOLON);
    this.validateAttrs(
      name,
      attrs,
      ["capacity", "threshold", "normalRate", "openRate"],
      [],
    );
    return {
      kind: "DamDecl",
      name,
      capacity: attrs.get("capacity")!,
      threshold: attrs.get("threshold")!,
      normalRate: attrs.get("normalRate")!,
      openRate: attrs.get("openRate")!,
    };
  }

  private nodeDeclaration(): NodeDecl {
    const name = this.consume(TokenType.IDENTIFIER, "Expect node name.");
    const attrs = this.check(TokenType.LEFT_BRACE)
      ? this.parseBlock(name)
      : new Map<string, number>();
    this.consumeOptional(TokenType.SEMICOLON);
    this.validateAttrs(name, attrs, [], ["supply"]);
    const supply = attrs.get("supply") ?? 0;
    return { kind: "NodeDecl", name, supply };
  }

  private outletDeclaration(): OutletDecl {
    const name = this.consume(TokenType.IDENTIFIER, "Expect outlet name.");
    if (this.check(TokenType.LEFT_BRACE)) {
      const attrs = this.parseBlock(name);
      if (attrs.size > 0) {
        throw this.errorAt(
          name,
          `Outlet '${name.lexeme}' takes no attributes.`,
        );
      }
    }
    this.consumeOptional(TokenType.SEMICOLON);
    return { kind: "OutletDecl", name };
  }

  private chain(): Chain {
    if (!this.check(TokenType.IDENTIFIER)) {
      throw this.errorAt(this.peek(), "Expect declaration or chain.");
    }
    const nodes: Token[] = [];
    const arrows: ChainEdge[] = [];
    nodes.push(this.advance());

    while (this.atArrowStart()) {
      arrows.push(this.parseArrow());
      nodes.push(this.consume(TokenType.IDENTIFIER, "Expect node name after arrow."));
    }
    this.consume(TokenType.SEMICOLON, "Expect ';' after chain.");
    return { kind: "Chain", nodes, arrows };
  }

  private atArrowStart(): boolean {
    if (this.check(TokenType.ARROW)) return true;
    if (this.check(TokenType.LARROW)) return true;
    if (this.check(TokenType.DASH) && this.peekAt(1, TokenType.LEFT_BRACKET)) return true;
    return false;
  }

  private parseArrow(): ChainEdge {
    if (this.match(TokenType.ARROW)) {
      return { forward: true, weight: null, op: this.previous() };
    }
    if (this.match(TokenType.LARROW)) {
      const op = this.previous();
      if (this.match(TokenType.LEFT_BRACKET)) {
        const w = this.consumeNumber("Expect weight number inside arrow brackets.");
        this.consume(TokenType.RIGHT_BRACKET, "Expect ']' after arrow weight.");
        this.consume(TokenType.DASH, "Expect '-' to close '<-[w]-' arrow.");
        return { forward: false, weight: w, op };
      }
      return { forward: false, weight: null, op };
    }
    // Forward weighted: -[w]->
    const op = this.consume(TokenType.DASH, "Expect arrow.");
    this.consume(TokenType.LEFT_BRACKET, "Expect '[' after '-' to start a weighted arrow.");
    const w = this.consumeNumber("Expect weight number inside arrow brackets.");
    this.consume(TokenType.RIGHT_BRACKET, "Expect ']' after arrow weight.");
    this.consume(TokenType.ARROW, "Expect '->' to close '-[w]->' arrow.");
    return { forward: true, weight: w, op };
  }

  private peekAt(offset: number, type: TokenType): boolean {
    const idx = this.current + offset;
    if (idx >= this.tokens.length) return false;
    return this.tokens[idx]!.type === type;
  }

  private parseBlock(declName: Token): Map<string, number> {
    this.consume(
      TokenType.LEFT_BRACE,
      `Expect '{' to open '${declName.lexeme}' attributes.`,
    );
    const attrs = new Map<string, number>();
    while (!this.check(TokenType.RIGHT_BRACE) && !this.isAtEnd()) {
      const key = this.consume(TokenType.IDENTIFIER, "Expect attribute name.");
      this.consume(
        TokenType.COLON,
        `Expect ':' after attribute name '${key.lexeme}'.`,
      );
      // Optional unary minus on numeric values (e.g. supply: -40).
      const negate = this.match(TokenType.DASH);
      let value = this.consumeNumber("Expect number after ':'.");
      if (negate) value = -value;
      if (this.match(TokenType.PERCENT)) value = value / 100;
      if (attrs.has(key.lexeme)) {
        throw this.errorAt(key, `Duplicate attribute '${key.lexeme}'.`);
      }
      attrs.set(key.lexeme, value);
      this.match(TokenType.COMMA);
    }
    this.consume(TokenType.RIGHT_BRACE, "Expect '}' to close attributes.");
    return attrs;
  }

  private validateAttrs(
    declName: Token,
    attrs: Map<string, number>,
    required: string[],
    optional: string[],
  ): void {
    for (const key of attrs.keys()) {
      if (!required.includes(key) && !optional.includes(key)) {
        throw this.errorAt(
          declName,
          `Unknown attribute '${key}' on '${declName.lexeme}'.`,
        );
      }
    }
    for (const r of required) {
      if (!attrs.has(r)) {
        throw this.errorAt(
          declName,
          `Missing required attribute '${r}' on '${declName.lexeme}'.`,
        );
      }
    }
  }

  private consumeNumber(message: string): number {
    if (!this.check(TokenType.NUMBER)) {
      throw this.errorAt(this.peek(), message);
    }
    return this.advance().literal as number;
  }

  private match(...types: TokenType[]): boolean {
    for (const type of types) {
      if (this.check(type)) {
        this.advance();
        return true;
      }
    }
    return false;
  }

  private consumeOptional(type: TokenType): void {
    if (this.check(type)) this.advance();
  }

  private consume(type: TokenType, message: string): Token {
    if (this.check(type)) return this.advance();
    throw this.errorAt(this.peek(), message);
  }

  private check(type: TokenType): boolean {
    if (this.isAtEnd()) return false;
    return this.peek().type === type;
  }

  private advance(): Token {
    if (!this.isAtEnd()) this.current++;
    return this.previous();
  }

  private isAtEnd(): boolean {
    return this.peek().type === TokenType.EOF;
  }

  private peek(): Token {
    return this.tokens[this.current]!;
  }

  private previous(): Token {
    return this.tokens[this.current - 1]!;
  }

  private errorAt(token: Token, message: string): ParseError {
    if (token.type === TokenType.EOF) {
      this.errs.push(`[line ${token.line}] Error at end: ${message}\n`);
    } else {
      this.errs.push(
        `[line ${token.line}] Error at '${token.lexeme}': ${message}\n`,
      );
    }
    return new ParseError();
  }

  private synchronize(): void {
    this.advance();
    while (!this.isAtEnd()) {
      if (this.previous().type === TokenType.SEMICOLON) return;
      switch (this.peek().type) {
        case TokenType.MODE:
        case TokenType.SYSTEM:
        case TokenType.RIVER:
        case TokenType.DAM:
        case TokenType.OUTLET:
        case TokenType.NODE:
        case TokenType.START:
        case TokenType.DISABLE:
        case TokenType.COMPARE:
        case TokenType.MAP:
        case TokenType.LIST:
        case TokenType.PRINT:
          return;
      }
      this.advance();
    }
  }
}
