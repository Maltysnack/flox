// Lexer. Mechanical port of Scanner.java.

import { Token, TokenType } from "./token.ts";

const KEYWORDS: Record<string, TokenType> = {
  system: TokenType.SYSTEM,
  map: TokenType.MAP,
  list: TokenType.LIST,
  print: TokenType.PRINT,
  help: TokenType.HELP,
  river: TokenType.RIVER,
  dam: TokenType.DAM,
  outlet: TokenType.OUTLET,
  mode: TokenType.MODE,
  start: TokenType.START,
};

export class Scanner {
  private readonly tokens: Token[] = [];
  private start = 0;
  private current = 0;
  private line = 1;

  constructor(
    private readonly source: string,
    private readonly errs: string[],
  ) {}

  scanTokens(): Token[] {
    while (!this.isAtEnd()) {
      this.start = this.current;
      this.scanToken();
    }
    this.tokens.push(new Token(TokenType.EOF, "", null, this.line));
    return this.tokens;
  }

  private scanToken(): void {
    const c = this.advance();
    switch (c) {
      case "[": this.addToken(TokenType.LEFT_BRACKET); break;
      case "]": this.addToken(TokenType.RIGHT_BRACKET); break;
      case "{": this.addToken(TokenType.LEFT_BRACE); break;
      case "}": this.addToken(TokenType.RIGHT_BRACE); break;
      case ",": this.addToken(TokenType.COMMA); break;
      case ";": this.addToken(TokenType.SEMICOLON); break;
      case "%": this.addToken(TokenType.PERCENT); break;
      case ":": this.addToken(TokenType.COLON); break;
      case "-":
        if (this.match(">")) {
          this.addToken(TokenType.ARROW);
        } else {
          // bare '-' is now a token (used in weighted arrows like
          // '-[0.5]->' and the closing '-' of '<-[0.5]-')
          this.addToken(TokenType.DASH);
        }
        break;
      case "<":
        if (this.match("-")) {
          this.addToken(TokenType.LARROW);
        } else {
          this.errs.push(`Unexpected character: < at line ${this.line}\n`);
        }
        break;
      case "/":
        if (this.match("/")) {
          // Line comment to end of line. Don't consume the newline so the
          // case below bumps the line counter.
          while (this.peek() !== "\n" && !this.isAtEnd()) this.advance();
        } else if (this.match("*")) {
          this.blockComment();
        } else {
          this.errs.push(`Unexpected character: / at line ${this.line}\n`);
        }
        break;
      case " ":
      case "\r":
      case "\t":
        // Ignore whitespace
        break;
      case "\n":
        this.line++;
        break;
      default:
        if (this.isDigit(c)) {
          this.number();
        } else if (this.isAlpha(c)) {
          this.identifier();
        } else {
          this.errs.push(`Unexpected character: ${c} at line ${this.line}\n`);
        }
        break;
    }
  }

  private blockComment(): void {
    while (!this.isAtEnd()) {
      if (this.peek() === "*" && this.peekNext() === "/") {
        this.advance(); // consume *
        this.advance(); // consume /
        return;
      }
      if (this.peek() === "\n") this.line++;
      this.advance();
    }
    this.errs.push(`Unterminated block comment starting near line ${this.line}\n`);
  }

  private identifier(): void {
    while (this.isAlphaNumeric(this.peek())) this.advance();
    const text = this.source.slice(this.start, this.current);
    const type = KEYWORDS[text] ?? TokenType.IDENTIFIER;
    this.addTokenLit(type, null);
  }

  private number(): void {
    while (this.isDigit(this.peek())) this.advance();
    if (this.peek() === "." && this.isDigit(this.peekNext())) {
      this.advance(); // consume '.'
      while (this.isDigit(this.peek())) this.advance();
    }
    const value = parseFloat(this.source.slice(this.start, this.current));
    this.addTokenLit(TokenType.NUMBER, value);
  }

  private match(expected: string): boolean {
    if (this.isAtEnd()) return false;
    if (this.source[this.current] !== expected) return false;
    this.current++;
    return true;
  }

  private peek(): string {
    if (this.isAtEnd()) return "\0";
    return this.source[this.current] ?? "\0";
  }

  private peekNext(): string {
    if (this.current + 1 >= this.source.length) return "\0";
    return this.source[this.current + 1] ?? "\0";
  }

  private isAlpha(c: string): boolean {
    return (c >= "a" && c <= "z") || (c >= "A" && c <= "Z");
  }

  private isAlphaNumeric(c: string): boolean {
    return this.isAlpha(c) || this.isDigit(c);
  }

  private isDigit(c: string): boolean {
    return c >= "0" && c <= "9";
  }

  private isAtEnd(): boolean {
    return this.current >= this.source.length;
  }

  private advance(): string {
    return this.source[this.current++] ?? "\0";
  }

  private addToken(type: TokenType): void {
    this.addTokenLit(type, null);
  }

  private addTokenLit(type: TokenType, literal: number | null): void {
    const text = this.source.slice(this.start, this.current);
    this.tokens.push(new Token(type, text, literal, this.line));
  }
}
