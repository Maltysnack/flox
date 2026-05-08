# Flox

A small, domain-specific language for describing **flow networks** and simulating
how stuff moves through them over time.

Today, Flox models river systems: rivers and dams connected by a `->` operator,
driven by a daily rainfall signal. The same engine is being generalised so the
same syntax can express other flow networks (Markov chains, traffic, supply
chains, drainage) by switching modes.

## Quick start

```sh
./build.sh
java -cp build flox.Flox examples/canberra.flox
```

You'll get a printed map of the river network plus a per-day flow table and
the cumulative volume at each outlet.

## A taste

```flox
system Demo;

// Chains: the network shape, top to bottom.
Spring    -> Confluence -> Outlet;
Tributary -> Confluence;

// Declarations: rich blocks, source order doesn't matter.
river  Spring     { base: 2, peak: 2.0, decay: 0.7 }
river  Tributary  { base: 1, peak: 1.5, decay: 0.6 }
river  Confluence { base: 3, peak: 1.8 }
outlet Outlet;

map Demo;
print Demo 5;   // simulate at 5mm rainfall
```

- `river Name { base: ..., peak: ..., decay: ... }`: a river segment.
- `dam Name { capacity: ..., threshold: ..., normalRate: ..., openRate: ... }`: a dam.
- `outlet Name;`: a zero-flow accumulator.
- `->`: directed flow, left to right.
- Top-level verbs: `system`, `river`, `dam`, `outlet`, `list`, `map`, `print`.

See `docs/grammar.md` for the full grammar and `examples/` for more programs.

## Project layout

```
src/flox/      Java reference implementation
ts/            TypeScript port (foundation for the browser playground)
examples/      Sample .flox programs
test/          Golden-file tests; run with `bash test/test.sh`
docs/          Grammar reference, design notes
build.sh       Java build script
```

Both backends share the `examples/` programs and the `test/expected/`
golden files. They must produce byte-identical output.

## Status

Working: scanner, parser, AST, two-pass tree-walking interpreter, river
hydrograph simulation with rainfall series, dam thresholds, network
visualisation, multi-day flow tables, golden-file test suite.

The project is at the end of Milestone 1. Future work is tracked as
[GitHub issues](https://github.com/Maltysnack/flox/issues), grouped by
milestone:

- **M2**: TypeScript port and browser playground
- **M3**: Generalise the engine; introduce `mode` for non-river networks
- **M4**: 3D rendering with Three.js
- **M5**: More modes (traffic, SIR, supply chain, plumbing)

Cross-cutting concerns also live as issues: a more realistic dam model,
hydrological loss accounting, richer graphical output.

## Working principles

The guiding north star: **Flox should be a flow-network DSL where rivers
are one mode and Markov chains, traffic, and other compartmental models
are others. Same parser, same engine, different presets.**

- **Don't break the river preset.** Every change is additive or behind
  a mode flag.
- **Each milestone ends with a public artifact.** No internal-only
  milestones; every meaningful change should land somewhere a visitor
  can see it.
- **Resist scope creep inside an issue.** Things that come up belong in
  a new issue, not in the current branch.
- **The grammar stays small.** If a feature can be expressed with
  existing primitives, don't add new syntax for it.
