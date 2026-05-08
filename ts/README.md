# Flox (TypeScript)

TypeScript port of the Java reference implementation. Same grammar,
same semantics, byte-identical output. Foundation for the browser
playground (issues #5-#9).

## Run

```sh
cd ts
npm install
npx tsx src/cli.ts ../examples/demo.flox
```

Or via the `flox` script:

```sh
npm run flox -- ../examples/demo.flox
```

## Tests

Uses the same golden files as the Java suite, in `../test/expected/`.

```sh
cd ts
bash test/test.sh
```

The Java and TS interpreters must both produce identical output for
each `.flox` program. If they drift, the test fails.

## Public API

```ts
import { interpret } from "./src/flox.ts";

const result = interpret(programSource);
result.stdout    // string, ready to display
result.stderr    // string, errors with line numbers
result.hadError  // boolean
```

This is what the browser playground will call.

## Layout

```
src/
  token.ts        Token + TokenType
  scanner.ts      Lexer
  ast.ts          AST node discriminated union
  parser.ts       Two-pass parser
  runtime.ts      River, Dam, RiverSystem (the simulator)
  output.ts       ASCII output formatter
  interpreter.ts  Two-pass interpreter
  flox.ts         Public interpret() entry point
  cli.ts          Command-line wrapper
test/
  test.sh         Golden-file test runner (uses ../test/expected/)
```

No build step. `tsx` runs the TypeScript directly.
