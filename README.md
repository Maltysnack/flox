# Flox

A small, domain-specific language for describing **flow networks** and simulating
how stuff moves through them over time.

Today, Flox models river systems: rivers and dams connected by a `->` operator,
driven by a daily rainfall signal. The same engine is being generalised so the
same syntax can express other flow networks — Markov chains, traffic, supply
chains, drainage — by switching modes.

## Quick start

```sh
./build.sh
java -cp build flox.Flox examples/canberra.flox
```

You'll get a printed map of the river network plus a per-day flow table and
the cumulative volume at each outlet.

## A taste

```flox
system Canberra;

Googong[2, 2.0, 0.7] -> googongDam{1000, 80%, 0.8, 1.2} -> Queanbeyan[3, 1.5];
upperMongolo[3, 1.8, 0.6] -> Queanbeyan[3, 1.5];
Queanbeyan[3, 1.5] -> centralMongolo[];
centralMongolo[] -> burleyGriffinDam{5000, 60%, 0.9, 1.5} -> lowerMongolo[];

map Canberra;
print Canberra 10;   // simulate 10 days
```

- `River[baseFlow, peakMultiplier, decayRate]` — a river segment.
- `Dam{capacity, threshold, normalRate, openRate}` — a dam.
- `->` — water flows from left to right.
- `[]` — a terminator (outlet to ocean / downstream-of-interest).
- Top-level verbs: `system`, `list`, `map`, `print`.

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
(units bug — see `ROADMAP.md`), no comments, positional parameters only,
single-direction flow, river preset only.
