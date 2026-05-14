package flox;

import java.util.List;

abstract class Ast {

    public interface Visitor<R> {
        R visitSystemDecl(SystemDecl stmt);

        R visitModeDecl(ModeDecl stmt);

        R visitRiverDecl(River decl);

        R visitDamDecl(Dam decl);

        R visitOutletDecl(Outlet decl);

        R visitNodeDecl(NodeDecl decl);

        R visitChain(Chain chain);

        R visitStartStmt(StartStmt stmt);

        R visitDisableStmt(DisableStmt stmt);

        R visitCompareStmt(CompareStmt stmt);

        R visitUnitsDecl(UnitsDecl stmt);

        R visitMapStmt(MapStmt stmt);

        R visitListStmt(ListStmt stmt);

        R visitPrintStmt(PrintStmt stmt);

        R visitHelpStmt(HelpStmt stmt);
    }

    public abstract <R> R accept(Visitor<R> visitor);

    // system Canberra;
    public static class SystemDecl extends Ast {
        public final Token name;

        public SystemDecl(Token name) {
            this.name = name;
        }

        @Override
        public <R> R accept(Visitor<R> visitor) {
            return visitor.visitSystemDecl(this);
        }
    }

    // river Spring { base: 2, peak: 2.0, decay: 0.7 }
    // 'supply' is an optional grid-mode attribute (default 0).
    public static class River extends Ast {
        public final Token name;
        public final double baseFlow;
        public final double peakMultiplier;
        public final double decayRate;
        public final double supply;

        public River(Token name, double baseFlow, double peakMultiplier, double decayRate, double supply) {
            this.name = name;
            this.baseFlow = baseFlow;
            this.peakMultiplier = peakMultiplier;
            this.decayRate = decayRate;
            this.supply = supply;
        }

        @Override
        public <R> R accept(Visitor<R> visitor) {
            return visitor.visitRiverDecl(this);
        }
    }

    // dam googongDam { capacity: 1000, threshold: 80%, normalRate: 0.8, openRate: 1.2 }
    public static class Dam extends Ast {
        public final Token name;
        public final double capacity;
        public final double threshold; // as fraction: 0.8 = 80%
        public final double normalRate;
        public final double openRate;

        public Dam(Token name, double capacity, double threshold, double normalRate, double openRate) {
            this.name = name;
            this.capacity = capacity;
            this.threshold = threshold;
            this.normalRate = normalRate;
            this.openRate = openRate;
        }

        @Override
        public <R> R accept(Visitor<R> visitor) {
            return visitor.visitDamDecl(this);
        }
    }

    // node Name;   or   node Name { supply: -40 }
    // A generic state container. Mode-agnostic; meaningful attributes
    // depend on the system's mode (currently only 'supply' for grid mode).
    public static class NodeDecl extends Ast {
        public final Token name;
        public final double supply;

        public NodeDecl(Token name, double supply) {
            this.name = name;
            this.supply = supply;
        }

        @Override
        public <R> R accept(Visitor<R> visitor) {
            return visitor.visitNodeDecl(this);
        }
    }

    // outlet lowerMongolo;
    public static class Outlet extends Ast {
        public final Token name;

        public Outlet(Token name) {
            this.name = name;
        }

        @Override
        public <R> R accept(Visitor<R> visitor) {
            return visitor.visitOutletDecl(this);
        }
    }

    // A -> B -[0.5]-> C <- D;
    // arrows[i] connects nodes[i] and nodes[i+1]; arrows.size() == nodes.size()-1.
    public static class Chain extends Ast {
        public final List<Token> nodes;
        public final List<ChainEdge> arrows;

        public Chain(List<Token> nodes, List<ChainEdge> arrows) {
            this.nodes = nodes;
            this.arrows = arrows;
        }

        @Override
        public <R> R accept(Visitor<R> visitor) {
            return visitor.visitChain(this);
        }
    }

    // One arrow inside a chain. forward=true means left-to-right (->),
    // forward=false means right-to-left (<-). weight is null when the
    // arrow is unweighted (the default).
    public static class ChainEdge {
        public final boolean forward;
        public final Double weight;
        public final Token op; // for error reporting

        public ChainEdge(boolean forward, Double weight, Token op) {
            this.forward = forward;
            this.weight = weight;
            this.op = op;
        }
    }

    // units { supply: "cars/min", level: "queue size" }
    // File-level cosmetic labels. The simulator ignores these; the
    // output formatter uses them to qualify column headers.
    public static class UnitsDecl extends Ast {
        public final java.util.Map<String, String> labels;
        public UnitsDecl(java.util.Map<String, String> labels) { this.labels = labels; }

