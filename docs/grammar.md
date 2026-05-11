# Flox Grammar

Version 0.4. Covers river, markov, and grid modes.

```
program     -> statement* EOF
statement   -> modeDecl | systemDecl | nodeDecl | chain | actionStmt
modeDecl    -> "mode" IDENTIFIER ";"
systemDecl  -> "system" IDENTIFIER ";"
nodeDecl    -> riverDecl | damDecl | outletDecl | genericNode
riverDecl   -> "river"   IDENTIFIER block ";"?
damDecl     -> "dam"     IDENTIFIER block ";"?
outletDecl  -> "outlet"  IDENTIFIER ( block )? ";"?
genericNode -> "node"    IDENTIFIER ( block )? ";"?
block       -> "{" ( attr ( ","? attr )* )? "}"
attr        -> IDENTIFIER ":" "-"? NUMBER "%"?
chain       -> IDENTIFIER ( arrow IDENTIFIER )+ ";"
arrow       -> "->" | "<-" | "-[" NUMBER "]->" | "<-[" NUMBER "]-"
actionStmt  -> startStmt | listStmt | mapStmt | printStmt | helpStmt
startStmt   -> "start" IDENTIFIER NUMBER ";"
listStmt    -> "list"  IDENTIFIER ";"
mapStmt     -> "map"   IDENTIFIER ";"
printStmt   -> "print" IDENTIFIER ( NUMBER | "[" numberList "]" )? ";"
helpStmt    -> "help" ";"
numberList  -> NUMBER ( "," NUMBER )*
IDENTIFIER  -> [a-zA-Z][a-zA-Z0-9_]*
NUMBER      -> [0-9]+ ( "." [0-9]+ )?
```

## Lexical structure

Plain ASCII. Whitespace separates tokens but is otherwise insignificant.

- `// line comment` runs to end of line.
- `/* block comment */` may span multiple lines; does not nest.

## Modes

`mode <name>;` selects the simulator. The default is `river` if no
mode is declared. Currently supported:

- `river`: water flowing through rivers, dams, and outlets; driven by
  per-day rainfall.
- `markov`: probability mass redistributed across weighted edges that
  sum to 1 per source.
- `grid`: an undirected network where nodes inject (supply +) or draw
  (supply -) and levels equilibrate.

## Statements

| Form                                | Meaning |
| ----------------------------------- | ------- |
| `mode name;`                        | Selects the simulator (river, markov, grid). |
| `system Name;`                      | Declares a named system; later declarations attach to it. |
| `river Name { ... }`                | Declares a river node. |
| `dam Name { ... }`                  | Declares a dam node. |
| `outlet Name;`                      | Declares a zero-flow accumulator. |
| `node Name;` or `node Name { ... }` | Generic state container, mode-agnostic. |
| `A -> B -> C;`                      | Chain of bare references. Names must be declared in the same system. |
| `start Name mass;`                  | Markov mode: sets initial mass on a node. |
| `list Name;`                        | Lists every node in the named system. |
| `map Name;`                         | Prints the mode-appropriate diagram. |
| `print Name;`                       | Simulates with the mode's default argument. |
| `print Name n;`                     | River: `n` mm rainfall; markov/grid: `n` ticks. |
| `print Name [r1, r2, ...];`         | River only: per-day rainfall series in mm. |
| `help;`                             | Prints a short CLI syntax reference. |

The interpreter runs in two passes. Pass 1 collects declarations and
chains. A resolve step validates each chain reference, then a
mode-specific validator runs. Pass 2 executes `list`, `map`, and
`print` in source order.

## Chains and arrows

Four arrow forms:

| Form          | Meaning |
| ------------- | ------- |
| `A -> B`      | Directed edge A to B, weight 1. |
| `A <- B`      | Directed edge B to A, weight 1. |
| `A -[w]-> B`  | Directed edge A to B, weight w. |
| `A <-[w]- B`  | Directed edge B to A, weight w. |

Chains can mix directions. The arrangement is purely a readability
choice; each adjacent pair compiles to one directed edge.

Grid mode ignores arrow direction (every edge is bidirectional).
Parallel edges between the same pair of nodes sum their conductances.

## Node attributes

### River

| Attribute | Required | Meaning |
| --------- | -------- | ------- |
| `base`    | yes      | Baseline flow in litres per second with no rainfall. |
| `peak`    | yes      | Multiplier applied to rainfall (mm) to compute the peak surge. |
| `decay`   | no       | Fraction of the surge retained each subsequent day. Default `0.7`. |
| `supply`  | no       | Grid-mode supply (+) or load (-). Default `0`. |

### Dam

| Attribute     | Required | Meaning |
| ------------- | -------- | ------- |
| `capacity`    | yes      | Maximum storage in megalitres. |
| `threshold`   | yes      | Storage level at which the dam opens. `80%` or `0.8`. |
| `normalRate`  | yes      | Outflow as a fraction of inflow when below threshold. |
| `openRate`    | yes      | Outflow as a fraction of inflow when at or above threshold. |

### Outlet

No attributes. Accumulates everything that flows into it.

### Node (generic)

| Attribute | Required | Meaning |
| --------- | -------- | ------- |
| `supply`  | no       | Grid-mode supply (+) or load (-). Default `0`. |

Prefer `node` in markov and grid programs; `river / dam / outlet` are
river-mode-specific.

## River semantics

### Hydrograph

Given `r` mm of rainfall on day 0, river flow on day *n* is:

```
day 1 (rising):  base + 0.3 * peak * r
day 2 (peak):    base + peak * r
day n >= 3:      base + peak * r * decay^(n - 2)
```

With a rainfall series, each non-zero day kicks off its own
hydrograph; flow on day *n* is the superposition of every event so
far. A series shorter than ten days is padded with zeros.

### Dam outflow

If storage is below threshold, outflow = `inflow * normalRate`. At or
above threshold, outflow = `inflow * openRate`. Storage updates each
day by `inflow - outflow` with units converted at the boundary
(L/s to ML/day).

### Edge weights

Edge weights scale flow on that edge. Default weight 1. Multiple
outgoing edges with default weight each receive 100% of their source
(a clone). Use explicit weights to model real splits.

## Markov semantics

Each node carries a non-negative `mass`. Initial distribution from
`start` statements. Each tick, every node's mass is redistributed
across its outgoing edges in proportion to weights.

### Validation

- For each node with outgoing edges, weights must sum to `1.0`
  (within `1e-6`).
- At least one node must have non-zero initial mass.
- Every `start` reference must name a declared node.

### Convergence

Halts at the tick budget or when the maximum per-node mass change in
a single tick drops below `1e-9`.

## Grid semantics

Each node carries a `level` (initialised to 0) and an optional fixed
`supply`. Each undirected edge with conductance *c* between A and B
carries flow `(level_A - level_B) * c`. Each tick:

```
delta[i] = supply[i] + sum_over_neighbors_j((level[j] - level[i]) * c[ij])
level[i] += 0.1 * delta[i]
```

Damping factor `0.1` keeps the iteration stable. Convergence at
`1e-7` on the largest per-node change.

### Validation

- Total supply must sum to zero across all nodes (within `1e-6`).
- At least one node must have non-zero supply.

## Reserved words

`mode`, `system`, `river`, `dam`, `outlet`, `node`, `start`, `list`,
`map`, `print`, `help`.
