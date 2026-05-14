package flox;

import java.util.*;

class Interpreter implements Ast.Visitor<Void> {
    private final Map<String, RiverSystem> systems = new LinkedHashMap<>();

    // Per-system pending chains, kept between phases.
    private final Map<String, List<Ast.Chain>> pendingChains = new HashMap<>();

    // Per-system pending disable statements. Applied after chain
    // resolution; matched against the resolved node/edge set.
    private final Map<String, List<Ast.DisableStmt>> pendingDisables = new HashMap<>();

    private RiverSystem currentSystem = null;
    private Phase phase = Phase.COLLECT;
    // File-level mode. Set by `mode <name>;`. Applied to every system at
    // the end of phase 1. Default is RIVER.
    private RiverSystem.Mode fileMode = RiverSystem.Mode.RIVER;
    private final Map<String, String> fileUnits = new LinkedHashMap<>();

    // Stderr buffer. Validators and the top-level catch push lines here;
    // we flush at the end of interpret() so the byte ordering matches
    // the TypeScript port (which buffers similarly).
    private final List<String> errs = new ArrayList<>();

    private enum Phase {
        COLLECT,  // gather declarations and chains
        EXECUTE   // run list/map/print
    }

    void interpret(List<Ast> statements) {
        try {
            // Phase 1: collect declarations and chains.
            phase = Phase.COLLECT;
            currentSystem = null;
            for (Ast stmt : statements) {
                if (stmt != null) execute(stmt);
            }

            // Apply file mode + units to every system before resolution.
            for (RiverSystem s : systems.values()) {
                s.mode = fileMode;
                s.unitLabels.putAll(fileUnits);
            }

            // Resolve: wire up pending chains against each system's symbol table.
            resolveChains();
            applyDisables();

            // Per-mode validation pass. Validators check whether the
            // system has disabled elements and soften their requirements
            // accordingly (a user explicitly disabling things is
            // modelling a scenario and the model may be unbalanced).
            for (RiverSystem s : systems.values()) s.mode.validate(s, errs);

            // Phase 2: identify roots once per system, then execute actions.
            for (RiverSystem s : systems.values()) {
                s.identifyRoots();
            }
            phase = Phase.EXECUTE;
            currentSystem = null;
            for (Ast stmt : statements) {
                if (stmt != null) execute(stmt);
            }
        } catch (RuntimeException error) {
            errs.add("Runtime error: " + error.getMessage() + "\n");
        } finally {
            // Flush the buffered stderr after stdout is done, so both
            // backends produce identical interleaving when captured with
            // 2>&1 by the test runner.
            for (String e : errs) System.err.print(e);
            errs.clear();
        }
    }

    private void execute(Ast stmt) {
        stmt.accept(this);
    }

    @Override
    public Void visitSystemDecl(Ast.SystemDecl stmt) {
        String name = stmt.name.lexeme;
        if (phase == Phase.COLLECT) {
            currentSystem = systems.computeIfAbsent(name, RiverSystem::new);
            pendingChains.computeIfAbsent(name, k -> new ArrayList<>());
            pendingDisables.computeIfAbsent(name, k -> new ArrayList<>());
        } else {
            currentSystem = systems.get(name);
        }
        return null;
    }

    @Override
    public Void visitRiverDecl(Ast.River decl) {
        if (phase != Phase.COLLECT) return null;
        requireSystem(decl.name);
        if (currentSystem.rivers.containsKey(decl.name.lexeme)
                || currentSystem.dams.containsKey(decl.name.lexeme)) {
            throw new RuntimeException(
                    "Node '" + decl.name.lexeme + "' (line " + decl.name.line + ") is declared more than once in system '"
                            + currentSystem.name + "'.");
        }
        currentSystem.addRiver(new River(
                decl.name.lexeme, decl.baseFlow, decl.peakMultiplier, decl.decayRate, decl.supply));
        return null;
    }

