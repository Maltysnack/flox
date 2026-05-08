#!/usr/bin/env -S npx tsx
// CLI entry point. Read a .flox file, interpret it, write output and errors
// to stdout / stderr respectively. Mirrors the Java Flox.java behaviour.

import { readFileSync } from "node:fs";
import { interpret } from "./flox.ts";

function main(): number {
  const argv = process.argv.slice(2);
  if (argv.length !== 1) {
    process.stderr.write("Usage: flox <file.flox>\n");
    return 64;
  }
  const path = argv[0]!;
  let source: string;
  try {
    source = readFileSync(path, "utf-8");
  } catch (e) {
    const msg = e instanceof Error ? e.message : String(e);
    process.stderr.write(`Could not read '${path}': ${msg}\n`);
    return 66;
  }
  const result = interpret(source);
  if (result.stdout) process.stdout.write(result.stdout);
  if (result.stderr) process.stderr.write(result.stderr);
  return result.hadError ? 65 : 0;
}

process.exit(main());
