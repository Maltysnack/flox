package flox;

import java.util.List;

abstract class Ast {

    public interface Visitor<R> {
        R visitSystemDecl(SystemDecl stmt);

        R visitRiverDecl(River decl);

        R visitDamDecl(Dam decl);

        R visitOutletDecl(Outlet decl);

        R visitChain(Chain chain);

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
    public static class River extends Ast {
        public final Token name;
        public final double baseFlow;
        public final double peakMultiplier;
        public final double decayRate;

        public River(Token name, double baseFlow, double peakMultiplier, double decayRate) {
            this.name = name;
            this.baseFlow = baseFlow;
            this.peakMultiplier = peakMultiplier;
            this.decayRate = decayRate;
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

    // A -> B -> C;  (pure references)
    public static class Chain extends Ast {
        public final List<Token> nodes;

        public Chain(List<Token> nodes) {
            this.nodes = nodes;
        }

        @Override
        public <R> R accept(Visitor<R> visitor) {
            return visitor.visitChain(this);
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

    // print Canberra; or print Canberra 5;
    public static class PrintStmt extends Ast {
        public final Token systemName;
        public final Double rainfall; // null means default (1mm)

        public PrintStmt(Token systemName, Double rainfall) {
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
