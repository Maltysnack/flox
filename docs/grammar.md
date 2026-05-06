# Flox Grammar (current — Milestone 0 / river mode only)

```
program     → statement* EOF
statement   → systemDecl | chainDecl | listStmt | mapStmt | printStmt
systemDecl  → "system" IDENTIFIER ";"
chainDecl   → node ( "->" node )* ";"
node        → river | dam | terminator
river       → IDENTIFIER "[" NUMBER "," NUMBER ( "," NUMBER )? "]"
dam         → IDENTIFIER "{" NUMBER "," percent "," NUMBER "," NUMBER "}"
terminator  → IDENTIFIER "[" "]"
listStmt    → "list" IDENTIFIER ";"
mapStmt     → "map" IDENTIFIER ";"
printStmt   → "print" IDENTIFIER NUMBER? ";"
percent     → NUMBER "%" | NUMBER          // 80% or 0.8
IDENTIFIER  → [a-zA-Z][a-zA-Z0-9_]*
NUMBER      → [0-9]+ ( "." [0-9]+ )?
```

## Semantics (river mode)

- `River[base, peak, decay]` — a river segment.
  - `base` (L/s): baseline flow with no rainfall.
  - `peak`: multiplier applied to rainfall (mm) to compute the peak surge.
  - `decay`: fraction of the surge retained each subsequent day (0–1).
- Hydrograph for a river given `r` mm of rainfall:
  - Day 1 (rising): `base + 0.3 × peak × r`
  - Day 2 (peak):  `base + peak × r`
  - Day n ≥ 3:     `base + peak × r × decay^(n-2)`
- `Dam{capacity, threshold, normalRate, openRate}` — a dam.
  - `capacity` (ML): max storage.
  - `threshold` (% of capacity): when storage crosses this, the dam "opens".
  - `normalRate`: outflow / inflow when below threshold.
  - `openRate`:   outflow / inflow when above threshold.
  - **Known bug**: inflow units (L/s) and storage units (ML) are mixed in the
    current implementation. Threshold is effectively never reached.
- `[]` — terminator. A node that accumulates everything that flows into it.
- `->` — directed flow, left to right. Multiple edges out of a node split flow
  evenly (current behaviour; weighted edges are a roadmap item).

## Reserved words

`system`, `list`, `map`, `print`.

(Future modes will add: `mode`, `chain`, `state`, `start`, `with`, `rainfall`.)