    @Override
    public Void visitDamDecl(Ast.Dam decl) {
        if (phase != Phase.COLLECT) return null;
        requireSystem(decl.name);
        if (currentSystem.rivers.containsKey(decl.name.lexeme)
                || currentSystem.dams.containsKey(decl.name.lexeme)) {
            throw new RuntimeException(
                    "Node '" + decl.name.lexeme + "' (line " + decl.name.line + ") is declared more than once in system '"
                            + currentSystem.name + "'.");
        }
        currentSystem.addDam(new Dam(
                decl.name.lexeme, decl.capacity, decl.threshold, decl.normalRate, decl.openRate));
        return null;
    }

    @Override
    public Void visitNodeDecl(Ast.NodeDecl decl) {
        if (phase != Phase.COLLECT) return null;
        requireSystem(decl.name);
        if (currentSystem.rivers.containsKey(decl.name.lexeme)
                || currentSystem.dams.containsKey(decl.name.lexeme)) {
            throw new RuntimeException(
                    "Node '" + decl.name.lexeme + "' (line " + decl.name.line + ") is declared more than once in system '"
                            + currentSystem.name + "'.");
        }
        // A 'node' desugars to a zero-flow river carrying the given supply.
        // Markov mode treats it as a plain state; grid mode reads supply;
        // river mode treats it as an outlet-like passthrough.
        currentSystem.addRiver(new River(decl.name.lexeme, 0, 0, 0.7, decl.supply));
        return null;
    }

    @Override
    public Void visitOutletDecl(Ast.Outlet decl) {
        if (phase != Phase.COLLECT) return null;
        requireSystem(decl.name);
        if (currentSystem.rivers.containsKey(decl.name.lexeme)
                || currentSystem.dams.containsKey(decl.name.lexeme)) {
            throw new RuntimeException(
                    "Node '" + decl.name.lexeme + "' (line " + decl.name.line + ") is declared more than once in system '"
                            + currentSystem.name + "'.");
        }
        // Outlets are stored as zero-flow rivers; the runtime detects them via
        // baseFlow == 0 && peakMultiplier == 0 (River.isTerminator()).
        currentSystem.addRiver(new River(decl.name.lexeme, 0, 0, 0.7));
        return null;
    }

    @Override
    public Void visitChain(Ast.Chain chain) {
        if (phase != Phase.COLLECT) return null;
        requireSystem(chain.nodes.isEmpty() ? null : chain.nodes.get(0));
        pendingChains.get(currentSystem.name).add(chain);
        return null;
    }

    @Override
    public Void visitModeDecl(Ast.ModeDecl stmt) {
        if (phase != Phase.COLLECT) return null;
        String name = stmt.mode.lexeme;
        if (name.equals("river")) {
            fileMode = RiverSystem.Mode.RIVER;
        } else if (name.equals("markov")) {
            fileMode = RiverSystem.Mode.MARKOV;
        } else if (name.equals("grid")) {
            fileMode = RiverSystem.Mode.GRID;
        } else {
            throw new RuntimeException(
                    "Unknown mode '" + name + "' (line " + stmt.mode.line
                            + "). Known modes: river, markov, grid.");
        }
        return null;
    }

    @Override
    public Void visitCompareStmt(Ast.CompareStmt stmt) {
        if (phase != Phase.EXECUTE) return null;
        RiverSystem original = lookupSystem(stmt.systemName);
        if (original.mode != RiverSystem.Mode.GRID) {
            throw new RuntimeException(
                    "'compare' is only supported in grid mode for now (system '"
                            + original.name + "' is " + original.mode.name().toLowerCase() + " mode).");
        }
        double[] arg = (stmt.ticks != null)
                ? new double[] { stmt.ticks }
                : original.mode.defaultArg();

        List<RiverSystem> runs = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (Ast.Scenario sc : stmt.scenarios) {
            RiverSystem clone = scenarioClone(original, sc.overrides);
            clone.mode.validate(clone, errs);
            clone.identifyRoots();
            clone.mode.run(clone, arg);
            runs.add(clone);
            labels.add(sc.label);
        }
        OutputFormatter.printGridComparison(original, runs, labels);
        return null;
    }

