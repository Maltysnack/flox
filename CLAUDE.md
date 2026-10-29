# Flox project instructions

## Read this first

**Always read `handoff.md` at session start.** It has the latest state, what just shipped, what's pending. Overwrite it before the session ends so the next one picks up clean. Treat it as living scratchpad, not history.

## Response style

- Short, terse, blunt, factual.
- No reexplaining what you just did.
- No flowery sign-offs, no compliments, no "let me know if...".
- Skip preamble. Answer first.
- Minimize tokens.
- Never use em dashes (global rule from ~/.claude/CLAUDE.md).

## Repo layout

- `src/flox/*.java` reference impl.
- `ts/src/*.ts` TS port, bundled with esbuild to `ts/dist/flox.js`.
- `examples/*.flox` example programs.
- `test/expected/*.expected` golden files shared by both backends.
- `test/test.sh` (Java) and `ts/test/test.sh` (TS) run the golden suite.
- The website's Flox page is `/Users/milo/Documents/Maltysnack.github.io/projects/flox/`, a single page of live examples (the playground and docs routes were removed 23-10-2026). After a rebuild, copy `ts/dist/flox.js` to `projects/flox/flox.js` there. Page changes are made in that repo, not here.

## Who leads

Flox work is driven from the site repo's sessions (see `DISPLAY.md` Boundary). An agent arriving from there edits this repo directly but must follow every rule in this file: read `handoff.md`, keep Java and TS byte-identical, run both test suites, rebuild `ts/dist/flox.js`, commit here before re-vendoring.

## Commands

- Java tests: `bash test/test.sh`
- TS tests: `PATH="/Users/milo/.nvm/versions/node/v20.20.1/bin:$PATH" bash ts/test/test.sh`
- TS build: `cd ts && PATH="/Users/milo/.nvm/versions/node/v20.20.1/bin:$PATH" npm run build`
- Update goldens: `bash test/test.sh --update`
- Both backends must produce byte-identical output.
