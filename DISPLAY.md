# Display brief: Flox

This file is the brief for the live page.

## Boundary

The page lives in this repo: `index.html` at the root, with its
stylesheet and top bar in `page/`. GitHub Pages serves the repo root
at maltysnack.github.io/flox/. The portfolio site only links here.

Order of work for a page change: update this brief, change and test
the engine or showcase following the Development rules in `README.md`
(both backends, byte-identical goldens, rebuild `ts/dist/flox.js`),
then commit. The page picks up the new engine and showcase directly.

## What the page reads

1. `ts/dist/flox.js` for the engine.
2. `examples/showcase/river.flox`, `markov.flox`, `grid.flox` for the
   three panels' program text, verbatim.

## The point

A visitor browsing the portfolio. The page answers: **what is this
little language, and what does a program in it look like running?**

Flox is shown as a finished sketch, not a product. No roadmap, no
pricing, no "coming soon". One scroll, three live examples, done.

## Headline

> A tiny language where you draw a network with arrows and it
> simulates what flows through it.

Immediately under the headline, one line of positioning:

> Water, probability and current, same syntax, three simulators
> sharing one parser.

Then one line of instruction: every example on the page is live, edit
the program and the output reruns.

## Panels, in priority order

Each panel is an editable program on the left, its output on the
right, loaded with the showcase file named below. The visitor can
type over it; the engine reruns.

Panel order matters. Markov leads because it is the shortest and
reads most like English, so the "nodes and arrows" idea lands in
three seconds. River comes second once the visitor is bought in and
can absorb its richer vocabulary. Grid closes with the one feature
worth flexing (scenarios).

1. **Markov**. Source `examples/showcase/markov.flox`. `Possession`:
   a football team's ball moving between defence, midfield, attack,
   shot and goal, every state reachable from every other. The chain
   settles on how much of the game is spent in each state, which is
   not obvious from the arrows. Shows that arrows can carry
   probabilities and the chain settles on its own. Dropped before:
   grid.

2. **River** (the default mode). Source `examples/showcase/river.flox`.
   A `Valley` system: two rivers, one dam, one outlet, two rain events.
   Shows the canonical use case and the flow table: days as rows,
   places as columns, every flow in ML/day so a node's inflows add up
   to its own column, plus the dam's stored volume and the outlet's
   running total in ML. Dropped last if space is tight: never.

3. **Grid**. Source `examples/showcase/grid.flox`. `Ring`: one plant
   feeding three towns round a ring, compared under baseline, north
   line cut and east line cut. Each cut forces power the long way
   round; with the north line cut, the North to East line reverses.
   Shows the third mode and the one feature worth showing (scenarios).
   Dropped first if the page feels long.

After the panels: a compact grammar table (one row per construct, one
line each). Nothing else.

## Never show

- Accounts, saving, sharing, galleries, pricing, "upgrade", "sign in",
  "coming soon". The project is presented as finished.
- Milestone roadmap, GitHub issues, "future work".
- Any Flox mode other than river, markov, grid. Prototype modes that
  exist in the engine but are not listed in the Panels section above
  must not be surfaced.
- Benchmarks, LOC counts, performance claims.

## Data contract

- **Engine**: `ts/dist/flox.js`, one ES module. Exports
  `interpret(source)` returning `{ stdout, stderr, hadError }`.
- **Example sources**: the three files in `examples/showcase/`, fetched
  verbatim as the initial program text. No trimming, no edits.
- **Page shell**: `index.html` and `page/` own layout, styling, the
  editor textarea and the rerun loop, nothing else.

## Voice

Flat, factual, present tense. "The dam fills on day two." Not "watch
as the dam fills!". No exclamation marks. No "powerful", "elegant",
"intuitive". Australian spelling (modelled, visualise, behaviour).

Never em dashes. Commas, full stops, semicolons, or restructure.
