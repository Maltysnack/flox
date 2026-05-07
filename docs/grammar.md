# Flox Grammar

Version 0.2 (river mode). Updated for the chain-first redesign.

```
program     -> statement* EOF
statement   -> systemDecl | nodeDecl | chain | listStmt | mapStmt | printStmt | helpStmt
systemDecl  -> "system" IDENTIFIER ";"
nodeDecl    -> riverDecl | damDecl | outletDecl
riverDecl   -> "river"   IDENTIFIER block ";"?
damDecl     -> "dam"     IDENTIFIER block ";"?
outletDecl  -> "outlet"  IDENTIFIER ( block )? ";"?
block       -> "{" ( attr ( ","? attr )* )? "}"
attr        -> IDENTIFIER ":" NUMBER "%"?
chain       -> IDENTIFIER ( "->" IDENTIFIER )* ";"
listStmt    -> "list"  IDENTIFIER ";"
mapStmt     -> "map"   IDENTIFIER ";"
printStmt   -> "print" IDENTIFIER NUMBER? ";"
helpStmt    -> "help" ";"
IDENTIFIER  -> [a-zA-Z][a-zA-Z0-9_]*
NUMBER      -> [0-9]+ ( "." [0-9]+ )?
```

## Lexical structure

Source is plain ASCII. Whitespace separates tokens but is otherwise
insignificant. Two comment forms are supported:

- `// line comment` runs to end of line.
- `/* block comment */` may span multiple lines and does not nest.

## Statements

| Form                        | Meaning |
| --------------------------- | ------- |
| `system Name;`              | Declares a named system. Subsequent declarations and chains attach to the most recently declared system. |
| `river Name { ... }`        | Declares a river node and its parameters. |
| `dam Name { ... }`          | Declares a dam node and its parameters. |
| `outlet Name;`              | Declares an outlet (zero-flow accumulator). Block is optional and must be empty. |
| `A -> B -> C;`              | Connects nodes by name. Names must be declared somewhere in the same system. Source order does not matter. |
| `list Name;`                | Lists every river, dam, and outlet in the named system. |
| `map Name;`                 | Prints an ASCII diagram of the network. |
| `print Name [rainfall];`    | Simulates the system over ten days at the given rainfall in mm (default `1.0`). |

The interpreter runs in two passes. Pass 1 collects declarations and
chains. Pass 2 resolves chains against the per-system symbol table,
then executes `list`, `map`, and `print` in source order.

## Node attributes

### River

| Attribute | Required | Meaning |
| --------- | -------- | ------- |
| `base`    | yes      | Baseline flow in litres per second with no rainfall. |
| `peak`    | yes      | Multiplier applied to rainfall (mm) to compute the peak surge. |
| `decay`   | no       | Fraction of the surge retained each subsequent day. Range `0` to `1`. Default `0.7`. |

### Dam

| Attribute     | Required | Meaning |
| ------------- | -------- | ------- |
| `capacity`    | yes      | Maximum storage in megalitres. |
| `threshold`   | yes      | Storage level at which the dam opens. Written as `80%` or `0.8`. |
| `normalRate`  | yes      | Outflow as a fraction of inflow when below threshold. |
| `openRate`    | yes      | Outflow as a fraction of inflow when at or above threshold. |

### Outlet

No attributes. An outlet accumulates everything that flows into it.
An outlet may have outgoing connections, in which case it acts as a
zero-flow passthrough.

## Block syntax

Attributes are written as `name: value`. Attributes may be separated
by commas, whitespace, or both. Single-line and multi-line forms are
both legal:

```flox
river Spring { base: 2, peak: 2.0, decay: 0.7 }

river Spring {
  base: 2
  peak: 2.0
  decay: 0.7
}
```

The semicolon after a block is optional. After a bare `outlet Name`
without a block, the semicolon is required.

## Operators

`->` is directed flow from left to right. A node may appear in any
number of chains. If a node has multiple outgoing edges, flow is
split evenly between them.

## Semantics

### River hydrograph

Given `r` millimetres of rainfall on day 0, river flow on day *n* is:

```
day 1 (rising):  base + 0.3 * peak * r
day 2 (peak):    base + peak * r
day n >= 3:      base + peak * r * decay^(n - 2)
```

With no rainfall, every day is just `base`.

### Dam outflow

If current storage is below threshold, outflow is
`inflow * normalRate`. Otherwise outflow is `inflow * openRate`.
Storage is updated each day by `inflow - outflow`.

### Outlet accumulation

An outlet sums every unit of flow that arrives at it across the
simulation. The total appears in the `print` output as the cumulative
outlet volume.

## Reserved words

`system`, `river`, `dam`, `outlet`, `list`, `map`, `print`, `help`.
