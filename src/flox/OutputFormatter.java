package flox;

import java.util.*;

class OutputFormatter {

    /* ─────────────────────────────── */
    /* HELP */
    /* ─────────────────────────────── */
    static void printHelp() {
        System.out.println("=== FLOX SYNTAX REFERENCE ===\n");
        System.out.println("Declarations:");
        System.out.println("  river Name { base: N, peak: N, decay: N }   (decay optional, default 0.7)");
        System.out.println("  dam   Name { capacity: N, threshold: N%, normalRate: N, openRate: N }");
        System.out.println("  outlet Name;                                (zero-flow accumulator)\n");
        System.out.println("Connections:");
        System.out.println("  A -> B -> C;                                (bare names; declared anywhere)\n");
        System.out.println("Commands:");
        System.out.println("  system Name;                  - Create a system");
        System.out.println("  list Name;                    - Show connections list");
        System.out.println("  map Name;                     - Show network diagram");
        System.out.println("  print Name;                   - Simulate with 1mm rainfall on day 1");
        System.out.println("  print Name N;                 - Simulate with N mm on day 1");
        System.out.println("  print Name [r1, r2, r3, ...]; - Per-day rainfall series in mm");
        System.out.println("  help;                         - Show this message\n");
    }

    /* ─────────────────────────────── */
    /* CONNECTION LIST */
    /* ─────────────────────────────── */
    static void printList(RiverSystem system) {
        System.out.println("\n=== RIVER SYSTEM: " + system.name + " ===\n");
        System.out.println("Connections:");
        for (Map.Entry<String, List<RiverSystem.Edge>> entry : system.connections.entrySet()) {
            String from = entry.getKey();
            for (RiverSystem.Edge e : entry.getValue()) {
                String fromLabel = system.dams.containsKey(from) ? from + " [DAM]" : from;
                String toLabel = system.dams.containsKey(e.target) ? e.target + " [DAM]" : e.target;
                System.out.println("  " + padRight(fromLabel, 22) + " →  " + toLabel);
            }
        }
        System.out.println();
    }

    /* ─────────────────────────────── */
    /* SIMPLE PATH MAP */
    /* ─────────────────────────────── */
    static void printMap(RiverSystem system) {
        System.out.println("\n=== RIVER SYSTEM MAP: " + system.name + " ===\n");

        String finalOutlet = system.getFinalOutlet();

        // Show each source and trace its path to the outlet
        for (String root : system.roots) {
            List<String> path = new ArrayList<>();
            tracePath(system, root, path, finalOutlet);
            printPath(system, path);
        }

        System.out.println();
    }

    private static void tracePath(RiverSystem system, String node, List<String> path, String finalOutlet) {
        path.add(node);

        // If this is the final outlet, we're done
        if (node.equals(finalOutlet)) {
            return;
        }

        // Continue to downstream node(s)
        List<RiverSystem.Edge> downstream = system.connections.get(node);
        if (downstream != null && !downstream.isEmpty()) {
            // Just follow the first downstream path for now
            tracePath(system, downstream.get(0).target, path, finalOutlet);
        }
    }

    private static void printPath(RiverSystem system, List<String> path) {
        for (int i = 0; i < path.size(); i++) {
            String node = path.get(i);
            boolean isDam = system.dams.containsKey(node);
            River river = system.rivers.get(node);
            boolean isOutlet = river != null && river.isTerminator();

            String indent = "  ".repeat(i);
            String label = node;
            if (isDam)
                label += " [DAM]";
            if (isOutlet)
                label += " [OUTLET]";

            if (i == 0) {
                System.out.println(indent + label + " (source)");
            } else {
                System.out.println(indent + "└──▶ " + label);
            }
        }
        System.out.println();
    }

