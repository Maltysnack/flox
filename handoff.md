# Handoff

## Where Flox is

Finished small portfolio piece. One grammar, three modes (markov,
river, grid), two backends (Java + TS), byte-identical goldens
(25/25), live editable page on the portfolio site. Not building
toward anything bigger. No cloud, no accounts, no roadmap.

## Boundary rule

Flox is controlled entirely from this repo. The site repo
(`Maltysnack.github.io`) is a dumb renderer and originates nothing.
It reads exactly three things from here, verbatim:

1. `DISPLAY.md` (structure, copy, panel order, voice)
2. `ts/dist/flox.js` (the engine, one ES module)
3. `examples/showcase/{markov,river,grid}.flox` (panel programs)

If a Flox change is needed, DISPLAY.md changes first and the site
re-vendors.

## This repo's state

- `DISPLAY.md` is current: markov leads, hookier headline, "one
  parser, three simulators" line up front.
- `examples/showcase/` has the three canonical panel files.
- `README.md` is stripped to portfolio scope. No milestones, no
  pricing, no cloud. Points at DISPLAY.md.
- `docs/flox-cloud-spec.md` is deleted.
- All committed (28-10-2026): portfolio-scope commit, then a
  separate commit for the money/settle prototype in `ts/src`,
  `ts/dist/flox.js` and its two examples.
- `flox-archive/` is untracked and intentionally left out.

## Known dead code (TS engine)

Prototype `money` and `settle` modes added by a previous session
across `ts/src/{modes,interpreter,parser,scanner,ast,token,output,
runtime,flox}.ts`, plus `examples/money_budget.flox` and
`examples/settle_trip.flox`. DISPLAY.md forbids surfacing them, so
they are invisible on the page but still ship in `flox.js`. Prune
when the prototype is confirmed dead. Java backend was never ported;
no parity work needed on removal.

## Work pending in the SITE repo (not this one)

Another session owns these; do not touch from here.

- Rebuild `projects/flox/index.html` against the current `DISPLAY.md`:
  new headline, positioning line, three panels in the new order
  (markov, river, grid), each loaded verbatim from
  `examples/showcase/*.flox`. Drop the current fourth "creek" panel.
  Re-vendor `ts/dist/flox.js`.
- Delete every Flox Cloud leftover (playground/cloud/, gallery/,
  cloud/schema.sql, cloud/SPEC.md, GO-LIVE.md, api/flox-*.js, and
  any toolbar/`window.FloxPlayground`/`flox-ready` wiring in
  index.html). Check actual uncommitted state first; the list above
  is from an older handoff and may be stale.

## Unrelated feedback (NOT Flox, do not act on here)

User mentioned issues on a separate algorithms visualisation page
on the site (A* pathfinding, 15-puzzle, ant colony). None of these
are Flox. Routed to whoever owns that project:

- A*: "New run" resets the algorithm selection; read dropdown at
  click time.
- 15-puzzle: no rules shown; add tooltip or Wikipedia link.
- Ant colony: too fast to follow; no way to add custom points;
  feels preplanned. Add speed slider + click-to-plant cities.

## Pending

- Site session: rebuild page + delete Cloud leftovers.
- Prune money/settle TS code when confirmed dead.
