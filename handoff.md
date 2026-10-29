# Handoff

## Where Flox is

Finished small portfolio piece. One grammar, three modes (markov,
river, grid), two backends (Java + TS), byte-identical goldens,
live editable page on the portfolio site. No cloud, no accounts.

## Process (changed 29-10-2026)

Work is now led from the site repo (`Maltysnack.github.io`). That
repo's agent decides what the page needs and edits this repo directly,
following `CLAUDE.md` here. Files still live where they did: engine,
showcase programs and `DISPLAY.md` in this repo; page shell in the
site repo. See `DISPLAY.md` Boundary.

## State of this repo

- All committed. `flox-archive/` is untracked on purpose.
- `examples/showcase/{markov,river,grid}.flox` are the panel sources.
- `parseGraph(source)` in `ts/src/flox.ts` already returns nodes,
  edges, river per-day `series`, and grid per-scenario `levels` and
  `flows`. Built for the removed playground; nothing renders it yet.

## Feedback on the live panels (29-10-2026), not yet acted on

River:
- Units are incoherent: rainfall mm/day, flow "volume per day in L/s"
  (self-contradictory header), outlet cumulative in ML. Nothing visibly
  adds up. Proposed: one unit (ML/day) for every row so the rows sum to
  the outlet total; show rainfall as its own row.
- Table is 10 days wide and side-scrolls. User suggested cutting to 5
  days, but that loses the recession (dam draining after rain), the
  interesting part. Preferred: transpose (days as rows, places as
  columns). This is an output format change: both backends + goldens.
- Reservoir flat at 231.5 for days 2 to 7 reads like a bug; check.

Markov:
- `PassOrShoot` is trivial: Pass and Shoot are transient, so the result
  is just Shoot's 10/90 split and the Pass loop is irrelevant. Needs an
  ergodic example where the stationary split is non-obvious, e.g. a
  possession cycle (defence, midfield, attack, shot, back to defence)
  or weather.
- Honest assessment: good notation for small chains, weak as a tool.
  Only prints the final distribution; no hitting times, no transient
  view. Its value on the page is readability plus (future) animation.
- Unchecked: whether markov mode validates outgoing probabilities sum
  to 1.

Grid:
- User does not understand it, and the example earns that. "Tie line
  cut" equals Baseline (the line carried 0). "Plant offline" works only
  because Backup.supply is hand-set to 130. Node "levels" have no
  physical meaning. Rework so each scenario visibly reroutes, or drop.

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
- Renderer is Flox logic, so it is written in this repo as a second
  vendored module and named in `DISPLAY.md`.

## Known dead code (TS engine)

Prototype `money` and `settle` modes across `ts/src/*`, plus
`examples/money_budget.flox` and `examples/settle_trip.flox`. Hidden
from the page by `DISPLAY.md` but shipped in `flox.js`. Prune when
confirmed dead. Never ported to Java.

## Unrelated feedback (not Flox)

Algorithms visualisation page on the site: A* "New run" resets the
algorithm selection; 15-puzzle shows no rules; ant colony too fast,
no custom points. Belongs to that project.
