package flox;

import java.util.*;

class Interpreter implements Ast.Visitor<Void> {
    private final Map<String, RiverSystem> systems = new LinkedHashMap<>();

    // Per-system pending chains, kept between phases.
    private final Map<String, List<Ast.Chain>> pendingChains = new HashMap<>();

    private RiverSystem currentSystem = null;
    private Phase phase = Phase.COLLECT;
    // File-level mode. Set by `mode <name>;`. Applied to every system at
    // the end of phase 1. Default is RIVER.
    private RiverSystem.Mode fileMode = RiverSystem.Mode.RIVER;

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

            // Apply file mode to every system before resolution.
            for (RiverSystem s : systems.values()) s.mode = fileMode;

            // Resolve: wire up pending chains against each system's symbol table.
            resolveChains();

            // Per-mode validation pass.
            for (RiverSystem s : systems.values()) {
                if (s.mode == RiverSystem.Mode.MARKOV) validateMarkov(s);
                if (s.mode == RiverSystem.Mode.GRID)   validateGrid(s);
            }

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
            System.err.println("Runtime error: " + error.getMessage());
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
                    "Node '" + decl.name.lexeme + "' is declared more than once in system '"
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
                    "Node '" + decl.name.lexeme + "' is declared more than once in system '"
                            + currentSystem.name + "'.");
        }
        currentSystem.addDam(new Dam(
                decl.name.lexeme, decl.capacity, decl.threshold, decl.normalRate, decl.openRate));
        return null;
    }

    @Override
    public Void visitOutletDecl(Ast.Outlet decl) {
        if (phase != Phase.COLLECT) return null;
        requireSystem(decl.name);
        if (currentSystem.rivers.containsKey(decl.name.lexeme)
                || currentSystem.dams.containsKey(decl.name.lexeme)) {
            throw new RuntimeException(
                    "Node '" + decl.name.lexeme + "' is declared more than once in system '"
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
                    system.addConnection(from.lexeme, to.lexeme, weight);
                }
            }
        }
    }

    // Markov-mode requirements:
    //   * Every initial-mass entry refers to a declared node.
    //   * For each node with outgoing edges, the weights sum to 1.0.
    //   * At least one node has non-zero initial mass.
    private void validateMarkov(RiverSystem system) {
        for (String name : system.initialMass.keySet()) {
            if (!nodeDefined(system, name)) {
                throw new RuntimeException(
                        "start references undeclared node '" + name
                                + "' in system '" + system.name + "'.");
            }
        }
        double totalInitial = 0;
        for (double m : system.initialMass.values()) totalInitial += m;
        if (totalInitial == 0) {
            throw new RuntimeException(
                    "Markov system '" + system.name
                            + "' has no initial mass. Use 'start NodeName 1.0;' to set one.");
        }

        for (Map.Entry<String, List<RiverSystem.Edge>> entry : system.connections.entrySet()) {
            String src = entry.getKey();
            double sum = 0;
            for (RiverSystem.Edge e : entry.getValue()) sum += e.weight;
            if (Math.abs(sum - 1.0) > 1e-6) {
                throw new RuntimeException(
                        "Markov system '" + system.name + "': outgoing weights from '"
                                + src + "' sum to " + sum + ", not 1.0.");
            }
        }
    }

    // Grid-mode requirements:
    //   * Total supply across all nodes must sum to zero (otherwise the
    //     simulation never reaches a steady state, levels diverge).
    //   * At least one node has non-zero supply (otherwise nothing
    //     happens, levels stay at zero forever).
    private void validateGrid(RiverSystem system) {
        double total = 0;
        boolean hasSupply = false;
        for (River r : system.rivers.values()) {
            total += r.supply;
            if (r.supply != 0) hasSupply = true;
        }
        if (!hasSupply) {
            throw new RuntimeException(
                    "Grid system '" + system.name + "' has no supply set on any node. "
                            + "Use 'river X { base: 0, peak: 0, supply: N }' for generators (+) or loads (-).");
        }
        if (Math.abs(total) > 1e-6) {
            throw new RuntimeException(
                    "Grid system '" + system.name + "': supplies do not balance (sum = "
                            + total + "). Generators (+) and loads (-) must sum to zero.");
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
        switch (system.mode) {
            case MARKOV: OutputFormatter.printMarkovMap(system); break;
            case GRID:   OutputFormatter.printGridMap(system);   break;
            default:     OutputFormatter.printMap(system);       break;
        }
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
        if (system.mode == RiverSystem.Mode.MARKOV) {
            // The number after 'print' is the tick budget. Default 30.
            int ticks = 30;
            if (stmt.rainfall != null && stmt.rainfall.length > 0) {
                ticks = (int) Math.round(stmt.rainfall[0]);
                if (ticks < 1) ticks = 1;
            }
            system.simulateMarkov(ticks);
            OutputFormatter.printMarkovDistribution(system);
        } else if (system.mode == RiverSystem.Mode.GRID) {
            // The number after 'print' is the tick budget. Default 500.
            int ticks = 500;
            if (stmt.rainfall != null && stmt.rainfall.length > 0) {
                ticks = (int) Math.round(stmt.rainfall[0]);
                if (ticks < 1) ticks = 1;
            }
            system.simulateGrid(ticks);
            OutputFormatter.printGridState(system);
        } else {
            // Default rainfall: 1mm on day 1, dry thereafter.
            double[] rainfall = (stmt.rainfall != null) ? stmt.rainfall : new double[] { 1.0 };
            system.simulate(rainfall);
            OutputFormatter.printFlowTable(system, rainfall);
        }
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
