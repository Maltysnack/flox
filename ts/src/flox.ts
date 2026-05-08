// Public entry point. Run a Flox program and capture its output.

import { Interpreter } from "./interpreter.ts";
import { Parser } from "./parser.ts";
import { Scanner } from "./scanner.ts";

export interface InterpretResult {
  stdout: string;
  stderr: string;
  hadError: boolean;
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