    // Build a fresh RiverSystem from an existing one with per-scenario
    // overrides applied. Rivers/dams are recreated (rivers may have an
    // overridden supply); edges are recreated with possibly-flipped
    // enabled flags. The original system is not mutated.
    private RiverSystem scenarioClone(RiverSystem src, List<Ast.OverrideSpec> overrides) {
        Map<String, Double> supplyOverrides = new HashMap<>();
        Set<String> extraDisableNodes = new HashSet<>();
        Set<String> extraDisableEdges = new HashSet<>(); // "from|to"
        for (Ast.OverrideSpec o : overrides) {
            switch (o.kind) {
                case DISABLE_NODE:
                    extraDisableNodes.add(o.name1.lexeme); break;
                case DISABLE_EDGE: {
                    String from = o.forward ? o.name1.lexeme : o.name2.lexeme;
                    String to   = o.forward ? o.name2.lexeme : o.name1.lexeme;
                    extraDisableEdges.add(from + "|" + to);
                    break;
                }
                case SUPPLY:
                    supplyOverrides.put(o.name1.lexeme, o.value); break;
            }
        }

        RiverSystem clone = new RiverSystem(src.name);
        clone.mode = src.mode;
        clone.scenarioRun = true;

        for (River r : src.rivers.values()) {
            double supply = supplyOverrides.getOrDefault(r.name, r.supply);
            clone.addRiver(new River(r.name, r.baseFlow, r.peakMultiplier, r.decayRate, supply));
        }
        for (Dam d : src.dams.values()) clone.addDam(d);

        // Combined disabled-nodes set: those in the base system + extras.
        clone.disabledNodes.addAll(src.disabledNodes);
        clone.disabledNodes.addAll(extraDisableNodes);

        for (Map.Entry<String, List<RiverSystem.Edge>> entry : src.connections.entrySet()) {
            String from = entry.getKey();
            for (RiverSystem.Edge e : entry.getValue()) {
                clone.addConnection(from, e.target, e.weight, e.op);
                List<RiverSystem.Edge> outs = clone.connections.get(from);
                RiverSystem.Edge added = outs.get(outs.size() - 1);
                added.enabled = e.enabled;  // carry forward base disables
                if (extraDisableEdges.contains(from + "|" + e.target)) added.enabled = false;
                if (clone.disabledNodes.contains(from) || clone.disabledNodes.contains(e.target)) {
                    added.enabled = false;
                }
            }
        }

        // Carry forward markov initial-mass.
        clone.initialMass.putAll(src.initialMass);
        return clone;
    }

    @Override
    public Void visitDisableStmt(Ast.DisableStmt stmt) {
        if (phase != Phase.COLLECT) return null;
        requireSystem(stmt.first);
        pendingDisables.get(currentSystem.name).add(stmt);
        return null;
    }

    @Override
    public Void visitUnitsDecl(Ast.UnitsDecl stmt) {
        if (phase != Phase.COLLECT) return null;
        fileUnits.putAll(stmt.labels);
        return null;
    }

    @Override
    public Void visitStartStmt(Ast.StartStmt stmt) {
        if (phase != Phase.COLLECT) return null;
        requireSystem(stmt.node);
        // start statements are valid in any mode but only used by markov.
        // We accumulate so multiple `start A 0.4; start A 0.6;` are equivalent
        // to one `start A 1.0;`.
        currentSystem.initialMass.merge(stmt.node.lexeme, stmt.mass, Double::sum);
        return null;
    }

    private void resolveChains() {
        for (Map.Entry<String, List<Ast.Chain>> entry : pendingChains.entrySet()) {
            RiverSystem system = systems.get(entry.getKey());
            for (Ast.Chain chain : entry.getValue()) {
                // Validate every name in the chain is declared.
                for (Token name : chain.nodes) {
                    if (!nodeDefined(system, name.lexeme)) {
                        throw new RuntimeException(
                                "Chain references undeclared node '" + name.lexeme
                                        + "' (line " + name.line + ") in system '"
                                        + system.name + "'.");
                    }
                }
                // Wire up edges. Arrow direction decides (from, to); arrow
                // weight decides edge weight (default 1.0 if absent).
                for (int i = 0; i + 1 < chain.nodes.size(); i++) {
                    Ast.ChainEdge arrow = chain.arrows.get(i);
                    Token a = chain.nodes.get(i);
                    Token b = chain.nodes.get(i + 1);
                    Token from = arrow.forward ? a : b;
                    Token to   = arrow.forward ? b : a;
                    double weight = arrow.weight != null ? arrow.weight : 1.0;
                    system.addConnection(from.lexeme, to.lexeme, weight, arrow.op);
                }
            }
        }
    }