        @Override
        public <R> R accept(Visitor<R> visitor) {
            return visitor.visitUnitsDecl(this);
        }
    }

    // mode river;  /  mode markov;
    public static class ModeDecl extends Ast {
        public final Token mode; // identifier token: "river" or "markov"

        public ModeDecl(Token mode) {
            this.mode = mode;
        }

        @Override
        public <R> R accept(Visitor<R> visitor) {
            return visitor.visitModeDecl(this);
        }
    }

    // disable Name;            -> node disable
    // disable A -> B;          -> edge disable, direction = forward
    // disable A <- B;          -> edge disable, direction = backward (= edge B->A)
    public static class DisableStmt extends Ast {
        public final Token first;
        public final Token second;   // null for node-disable
        public final boolean forward; // ignored if second is null

        public DisableStmt(Token first, Token second, boolean forward) {
            this.first = first;
            this.second = second;
            this.forward = forward;
        }

        public boolean isNode() { return second == null; }

        @Override
        public <R> R accept(Visitor<R> visitor) {
            return visitor.visitDisableStmt(this);
        }
    }

    // One override clause inside a `compare` scenario.
    public static class OverrideSpec {
        public enum Kind { DISABLE_NODE, DISABLE_EDGE, SUPPLY }
        public final Kind kind;
        public final Token name1;
        public final Token name2;  // for DISABLE_EDGE; null otherwise
        public final boolean forward;  // for DISABLE_EDGE
        public final double value;  // for SUPPLY

        private OverrideSpec(Kind kind, Token name1, Token name2, boolean forward, double value) {
            this.kind = kind;
            this.name1 = name1;
            this.name2 = name2;
            this.forward = forward;
            this.value = value;
        }

        public static OverrideSpec disableNode(Token name) {
            return new OverrideSpec(Kind.DISABLE_NODE, name, null, true, 0);
        }
        public static OverrideSpec disableEdge(Token a, Token b, boolean forward) {
            return new OverrideSpec(Kind.DISABLE_EDGE, a, b, forward, 0);
        }
        public static OverrideSpec supply(Token name, double value) {
            return new OverrideSpec(Kind.SUPPLY, name, null, true, value);
        }
    }

    // One scenario inside a `compare`.
    public static class Scenario {
        public final String label;
        public final List<OverrideSpec> overrides;
        public Scenario(String label, List<OverrideSpec> overrides) {
            this.label = label;
            this.overrides = overrides;
        }
    }

    // compare SystemName [ticks] as "label" [with override]*, "label" ...;
    public static class CompareStmt extends Ast {
        public final Token systemName;
        public final Double ticks;
        public final List<Scenario> scenarios;

        public CompareStmt(Token systemName, Double ticks, List<Scenario> scenarios) {
            this.systemName = systemName;
            this.ticks = ticks;
            this.scenarios = scenarios;
        }

        @Override
        public <R> R accept(Visitor<R> visitor) {
            return visitor.visitCompareStmt(this);
        }
    }

    // start NodeName 1.0;
    public static class StartStmt extends Ast {
        public final Token node;
        public final double mass;

        public StartStmt(Token node, double mass) {
            this.node = node;
            this.mass = mass;
        }

        @Override
        public <R> R accept(Visitor<R> visitor) {
            return visitor.visitStartStmt(this);
        }
    }

    // map Canberra;
    public static class MapStmt extends Ast {
        public final Token systemName;

        public MapStmt(Token systemName) {
            this.systemName = systemName;
        }

        @Override
        public <R> R accept(Visitor<R> visitor) {
            return visitor.visitMapStmt(this);
        }
    }

    // list Canberra;
    public static class ListStmt extends Ast {
        public final Token systemName;

        public ListStmt(Token systemName) {
            this.systemName = systemName;
        }

        @Override
        public <R> R accept(Visitor<R> visitor) {
            return visitor.visitListStmt(this);
        }
    }

    // print Canberra;            (default: 1mm on day 1)
    // print Canberra 5;          (single 5mm event on day 1)
    // print Canberra [0, 5, 30]; (rainfall series, day-by-day)
    public static class PrintStmt extends Ast {
        public final Token systemName;
        public final double[] rainfall; // null means use default

        public PrintStmt(Token systemName, double[] rainfall) {
            this.systemName = systemName;
            this.rainfall = rainfall;
        }

        @Override
        public <R> R accept(Visitor<R> visitor) {
            return visitor.visitPrintStmt(this);
        }
    }

    // help;
    public static class HelpStmt extends Ast {
        @Override
        public <R> R accept(Visitor<R> visitor) {
            return visitor.visitHelpStmt(this);
        }
    }
}
