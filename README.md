# Flox

A small language for **flow networks**. You write nodes and arrows, and
it simulates how something moves through them over time.

One grammar, three modes:

- `river` (default): water through rivers, dams and outlets, driven by
  a daily rainfall signal.
- `markov`: probability mass through a chain of states.
- `grid`: current through a network that balances supply and demand.

The declarations, the `->` arrows and `print` are the same across all
three. A `mode` line picks the physics.

## Quick start

```sh
./build.sh
java -cp build flox.Flox examples/canberra.flox
```

Prints the network and a per-day flow table.

## A taste

```flox
system Demo;

Spring    -> Confluence -> Outlet;
Tributary -> Confluence;

river  Spring     { base: 2, peak: 2.0, decay: 0.7 }
river  Tributary  { base: 1, peak: 1.5, decay: 0.6 }
river  Confluence { base: 3, peak: 1.8 }
outlet Outlet;

map Demo;
print Demo 5;   // simulate at 5mm rainfall
```

See `docs/grammar.md` for the full grammar and `examples/` for more
programs.

## Project layout

```
src/flox/      Java reference implementation
ts/            TypeScript port, bundles to ts/dist/flox.js
examples/      Sample .flox programs
test/          Golden-file tests; run with `bash test/test.sh`
docs/          Grammar reference
```

Both backends share the `examples/` programs and the `test/expected/`
golden files, and must produce byte-identical output.

## What's here

A scanner, parser, two-pass tree-walking interpreter, and three
simulators, in about two thousand lines across Java and TypeScript.
A sketching tool for the shape of a system, not a hydrology engine or
a power-flow solver.

The TypeScript build powers the live page at
[maltysnack.github.io/projects/flox](https://maltysnack.github.io/projects/flox/).
See [`DISPLAY.md`](DISPLAY.md) for the brief that page follows.

## Working principles

- **Don't break the river preset.** Every change is additive or behind
  a mode flag.
- **The grammar stays small.** If a feature can be expressed with
  existing primitives, don't add new syntax for it.