    // Apply pending disable statements after chains are resolved. Marks
    // edges as enabled=false; marks nodes by adding to disabledNodes
    // and disabling all incident edges in both directions.
    private void applyDisables() {
        for (Map.Entry<String, List<Ast.DisableStmt>> entry : pendingDisables.entrySet()) {
            RiverSystem system = systems.get(entry.getKey());
            if (system == null) continue;
            for (Ast.DisableStmt stmt : entry.getValue()) {
                if (stmt.isNode()) {
                    String name = stmt.first.lexeme;
                    if (!nodeDefined(system, name)) {
                        throw new RuntimeException(
                                "disable references undeclared node '" + name
                                        + "' (line " + stmt.first.line + ") in system '"
                                        + system.name + "'.");
                    }
                    system.disabledNodes.add(name);
                    // Disable all outgoing edges from this node.
                    List<RiverSystem.Edge> outs = system.connections.get(name);
                    if (outs != null) for (RiverSystem.Edge e : outs) e.enabled = false;
                    // Disable all incoming edges targeting this node.
                    for (List<RiverSystem.Edge> es : system.connections.values()) {
                        for (RiverSystem.Edge e : es) {
                            if (e.target.equals(name)) e.enabled = false;
                        }
                    }
                } else {
                    String a = stmt.first.lexeme;
                    String b = stmt.second.lexeme;
                    String from = stmt.forward ? a : b;
                    String to   = stmt.forward ? b : a;
                    if (!nodeDefined(system, from) || !nodeDefined(system, to)) {
                        throw new RuntimeException(
                                "disable references undeclared node in edge '"
                                        + from + " -> " + to + "' (line "
                                        + stmt.first.line + ") in system '"
                                        + system.name + "'.");
                    }
                    List<RiverSystem.Edge> outs = system.connections.get(from);
                    boolean found = false;
                    if (outs != null) {
                        for (RiverSystem.Edge e : outs) {
                            if (e.target.equals(to)) {
                                e.enabled = false;
                                found = true;
                            }
                        }
                    }
                    if (!found) {
                        throw new RuntimeException(
                                "disable references edge '" + from + " -> " + to
                                        + "' (line " + stmt.first.line + ") which is not declared in system '"
                                        + system.name + "'.");
                    }
                }
            }
        }
    }

    private boolean nodeDefined(RiverSystem system, String name) {
        return system.rivers.containsKey(name) || system.dams.containsKey(name);
    }

    private void requireSystem(Token at) {
        if (currentSystem == null) {
            String where = at != null ? " (line " + at.line + ")" : "";
            throw new RuntimeException("No system declared. Use 'system Name;' first." + where);
        }
    }

    @Override
    public Void visitMapStmt(Ast.MapStmt stmt) {
        if (phase != Phase.EXECUTE) return null;
        RiverSystem system = lookupSystem(stmt.systemName);
        system.mode.printMap(system);
        return null;
    }

    @Override
    public Void visitListStmt(Ast.ListStmt stmt) {
        if (phase != Phase.EXECUTE) return null;
        RiverSystem system = lookupSystem(stmt.systemName);
        OutputFormatter.printList(system);
        return null;
    }

    @Override
    public Void visitPrintStmt(Ast.PrintStmt stmt) {
        if (phase != Phase.EXECUTE) return null;
        RiverSystem system = lookupSystem(stmt.systemName);
        double[] arg = (stmt.rainfall != null) ? stmt.rainfall : system.mode.defaultArg();
        system.mode.run(system, arg);
        system.mode.printResult(system, arg);
        return null;
    }

    @Override
    public Void visitHelpStmt(Ast.HelpStmt stmt) {
        if (phase != Phase.EXECUTE) return null;
        OutputFormatter.printHelp();
        return null;
    }

    private RiverSystem lookupSystem(Token nameToken) {
        RiverSystem system = systems.get(nameToken.lexeme);
        if (system == null) {
            throw new RuntimeException(
                    "System '" + nameToken.lexeme + "' not found (line " + nameToken.line + ").");
        }
        return system;
    }
}
