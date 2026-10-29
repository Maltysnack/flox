# Display brief: Flox

This file is the contract between the Flox repo and the site repo.

## Boundary

Work on Flox is led from the site repo (`Maltysnack.github.io`),
because the live page is the point of the project. The agent working
there decides what the page needs and makes the matching changes in
this repo directly: engine, showcase programs, and this brief.

Ownership of files does not move. Engine code, showcase sources and
this brief still live here, and every change to them follows this
repo's `CLAUDE.md` (both backends, byte-identical goldens, rebuild
`ts/dist/flox.js`). The site still never keeps Flox files of its own
beyond the vendored copies listed below; a renderer or any other
Flox logic is written here and vendored like the engine.

Order of work for a page change: update this brief, change and test
the engine or showcase here, commit here, then re-vendor and rebuild
the page in the site repo.

## What the site reads from this repo

Only these:

1. This file (`DISPLAY.md`) for structure and copy.
2. `ts/dist/flox.js` for the engine.
3. `examples/showcase/river.flox`, `markov.flox`, `grid.flox` for the
   three panels' program text, verbatim.

Nothing else. The site vendors them on each rebuild; it does not
modify them.

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

1. **Markov**. Source `examples/showcase/markov.flox`. `PassOrShoot`:
   a footballer deciding to pass again or shoot, converging to a
   goal/miss split over 200 ticks. Shows that arrows can carry
   probabilities and the chain settles on its own. Dropped before:
   grid.

2. **River** (the default mode). Source `examples/showcase/river.flox`.
   A `Valley` system: two rivers, one dam, one outlet, a five-day
   rainfall pulse. Shows the canonical use case and the per-day flow
   table. Dropped last if space is tight: never.

3. **Grid**. Source `examples/showcase/grid.flox`. `PowerGrid` with
   `compare`: baseline, plant offline, tie line cut, side by side.
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

- **Engine**: `ts/dist/flox.js` from this repo, one ES module. Exports
  `interpret(source)` returning `{ stdout, stderr, hadError }`.
- **Example sources**: the three files in `examples/showcase/` from
  this repo, read verbatim into the page as the initial program text.
  No trimming, no edits, no site-side rewrites.
- **Update cadence**: this repo rebuilds the engine and updates the
  showcase files when they change; the site re-vendors on its next
  rebuild. There is no runtime fetch and no cross-repo link.
- **Who writes**: this repo owns the engine, the showcase sources, and
  this brief. The site owns only the page shell: layout, styling, the
  editor textarea, the rerun loop.

## Voice

Flat, factual, present tense. "The dam fills on day two." Not "watch
as the dam fills!". No exclamation marks. No "powerful", "elegant",
"intuitive". Australian spelling (modelled, visualise, behaviour).

Never em dashes. Commas, full stops, semicolons, or restructure.