    /* ─────────────────────────────── */
    /* FLOW TABLE */
    /* ─────────────────────────────── */
    static void printFlowTable(RiverSystem system, double[] rainfall) {
        System.out.println("\n=== FLOW SIMULATION: " + system.name + " (" + formatRainfall(rainfall) + ") ===\n");
        System.out.println("(Flow values represent water volume per day in L/s)\n");

        String finalOutlet = system.getFinalOutlet();

        // Pre-scan to compute column widths so big numbers and long node
        // names don't break the grid alignment.
        // valWidth: width of the widest value in any cell (raw flows or
        // unit-suffixed cumulative volumes).
        // locWidth: width of the widest row label.
        int valWidth = 5; // minimum, supports up to "999.9"
        int locWidth = 8; // minimum, supports "Location"
        for (River r : system.rivers.values()) {
            if (r.name.equals(finalOutlet)) continue;
            locWidth = Math.max(locWidth, r.name.length());
            for (int day = 0; day < 10; day++) {
                valWidth = Math.max(valWidth, String.format("%.1f", r.dailyFlow[day]).length());
            }
        }
        for (Dam d : system.dams.values()) {
            locWidth = Math.max(locWidth, d.name.length());
            for (int day = 0; day < 10; day++) {
                valWidth = Math.max(valWidth, String.format("%.1f", d.dailyOutflow[day]).length());
            }
        }
        if (finalOutlet != null) {
            River outlet = system.rivers.get(finalOutlet);
            if (outlet != null) {
                locWidth = Math.max(locWidth, (outlet.name + " [OUTLET]").length());
                double cumulative = 0;
                for (int day = 0; day < 10; day++) {
                    cumulative += outlet.dailyFlow[day];
                    valWidth = Math.max(valWidth, formatCumulative(cumulative).length());
                }
            }
        }

        // Each cell between pipes: 2 leading spaces + value + 1 trailing.
        int interior = valWidth + 3;
        String dashes = "-".repeat(interior);
        String locDashes = "-".repeat(locWidth);

        // Header row
        System.out.print(padRight("Location", locWidth));
        for (int day = 1; day <= 10; day++) {
            String dayStr = "Day " + padLeft(String.valueOf(day), 2);
            System.out.print("|" + padCenter(dayStr, interior));
        }
        System.out.println("|");

        // Top separator
        System.out.print(locDashes + "|");
        for (int i = 0; i < 10; i++) System.out.print(dashes + "|");
        System.out.println();

        // Rivers (skip the final outlet; printed separately as cumulative)
        for (River r : system.rivers.values()) {
            if (r.name.equals(finalOutlet)) continue;
            System.out.print(padRight(r.name, locWidth));
            for (int day = 0; day < 10; day++) {
                String val = String.format("%.1f", r.dailyFlow[day]);
                System.out.print("|  " + padLeft(val, valWidth) + " ");
            }
            System.out.println("|");
        }

        // Dams
        for (Dam d : system.dams.values()) {
            System.out.print(padRight(d.name, locWidth));
            for (int day = 0; day < 10; day++) {
                String val = String.format("%.1f", d.dailyOutflow[day]);
                System.out.print("|  " + padLeft(val, valWidth) + " ");
            }
            System.out.println("|");
        }

        // Cumulative outlet
        if (finalOutlet != null) {
            River outlet = system.rivers.get(finalOutlet);
            if (outlet != null) {
                System.out.print(locDashes + "|");
                for (int i = 0; i < 10; i++) System.out.print(dashes + "|");
                System.out.println();
                System.out.println("Cumulative Outlet (converted to actual volume):");

                System.out.print(padRight(outlet.name + " [OUTLET]", locWidth));
                double cumulative = 0;
                for (int day = 0; day < 10; day++) {
                    cumulative += outlet.dailyFlow[day];
                    String val = formatCumulative(cumulative);
                    System.out.print("|  " + padLeft(val, valWidth) + " ");
                }
                System.out.println("|");

                // Final total
                double totalLitersPerDay = cumulative * 86400;
                double totalGigaliters = totalLitersPerDay / 1_000_000_000.0;
                System.out.println();
                if (totalGigaliters >= 1.0) {
                    System.out.println("Total water accumulated at outlet: " +
                            String.format("%.2f GL (gigaliters)", totalGigaliters));
                } else if (totalGigaliters >= 0.001) {
                    double totalMegaliters = totalLitersPerDay / 1_000_000.0;
                    System.out.println("Total water accumulated at outlet: " +
                            String.format("%.2f ML (megaliters)", totalMegaliters));
                } else if (totalGigaliters >= 0.000001) {
                    double totalKiloliters = totalLitersPerDay / 1_000.0;
                    System.out.println("Total water accumulated at outlet: " +
                            String.format("%.2f KL (kiloliters)", totalKiloliters));
                } else {
                    System.out.println("Total water accumulated at outlet: " +
                            String.format("%.1f L (liters)", totalLitersPerDay));
                }
            }
        }

        System.out.println();
    }

