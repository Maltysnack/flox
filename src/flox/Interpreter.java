package flox;

import java.util.*;

class Interpreter implements Ast.Visitor<Void> {
    private final Map<String, RiverSystem> systems = new LinkedHashMap<>();

    // Per-system pending chain edges, kept between phases.
    // Each entry is one chain: a list of (token) names in source order.
    private final Map<String, List<List<Token>>> pendingChains = new HashMap<>();

    private RiverSystem currentSystem = null;
    private Phase phase = Phase.COLLECT;

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

            // Resolve: wire up pending chains against each system's symbol table.
            resolveChains();

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
                decl.name.lexeme, decl.baseFlow, decl.peakMultiplier, decl.decayRate));
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
        pendingChains.get(currentSystem.name).add(chain.nodes);
        return null;
    }

    private void resolveChains() {
        for (Map.Entry<String, List<List<Token>>> entry : pendingChains.entrySet()) {
            RiverSystem system = systems.get(entry.getKey());
            for (List<Token> chain : entry.getValue()) {
                // Validate every name in the chain is declared.
                for (Token name : chain) {
                    if (!nodeDefined(system, name.lexeme)) {
                        throw new RuntimeException(
                                "Chain references undeclared node '" + name.lexeme
                                        + "' (line " + name.line + ") in system '"
                                        + system.name + "'.");
                    }
                }
                // Wire up pairs.
                for (int i = 0; i + 1 < chain.size(); i++) {
                    system.addConnection(chain.get(i).lexeme, chain.get(i + 1).lexeme);
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
        OutputFormatter.printMap(system);
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
        double rainfall = (stmt.rainfall != null) ? stmt.rainfall : 1.0;
        system.simulate(rainfall);
        OutputFormatter.printFlowTable(system, rainfall);
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
