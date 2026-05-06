package flox;

import java.util.*;

class Interpreter implements Ast.Visitor<Void> {
    private Map<String, RiverSystem> systems = new HashMap<>();
    private RiverSystem currentSystem = null;

    void interpret(List<Ast> statements) {
        try {
            for (Ast statement : statements) {
                if (statement != null) {
                    execute(statement);
                }
            }
        } catch (RuntimeException error) {
            System.err.println("Runtime error: " + error.getMessage());
        }
    }

    private void execute(Ast stmt) {
        stmt.accept(this);
    }

    @Override
    public Void visitSystemDeclStmt(Ast.SystemDecl stmt) {
        String name = stmt.name.lexeme;
        currentSystem = new RiverSystem(name);
        systems.put(name, currentSystem);
        return null;
    }

    @Override
    public Void visitRiverExpr(Ast.River expr) {
        if (currentSystem == null) {
            throw new RuntimeException("No system declared. Use 'system Name;' first.");
        }

        River river = new River(
                expr.name.lexeme,
                expr.baseFlow,
                expr.peakMultiplier,
                expr.decayRate);
        currentSystem.addRiver(river);
        return null;
    }

    @Override
    public Void visitDamExpr(Ast.Dam expr) {
        if (currentSystem == null) {
            throw new RuntimeException("No system declared. Use 'system Name;' first.");
        }

        Dam dam = new Dam(
                expr.name.lexeme,
                expr.capacity,
                expr.threshold,
                expr.normalRate,
                expr.openRate);
        currentSystem.addDam(dam);
        return null;
    }

    @Override
    public Void visitBinaryExpr(Ast.Binary expr) {
        if (currentSystem == null) {
            throw new RuntimeException("No system declared. Use 'system Name;' first.");
        }

        // Process left side
        expr.left.accept(this);
        String leftName = getNodeName(expr.left);

        // Process right side
        expr.right.accept(this);
        String rightName = getNodeName(expr.right);

        // Add connection
        if (leftName != null && rightName != null) {
            currentSystem.addConnection(leftName, rightName);
        }

        return null;
    }

    private String getNodeName(Ast expr) {
        if (expr instanceof Ast.River) {
            return ((Ast.River) expr).name.lexeme;
        } else if (expr instanceof Ast.Dam) {
            return ((Ast.Dam) expr).name.lexeme;
        } else if (expr instanceof Ast.Binary) {
            // For chained expressions, return the rightmost name
            return getNodeName(((Ast.Binary) expr).right);
        }
        return null;
    }

    @Override
    public Void visitMapStmt(Ast.MapStmt stmt) {
        String name = stmt.systemName.lexeme;
        RiverSystem system = systems.get(name);

        if (system == null) {
            throw new RuntimeException("System '" + name + "' not found.");
        }

        system.identifyRoots();
        OutputFormatter.printMap(system);
        return null;
    }

    @Override
    public Void visitListStmt(Ast.ListStmt stmt) {
        String name = stmt.systemName.lexeme;
        RiverSystem system = systems.get(name);

        if (system == null) {
            throw new RuntimeException("System '" + name + "' not found.");
        }

        system.identifyRoots();
        OutputFormatter.printList(system);
        return null;
    }

    @Override
    public Void visitPrintStmt(Ast.PrintStmt stmt) {
        String name = stmt.systemName.lexeme;
        RiverSystem system = systems.get(name);

        if (system == null) {
            throw new RuntimeException("System '" + name + "' not found.");
        }

        double rainfall = (stmt.rainfall != null) ? stmt.rainfall : 1.0;

        system.identifyRoots();
        system.simulate(rainfall);
        OutputFormatter.printFlowTable(system, rainfall);
        return null;
    }

    @Override
    public Void visitHelpStmt(Ast.HelpStmt stmt) {
        OutputFormatter.printHelp();
        return null;
    }
}