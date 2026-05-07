# Flox Roadmap

This is the long-term plan. Each milestone is small enough to finish in a
sitting or two; the whole thing is staged so nothing depends on a future
milestone before it.

The guiding north star: **Flox should be a flow-network DSL where rivers
are one mode and Markov chains, traffic, and other compartmental models are
others. Same parser, same engine, different presets.**

---

## Milestone 1: Harden what's here

Small fixes that make the current river language solid before extending it.

- [x] `// line comments` and `/* block comments */` in the scanner.
- [ ] **Language redesign: declarative blocks + chain-first programs.**
      Replace inline positional/curly parameters with top-level node
      declarations and chains-of-bare-references. Programs read chains-first
      so the network shape is visible at a glance; declarations live below as
      reference material. Subsumes the old "named parameters" and
      "declare-once / reference-by-name" items.

      ```flox
      system Canberra;

      Googong -> googongDam -> Queanbeyan -> centralMongolo;

      river Googong     { base: 2, peak: 2.0, decay: 0.7 }
      river Queanbeyan  { base: 3, peak: 1.5 }
      dam   googongDam  { capacity: 1000, threshold: 80%,
                          normalRate: 0.8, openRate: 1.2 }
      outlet centralMongolo
      ```

      Sub-tasks:
      - [ ] New keywords: `river`, `dam`, `outlet`.
      - [ ] Block syntax `name: value` with commas or newlines as separators.
            Single-line and multi-line forms both legal.
      - [ ] Chains become pure references. No inline parameters.
      - [ ] Two-pass interpreter. Pass 1 collects node declarations into a
            symbol table. Pass 2 walks chains and resolves bare names. Error
            if a chain references an undeclared node; warn if a node is
            declared but never connected.
      - [ ] Error messages point at both sites (the chain reference and the
            (missing or stray) declaration).
      - [ ] Migrate every program in `examples/` to the new grammar.
      - [ ] Rewrite `docs/grammar.md` and the public grammar reference
            (`maltysnack-site/projects/flox/docs/grammar.html`) to match.

- [ ] Fix the dam units bug. Inflow is L/s, currentLevel is ML; they get
      added together. Convert with `inflow_L_per_s * 0.0864 = ML/day` before
      mutating the level. With this fixed, `threshold` and `openRate` actually
      do something.
- [ ] Rainfall as a series, not a scalar:
      `print Sydney with rainfall [0, 5, 30, 10, 0, 0];` for storm events.
- [ ] Tests. Even just a `test/` dir of `.flox` files with `.expected` outputs
      and a shell script that diffs them. Catches regressions when the engine
      is generalised in M3.

## Milestone 2: TypeScript port + browser playground

The thing that takes Flox from "thing on disk" to "thing on a website."

- [ ] Port scanner / parser / AST / interpreter to TypeScript. Mechanical;
      ~1000 lines, no external deps.
- [ ] Monaco editor on the left, output panel on the right.
- [ ] D3 graph rendering of the network.
- [ ] Recharts (or similar) hydrograph for the per-day flow table.
- [ ] "Load example" dropdown with the example .flox files.
- [ ] Permalinks: program encoded in URL hash so you can share a Flox
      program by sending a link.

## Milestone 3: Generalise the engine with a `mode` switch

The unlocking move. Instead of one tick rule, the engine becomes a graph
simulator parameterised by a tick function.

- [ ] Top-level `mode river;` / `mode markov;` declaration.
- [ ] Edge weights: `A -[0.7]-> B`. Optional in `river` mode (defaults
      preserved), required in `markov` mode (sum-to-1 enforced per node).
- [ ] Allow cycles. Replace the DAG-only assumption in `RiverSystem` with
      matrix-iteration for steady-state, and step-by-step for transient.
- [ ] `mode markov` semantics:
      - `state` instead of `river` (or just keep `river`-but-meaningless).
      - `start NodeName 1.0;` to set initial distribution.
      - Tick: each node redistributes its current mass per outgoing weights.
      - Convergence detection: print steady-state distribution.

Demo to ship alongside this milestone:
```flox
mode markov;
chain PageRank;

A -[0.5]-> B;
A -[0.5]-> C;
B -[1.0]-> A;
C -[1.0]-> A;

start A 1.0;
print PageRank 30;
```

## Milestone 4: 3D rendering

You asked for it. Procedural / random visualisation of the network in 3D.

- [ ] Three.js scene driven by the parsed AST.
- [ ] Rivers rendered as procedural splines on a generated terrain. Width
      proportional to flow, animated water texture.
- [ ] Dams as box geometries with water-level indicators.
- [ ] Camera fly-through of the system from sources to outlet.
- [ ] Random seed in the URL so layouts are reproducible.
- [ ] Markov-mode rendering: nodes as spheres in 3D space, edges as
      animated particles whose density encodes weight.

## Milestone 5: More modes

Once the engine generalisation in M3 is stable, each new mode is a
small addition: a node type, a tick rule, a printer.

- [ ] `mode traffic`: intersections, roads, arrival rate.
- [ ] `mode sir`: epidemic compartmental model.
- [ ] `mode supply`: factories &rarr; warehouses &rarr; stores.
- [ ] `mode plumbing`: bidirectional pressure-driven flow.

Each should ship with one canonical example and a writeup on the project
website.

---

## Working principles

- **Don't break the river preset.** Every change is additive or behind a mode flag.
- **Each milestone ends with a demo on the website.** No "internal-only" milestones.
- **Resist scope creep inside a milestone.** Things that come up belong here, in this doc, not in the current branch.
- **The grammar stays small.** If a feature can be expressed with existing primitives, don't add new syntax for it.
