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
src/flox/      Java source for the interpreter
examples/      Sample .flox programs
docs/          Grammar reference, design notes
build.sh       One-line build script
ROADMAP.md     Where this is going
```

## Status

Working: parser, AST, tree-walking interpreter, river hydrograph simulation,
network visualisation, multi-day flow tables.

Known limitations the roadmap addresses: dam storage dynamics are stubbed
(units bug, see `ROADMAP.md`), single-direction flow, river preset only.
