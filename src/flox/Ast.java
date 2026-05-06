package flox;

import java.util.List;

abstract class Ast {

    public interface Visitor<R> {
        R visitRiverExpr(River expr);

        R visitDamExpr(Dam expr);

        R visitBinaryExpr(Binary expr);

        R visitSystemDeclStmt(SystemDecl stmt);

        R visitMapStmt(MapStmt stmt);

        R visitListStmt(ListStmt stmt);

        R visitPrintStmt(PrintStmt stmt);

        R visitHelpStmt(HelpStmt stmt);
    }

    public abstract <R> R accept(Visitor<R> visitor);

    // River: Googong[2, 2.0, 0.7]
    public static class River extends Ast {
        public final Token name;
        public final double baseFlow;
        public final double peakMultiplier;
        public final double decayRate; // optional, defaults to 0.7

        public River(Token name, double baseFlow, double peakMultiplier, double decayRate) {
            this.name = name;
            this.baseFlow = baseFlow;
            this.peakMultiplier = peakMultiplier;
            this.decayRate = decayRate;
        }

        @Override
        public <R> R accept(Visitor<R> visitor) {
            return visitor.visitRiverExpr(this);
        }
    }

    // Dam: googongDam{1000, 0.8, 1.2} or googongDam{1000, 70%, 0.8, 1.2}
    public static class Dam extends Ast {
        public final Token name;
        public final double capacity;
        public final double threshold; // as decimal: 0.8 = 80%
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
            return visitor.visitDamExpr(this);
        }
    }

    // Binary: river1 -> river2
    public static class Binary extends Ast {
        public final Ast left;
        public final Token operator;
        public final Ast right;

        public Binary(Ast left, Token operator, Ast right) {
            this.left = left;
            this.operator = operator;
            this.right = right;
        }

        @Override
        public <R> R accept(Visitor<R> visitor) {
            return visitor.visitBinaryExpr(this);
        }
    }

    // SystemDecl: system Canberra;
    public static class SystemDecl extends Ast {
        public final Token name;

        public SystemDecl(Token name) {
            this.name = name;
        }

        @Override
        public <R> R accept(Visitor<R> visitor) {
            return visitor.visitSystemDeclStmt(this);
        }
    }

    // MapStmt: map Canberra;
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

    // ListStmt: list Canberra;
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

    // PrintStmt: print Canberra; or print Canberra 5;
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

    // HelpStmt: help;
    public static class HelpStmt extends Ast {
        @Override
        public <R> R accept(Visitor<R> visitor) {
            return visitor.visitHelpStmt(this);
        }
    }
}