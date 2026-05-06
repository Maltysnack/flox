package flox;

import java.util.*;

class OutputFormatter {

    /* ─────────────────────────────── */
    /* HELP */
    /* ─────────────────────────────── */
    static void printHelp() {
        System.out.println("=== FLOX SYNTAX REFERENCE ===\n");
        System.out.println("Rivers:  RiverName[baseFlow, peakMultiplier, decayRate]");
        System.out.println("         RiverName[baseFlow, peakMultiplier]  (decay defaults to 0.7)");
        System.out.println("         RiverName[]  (terminator)\n");
        System.out.println("Dams:    nameDam{capacity, normalRate, openRate}  (threshold defaults to 80%)");
        System.out.println("         nameDam{capacity, threshold%, normalRate, openRate}\n");
        System.out.println("Commands:");
        System.out.println("  system Name;      - Create river system");
        System.out.println("  list Name;        - Show connections list");
        System.out.println("  map Name;         - Show network diagram");
        System.out.println("  print Name;       - Simulate with 1mm rain (default)");
        System.out.println("  print Name N;     - Simulate with N mm rain");
        System.out.println("  help;             - Show this message\n");
    }

    /* ─────────────────────────────── */
    /* CONNECTION LIST */
    /* ─────────────────────────────── */
    static void printList(RiverSystem system) {
        System.out.println("\n=== RIVER SYSTEM: " + system.name + " ===\n");
        System.out.println("Connections:");
        for (Map.Entry<String, List<String>> entry : system.connections.entrySet()) {
            String from = entry.getKey();
            for (String to : entry.getValue()) {
                String fromLabel = system.dams.containsKey(from) ? from + " [DAM]" : from;
                String toLabel = system.dams.containsKey(to) ? to + " [DAM]" : to;
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
        List<String> downstream = system.connections.get(node);
        if (downstream != null && !downstream.isEmpty()) {
            // Just follow the first downstream path for now
            tracePath(system, downstream.get(0), path, finalOutlet);
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
    static void printFlowTable(RiverSystem system, double rainfall) {
        System.out.println("\n=== FLOW SIMULATION: " + system.name + " (" + rainfall + "mm rainfall) ===\n");
        System.out.println("(Flow values represent water volume per day in L/s)\n");

        System.out.print(padRight("Location", 20));
        for (int day = 1; day <= 10; day++)
            System.out.print("| Day " + padLeft(String.valueOf(day), 2) + " ");
        System.out.println("|");

        System.out.print("--------------------|");
        for (int i = 0; i < 10; i++)
            System.out.print("--------|");
        System.out.println();

        // Find the final outlet
        String finalOutlet = system.getFinalOutlet();

        // Print ALL rivers except the final outlet (including intermediate terminators)
        for (River r : system.rivers.values()) {
            if (r.name.equals(finalOutlet))
                continue; // Skip final outlet for now

            System.out.print(padRight(r.name, 20));
            for (int day = 0; day < 10; day++)
                System.out.print(String.format("|  %5.1f ", r.dailyFlow[day]));
            System.out.println("|");
        }

        // Print dams
        for (Dam d : system.dams.values()) {
            System.out.print(padRight(d.name, 20));
            for (int day = 0; day < 10; day++)
                System.out.print(String.format("|  %5.1f ", d.dailyOutflow[day]));
            System.out.println("|");
        }

        // Print final outlet with cumulative totals converted to GL
        if (finalOutlet != null) {
            River outlet = system.rivers.get(finalOutlet);
            if (outlet != null) {
                System.out.print("--------------------|");
                for (int i = 0; i < 10; i++)
                    System.out.print("--------|");
                System.out.println();
                System.out.println("Cumulative Outlet (converted to actual volume):");

                System.out.print(padRight(outlet.name + " [OUTLET]", 20));
                double cumulative = 0;
                for (int day = 0; day < 10; day++) {
                    cumulative += outlet.dailyFlow[day];
                    // Convert L/s to liters per day, then to gigaliters
                    double litersPerDay = cumulative * 86400; // seconds per day
                    double gigaliters = litersPerDay / 1_000_000_000.0;

                    if (gigaliters >= 1.0) {
                        System.out.print(String.format("| %5.2fGL", gigaliters));
                    } else if (gigaliters >= 0.001) {
                        double megaliters = litersPerDay / 1_000_000.0;
                        System.out.print(String.format("| %5.2fML", megaliters));
                    } else if (gigaliters >= 0.000001) {
                        double kiloliters = litersPerDay / 1_000.0;
                        System.out.print(String.format("| %5.2fKL", kiloliters));
                    } else {
                        System.out.print(String.format("| %5.1fL ", litersPerDay));
                    }
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