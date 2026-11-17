# Handoff

## Where Flox is

Finished small portfolio piece. One grammar, three modes (markov,
river, grid), two backends (Java + TS), byte-identical goldens,
live editable page at maltysnack.github.io/flox/. No cloud, no accounts.

## Process (changed 17-11-2026)

The live page now lives in this repo (`index.html`, `page/`), served by
GitHub Pages from the repo root. Follow the Development rules in
`README.md` and `DISPLAY.md`; no other repo is involved.

## State of this repo

- All committed. `flox-archive/` is untracked on purpose.
- `examples/showcase/{markov,river,grid}.flox` are the panel sources.
- `parseGraph(source)` in `ts/src/flox.ts` already returns nodes,
  edges, river per-day `series`, and grid per-scenario `levels` and
  `flows`. Built for the removed playground; nothing renders it yet.

## Panel feedback, acted on 29-10-2026

- River: flow table transposed (days as rows, places as columns),
  every flow in ML/day, dams get a `<name> held` column (ML stored),
  the outlet gets a running `<name> total`. All river goldens
  regenerated. The flat Reservoir run was not a bug: the dam releases
  openRate (20 ML/day) whenever that day's inflow takes it over 80%;
  the held column now shows that. `units { flow: ... }` no longer
  affects the river table.
- Open: a dam over capacity after release silently loses the excess
  (`currentLevel` clamped, no spill downstream). No showcase or
  example hits it. Fix would be spilling into outflow.
- Markov: showcase is now `Possession`, an ergodic five-state cycle.
  Outgoing weights were already validated to sum to 1 (runtime error
  with line numbers); `test/cases/err_markov_sum.flox` now pins it.
  Still prints only the final distribution.
- Grid: showcase is now `Ring`, one plant and three towns on a ring,
  scenarios are two line cuts; the north cut reverses North to East.
  Compare output labels levels "power flows from high to low".

## Visual renderer idea (thinking only, nothing built)

Goal: draw each system and animate it instead of only printing a table.
- 2D SVG, not three.js. Under 10 nodes; dots moving along edges.
- River: top to bottom by downstream depth, edge width by flow, day
  play/scrub, dam fill gauge, outlet filling.
- Markov: nodes on a circle, edge thickness by probability, a token
  hopping per tick, bars converging. Needs a new per-tick distribution
  series from the engine (parseGraph has river series only).
- Grid: dots at speed proportional to flow; scenario toggle buttons
  instead of three columns.
- Layout: layer by topological depth; dagre only if that fails.
- Editing: start one-way (code to picture). Then drag nodes (easy),
  drag a weight that rewrites the number in the source (moderate),
  draw nodes/edges with two-way sync (hard).
- Renderer is Flox logic, so it is a second module in this repo,
  named in `DISPLAY.md`.

## Known dead code (TS engine)

Prototype `money` and `settle` modes across `ts/src/*`, plus
`examples/money_budget.flox` and `examples/settle_trip.flox`. Hidden
from the page by `DISPLAY.md` but shipped in `flox.js`. Prune when
confirmed dead. Never ported to Java.