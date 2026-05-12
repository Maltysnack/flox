// Token types and literal values produced by the scanner.

export enum TokenType {
  // Single-character tokens
  SEMICOLON = "SEMICOLON",
  LEFT_BRACKET = "LEFT_BRACKET",
  RIGHT_BRACKET = "RIGHT_BRACKET",
  COMMA = "COMMA",
  LEFT_BRACE = "LEFT_BRACE",
  RIGHT_BRACE = "RIGHT_BRACE",
  PERCENT = "PERCENT",
  COLON = "COLON",
  DASH = "DASH",

  // One or two character tokens
  ARROW = "ARROW",
  LARROW = "LARROW",

  // Literals
  IDENTIFIER = "IDENTIFIER",
  NUMBER = "NUMBER",

  // Keywords
  SYSTEM = "SYSTEM",
  MAP = "MAP",
  LIST = "LIST",
  PRINT = "PRINT",
  HELP = "HELP",
  RIVER = "RIVER",
  DAM = "DAM",
  OUTLET = "OUTLET",
  NODE = "NODE",
  MODE = "MODE",
  START = "START",
  DISABLE = "DISABLE",

  EOF = "EOF",
}

export class Token {
  constructor(
    readonly type: TokenType,
    readonly lexeme: string,
    readonly literal: number | null,
    readonly line: number,
  ) {}

  toString(): string {
    return `${this.type} ${this.lexeme} ${this.literal ?? ""}`;
  }
}