    /* ─────────────────────────────── */
    /* MARKOV CHAIN MAP */
    /* ─────────────────────────────── */
    static void printMarkovMap(RiverSystem system) {
        System.out.println("\n=== MARKOV CHAIN: " + system.name + " ===\n");

        // States section
        List<String> states = new ArrayList<>(system.rivers.keySet());
        states.addAll(system.dams.keySet());
        System.out.println("States (" + states.size() + "):");
        System.out.println("  " + String.join(", ", states));
        System.out.println();

        // Transitions section, grouped by source. The first line of each
        // group is "<src> -[w]-> <target>". Additional outgoing edges from
        // the same source are indented with "  └[w]-> <target>".
        System.out.println("Transitions:");
        for (Map.Entry<String, List<RiverSystem.Edge>> entry : system.connections.entrySet()) {
            String src = entry.getKey();
            List<RiverSystem.Edge> outs = entry.getValue();
            for (int i = 0; i < outs.size(); i++) {
                RiverSystem.Edge e = outs.get(i);
                String w = formatWeight(e.weight);
                if (i == 0) {
                    System.out.println("  " + src + " ─[" + w + "]─▶ " + e.target);
                } else {
                    String pad = " ".repeat(src.length() + 2);
                    System.out.println(pad + "└[" + w + "]─▶ " + e.target);
                }
            }
        }
        System.out.println();

        // Sum-of-weights summary (already validated to be 1.0).
        System.out.println("Outgoing weights per state:");
        for (String src : states) {
            List<RiverSystem.Edge> outs = system.connections.get(src);
            if (outs == null || outs.isEmpty()) {
                System.out.println("  " + src + " absorbing");
                continue;
            }
            double sum = 0;
            for (RiverSystem.Edge e : outs) sum += e.weight;
            String mark = Math.abs(sum - 1.0) < 1e-6 ? "  ✓" : "  ✗";
            System.out.println("  " + src + " → " + String.format("%.3f", sum) + mark);
        }
        System.out.println();
    }

    /* ─────────────────────────────── */
    /* MARKOV DISTRIBUTION OUTPUT */
    /* ─────────────────────────────── */
    static void printMarkovDistribution(RiverSystem system) {
        String header = "\n=== MARKOV DISTRIBUTION: " + system.name + " (" + system.markovTicksRun
                + " tick" + (system.markovTicksRun == 1 ? "" : "s")
                + (system.markovConverged ? ", converged" : ", not yet converged")
                + ") ===\n";
        System.out.println(header);

        // Find the widest state name for nice column alignment.
        int locWidth = 5;
        for (String s : system.markovHistory.keySet()) {
            if (s.length() > locWidth) locWidth = s.length();
        }

        System.out.println("Final distribution:");
        double total = 0;
        for (Map.Entry<String, double[]> entry : system.markovHistory.entrySet()) {
            double v = entry.getValue()[system.markovTicksRun];
            total += v;
            System.out.println("  " + padRight(entry.getKey(), locWidth)
                    + "  " + String.format("%.4f", v));
        }
        System.out.println();
        System.out.println("Total mass: " + String.format("%.4f", total));
        System.out.println();
    }

    private static String formatWeight(double w) {
        if (w == Math.floor(w)) return String.format("%.1f", w);
        // Trim trailing zeros for cleaner display: 0.50 -> 0.5
        String s = String.format("%.3f", w);
        while (s.endsWith("0")) s = s.substring(0, s.length() - 1);
        if (s.endsWith(".")) s += "0";
        return s;
    }

    // Render the cumulative outlet volume in the most appropriate unit
    // (L, KL, ML, GL). Returns just the value+unit, no padding.
    private static String formatCumulative(double cumulativeFlowLps) {
        double litersPerDay = cumulativeFlowLps * 86400;
        double gigaliters = litersPerDay / 1_000_000_000.0;
        if (gigaliters >= 1.0) {
            return String.format("%.2fGL", gigaliters);
        } else if (gigaliters >= 0.001) {
            return String.format("%.2fML", litersPerDay / 1_000_000.0);
        } else if (gigaliters >= 0.000001) {
            return String.format("%.2fKL", litersPerDay / 1_000.0);
        } else {
            return String.format("%.1fL", litersPerDay);
        }
    }

    /* Render the rainfall input for the flow-table header.
     * Single non-zero entry: "5.0mm rainfall on day 1".
     * Anything else: "rainfall mm/day: [0, 5, 30, 10]".
     */
    private static String formatRainfall(double[] rainfall) {
        if (rainfall.length == 1) {
            return String.format("%.1fmm rainfall on day 1", rainfall[0]);
        }
        StringBuilder sb = new StringBuilder("rainfall mm/day: [");
        for (int i = 0; i < rainfall.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(formatNumber(rainfall[i]));
        }
        sb.append("]");
        return sb.toString();
    }

    private static String formatNumber(double v) {
        if (v == Math.floor(v) && !Double.isInfinite(v)) {
            return String.valueOf((long) v);
        }
        return String.format("%.1f", v);
    }

    /* ─────────────────────────────── */
    /* PADDING HELPERS */
    /* ─────────────────────────────── */
    private static String padRight(String s, int n) {
        if (s.length() >= n)
            return s.substring(0, n);
        return String.format("%-" + n + "s", s);
    }

    private static String padLeft(String s, int n) {
        if (s.length() >= n)
            return s;
        return " ".repeat(n - s.length()) + s;
    }

    private static String padCenter(String s, int n) {
        if (s.length() >= n)
            return s.substring(0, n);
        int pad = n - s.length();
        int left = pad / 2, right = pad - left;
        return " ".repeat(left) + s + " ".repeat(right);
    }
}