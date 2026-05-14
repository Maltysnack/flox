package flox;

import java.util.*;

class RiverSystem {
    // Each mode is its own strategy: validate the system before
    // simulation, run a tick budget against it, render the topology
    // (map), render the result of a simulation (print).
    enum Mode {
        RIVER {
            void validate(RiverSystem s, List<String> errs) { /* no-op */ }

            void run(RiverSystem s, double[] arg) { s.simulate(arg); }

            double[] defaultArg() { return new double[] { 1.0 }; }

            void printMap(RiverSystem s) { OutputFormatter.printMap(s); }

            void printResult(RiverSystem s, double[] arg) {
                OutputFormatter.printFlowTable(s, arg);
            }
        },

        MARKOV {
            void validate(RiverSystem s, List<String> errs) {
                for (String name : s.initialMass.keySet()) {
                    if (!s.rivers.containsKey(name) && !s.dams.containsKey(name)) {
                        throw new RuntimeException(
                                "start references undeclared node '" + name
                                        + "' in system '" + s.name + "'.");
                    }
                }
                double totalInitial = 0;
                for (Map.Entry<String, Double> entry : s.initialMass.entrySet()) {
                    if (s.disabledNodes.contains(entry.getKey())) continue;
                    totalInitial += entry.getValue();
                }
                if (totalInitial == 0) {
                    throw new RuntimeException(
                            "Markov system '" + s.name
                                    + "' has no initial mass on any active node. Use 'start NodeName 1.0;' to set one.");
                }
                // Per-source sum-to-1, considering only enabled edges and
                // skipping disabled source nodes entirely. If any element
                // is disabled we soften this to a warning (the user is
                // exploring a scenario; an unbalanced chain is OK).
                boolean softened = s.scenarioRun
                        || !s.disabledNodes.isEmpty()
                        || s.connections.values().stream().flatMap(List::stream).anyMatch(e -> !e.enabled);
                for (Map.Entry<String, List<Edge>> entry : s.connections.entrySet()) {
                    if (s.disabledNodes.contains(entry.getKey())) continue;
                    double sum = 0;
                    boolean anyEnabled = false;
                    StringBuilder lines = new StringBuilder();
                    for (Edge e : entry.getValue()) {
                        if (!e.enabled) continue;
                        anyEnabled = true;
                        sum += e.weight;
                        if (e.op != null) {
                            if (lines.length() > 0) lines.append(", ");
                            lines.append("line ").append(e.op.line);
                        }
                    }
                    if (!anyEnabled) continue;  // becomes absorbing after disabling
                    if (Math.abs(sum - 1.0) > 1e-6) {
                        String where = lines.length() > 0 ? " (" + lines + ")" : "";
                        String msg = "Markov system '" + s.name + "': outgoing weights from '"
                                + entry.getKey() + "'" + where + " sum to " + sum + ", not 1.0.";
                        if (softened) {
                            errs.add("Note (scenario): " + msg + "\n");
                        } else {
                            throw new RuntimeException(msg);
                        }
                    }
                }
            }

            void run(RiverSystem s, double[] arg) {
                int ticks = (arg.length > 0)
                        ? Math.max(1, (int) Math.round(arg[0]))
                        : 30;
                s.simulateMarkov(ticks);
            }

            double[] defaultArg() { return new double[] { 30 }; }

            void printMap(RiverSystem s) { OutputFormatter.printMarkovMap(s); }

            void printResult(RiverSystem s, double[] arg) {
                OutputFormatter.printMarkovDistribution(s);
            }
        },

        GRID {
            void validate(RiverSystem s, List<String> errs) {
                double total = 0;
                boolean hasSupply = false;
                for (River r : s.rivers.values()) {
                    if (s.disabledNodes.contains(r.name)) continue;
                    total += r.supply;
                    if (r.supply != 0) hasSupply = true;
                }
                if (!hasSupply) {
                    throw new RuntimeException(
                            "Grid system '" + s.name + "' has no supply set on any active node. "
                                    + "Use 'node X { supply: N }' for generators (+) or loads (-).");
                }
                boolean softened = s.scenarioRun
                        || !s.disabledNodes.isEmpty()
                        || s.connections.values().stream().flatMap(List::stream).anyMatch(e -> !e.enabled);
                if (Math.abs(total) > 1e-6) {
                    String msg = "Grid system '" + s.name + "': supplies do not balance (sum = "
                            + total + " after disabling). Generators (+) and loads (-) must sum to zero.";
                    if (softened) {
                        errs.add("Note (scenario): " + msg + "\n");
                    } else {
                        throw new RuntimeException(msg);
                    }
                }
            }

            void run(RiverSystem s, double[] arg) {
                int ticks = (arg.length > 0)
                        ? Math.max(1, (int) Math.round(arg[0]))
                        : 500;
                s.simulateGrid(ticks);
            }

            double[] defaultArg() { return new double[] { 500 }; }

            void printMap(RiverSystem s) { OutputFormatter.printGridMap(s); }

            void printResult(RiverSystem s, double[] arg) {
                OutputFormatter.printGridState(s);
            }
        };

        abstract void validate(RiverSystem s, List<String> errs);
        abstract void run(RiverSystem s, double[] arg);
        abstract double[] defaultArg();
        abstract void printMap(RiverSystem s);
        abstract void printResult(RiverSystem s, double[] arg);
    }

    static class Edge {
        final String target;
        final double weight;
        final Token op; // arrow token that declared this edge (for diagnostics)
        boolean enabled = true;

        Edge(String target, double weight, Token op) {
            this.target = target;
            this.weight = weight;
            this.op = op;
        }
    }

    final String name;
    Mode mode = Mode.RIVER;
    Map<String, River> rivers = new LinkedHashMap<>();
    Map<String, Dam> dams = new LinkedHashMap<>();
    Map<String, List<Edge>> connections = new LinkedHashMap<>(); // name -> outgoing edges, insertion-ordered
    List<String> roots = new ArrayList<>(); // Rivers with no upstream

    // Scenario-disabling. Nodes whose names appear here are skipped by
    // every simulator (their incident edges effectively don't exist),
    // but they remain in rivers/dams so the visualisation can render
    // them dimmed.
    Set<String> disabledNodes = new LinkedHashSet<>();
    // Marker set by `compare` so per-mode validators know they're looking
    // at a scenario clone and should soften their requirements.
    boolean scenarioRun = false;
    // Optional cosmetic labels for output (e.g. supply: "cars/min").
    Map<String, String> unitLabels = new LinkedHashMap<>();

    // Markov-mode state.
    Map<String, Double> initialMass = new LinkedHashMap<>();
    Map<String, double[]> markovHistory = new LinkedHashMap<>(); // node -> mass per tick
    int markovTicksRun = 0;
    boolean markovConverged = false;

    // Grid-mode state.
    Map<String, Double> gridLevel = new LinkedHashMap<>();
    Map<String, Double> gridFlow = new LinkedHashMap<>(); // edge "A->B" -> flow A to B
    int gridTicksRun = 0;
    boolean gridConverged = false;

    RiverSystem(String name) {
        this.name = name;
    }

    void addRiver(River river) {
        rivers.put(river.name, river);
    }

    void addDam(Dam dam) {
        dams.put(dam.name, dam);
    }

    void addConnection(String from, String to, double weight, Token op) {
        connections.computeIfAbsent(from, k -> new ArrayList<>()).add(new Edge(to, weight, op));
    }

    void identifyRoots() {
        roots.clear();
        Set<String> hasUpstream = new HashSet<>();
        for (List<Edge> downstream : connections.values()) {
            for (Edge e : downstream) {
                if (!e.enabled) continue;
                hasUpstream.add(e.target);
            }
        }
        for (River r : rivers.values()) {
            if (disabledNodes.contains(r.name)) continue;
            if (!hasUpstream.contains(r.name) && !r.isTerminator()) {
                roots.add(r.name);
            }
        }
    }

    // Find the final outlet (terminator with no downstream connections)
    String getFinalOutlet() {
        for (River river : rivers.values()) {
            if (river.isTerminator()) {
                List<Edge> downstream = connections.get(river.name);
                if (downstream == null || downstream.isEmpty()) {
                    return river.name;
                }
            }
        }
        return null;
    }

    // Simulate flow over 10 days given a daily rainfall series (mm).
    // The series may be shorter than 10 entries; missing days are treated
    // as zero rainfall.
    void simulate(double[] rainfall) {
        // Reset dams and terminators
        for (Dam dam : dams.values()) {
            dam.reset();
        }
        for (River river : rivers.values()) {
            river.totalAccumulated = 0;
        }

        // For each day
        for (int day = 0; day < 10; day++) {
            Map<String, Double> flowThisDay = new HashMap<>();

            // Calculate flow for each node
            for (String node : getTopologicalOrder()) {
                if (disabledNodes.contains(node)) continue;
                double flow = 0;

                // Get inflow from upstream, weighted per-edge.
                for (Map.Entry<String, List<Edge>> entry : connections.entrySet()) {
                    for (Edge e : entry.getValue()) {
                        if (!e.enabled) continue;
                        if (e.target.equals(node)) {
                            flow += flowThisDay.getOrDefault(entry.getKey(), 0.0) * e.weight;
                        }
                    }
                }

                // Add own contribution from rainfall (all rivers, not just roots)
                River river = rivers.get(node);
                if (river != null && !river.isTerminator()) {
                    flow += river.calculateFlow(day, rainfall);
                }

                // Process through dam if it's a dam
                Dam dam = dams.get(node);
                if (dam != null) {
                    flow = dam.processFlow(flow);
                    dam.dailyOutflow[day] = flow;
                } else if (river != null) {
                    river.dailyFlow[day] = flow;
                    // Accumulate total for terminators
                    if (river.isTerminator()) {
                        river.totalAccumulated += flow;
                    }
                }

                flowThisDay.put(node, flow);
            }
        }
    }

    // Get nodes in topological order (upstream first) using Kahn's algorithm
    private List<String> getTopologicalOrder() {
        List<String> order = new ArrayList<>();

        // Count incoming edges for each node
        Map<String, Integer> inDegree = new HashMap<>();
        Set<String> allNodes = new HashSet<>();
        allNodes.addAll(rivers.keySet());
        allNodes.addAll(dams.keySet());

        for (String node : allNodes) {
            inDegree.put(node, 0);
        }

        for (List<Edge> downstream : connections.values()) {
            for (Edge e : downstream) {
                if (!e.enabled) continue;
                inDegree.put(e.target, inDegree.getOrDefault(e.target, 0) + 1);
            }
        }

        // Queue of nodes with no incoming edges
        Queue<String> queue = new LinkedList<>();
        for (Map.Entry<String, Integer> entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) {
                queue.add(entry.getKey());
            }
        }

        // Process nodes in topological order
        while (!queue.isEmpty()) {
            String node = queue.poll();
            order.add(node);

            List<Edge> downstream = connections.get(node);
            if (downstream != null) {
                for (Edge e : downstream) {
                    if (!e.enabled) continue;
                    String next = e.target;
                    int newDegree = inDegree.get(next) - 1;
                    inDegree.put(next, newDegree);
                    if (newDegree == 0) {
                        queue.add(next);
                    }
                }
            }
        }

        return order;
    }

    // Markov-mode simulation. Distributes mass across nodes per-tick by
    // multiplying current state vector by the (row-stochastic) transition
    // matrix induced by the edge weights. Halts at maxTicks or when the
    // state stops changing within EPSILON.
    void simulateMarkov(int maxTicks) {
        final double EPSILON = 1e-9;
        final List<String> nodes = new ArrayList<>();
        for (String n : rivers.keySet()) if (!disabledNodes.contains(n)) nodes.add(n);
        for (String n : dams.keySet())   if (!disabledNodes.contains(n)) nodes.add(n);

        // Initial state vector
        Map<String, Double> mass = new LinkedHashMap<>();
        for (String n : nodes) mass.put(n, initialMass.getOrDefault(n, 0.0));

        // Record per-tick history (tick 0 = initial)
        markovHistory.clear();
        for (String n : nodes) {
            double[] hist = new double[maxTicks + 1];
            hist[0] = mass.get(n);
            markovHistory.put(n, hist);
        }
        markovTicksRun = 0;
        markovConverged = false;

        for (int t = 1; t <= maxTicks; t++) {
            Map<String, Double> next = new LinkedHashMap<>();
            for (String n : nodes) next.put(n, 0.0);

            for (String src : nodes) {
                List<Edge> outs = connections.get(src);
                if (outs == null || outs.isEmpty()) {
                    // Dangling node: keep its mass put.
                    next.put(src, next.get(src) + mass.get(src));
                    continue;
                }
                for (Edge e : outs) {
                    if (!e.enabled) continue;
                    if (!next.containsKey(e.target)) continue;
                    next.put(e.target, next.get(e.target) + mass.get(src) * e.weight);
                }
            }

            double maxDiff = 0;
            for (String n : nodes) {
                maxDiff = Math.max(maxDiff, Math.abs(next.get(n) - mass.get(n)));
                markovHistory.get(n)[t] = next.get(n);
            }
            mass = next;
            markovTicksRun = t;

            if (maxDiff < EPSILON) {
                markovConverged = true;
                break;
            }
        }
    }

    // Grid-mode simulation. Each undirected edge between A and B with
    // conductance c carries flow (level_A - level_B) * c. Each node has a
    // 'supply' (positive = injects, negative = draws). The level integrates
    // (supply + net_inflow) over time with damping factor dt.
    //
    // For supplies that sum to zero, the system converges to the unique
    // (up to constant) solution of the graph Laplacian system. Damping
    // dt = 0.1 keeps iteration stable for moderately-conditioned graphs.
    void simulateGrid(int maxTicks) {
        final double EPSILON = 1e-7;

        final List<String> nodes = new ArrayList<>();
        for (String n : rivers.keySet()) if (!disabledNodes.contains(n)) nodes.add(n);
        for (String n : dams.keySet())   if (!disabledNodes.contains(n)) nodes.add(n);

        Map<String, Double> edgeC = new LinkedHashMap<>();
        Map<String, String[]> edgeEnds = new LinkedHashMap<>();
        for (Map.Entry<String, List<Edge>> entry : connections.entrySet()) {
            for (Edge e : entry.getValue()) {
                if (!e.enabled) continue;
                String a = entry.getKey();
                String b = e.target;
                String key = a.compareTo(b) <= 0 ? a + "|" + b : b + "|" + a;
                edgeC.merge(key, e.weight, Double::sum);
                edgeEnds.putIfAbsent(key, new String[] { a, b });
            }
        }

        // Adaptive damping. The Jacobi iteration is stable when
        //   dt < 2 / lambda_max(L)
        // where lambda_max of the graph Laplacian is at most twice the
        // maximum row-sum (sum of conductances incident on any node).
        // So dt = 0.5 / maxNodeSum is safely under the stability limit.
        // For small conductances we cap dt at 0.1 to avoid being slower
        // than necessary.
        Map<String, Double> nodeSum = new HashMap<>();
        for (Map.Entry<String, String[]> entry : edgeEnds.entrySet()) {
            String[] ends = entry.getValue();
            double c = edgeC.get(entry.getKey());
            nodeSum.merge(ends[0], c, Double::sum);
            nodeSum.merge(ends[1], c, Double::sum);
        }
        double maxNodeSum = 0;
        for (double v : nodeSum.values()) maxNodeSum = Math.max(maxNodeSum, v);
        final double DT = (maxNodeSum > 0) ? Math.min(0.1, 0.5 / maxNodeSum) : 0.1;

        gridLevel.clear();
        for (String n : nodes) gridLevel.put(n, 0.0);
        gridTicksRun = 0;
        gridConverged = false;

        for (int t = 1; t <= maxTicks; t++) {
            Map<String, Double> delta = new LinkedHashMap<>();
            for (String n : nodes) delta.put(n, 0.0);

            // Each node receives its own supply per tick.
            for (String n : nodes) {
                River r = rivers.get(n);
                double supply = (r != null) ? r.supply : 0;
                delta.put(n, delta.get(n) + supply);
            }

            // Edge flows. Positive flow means a->b direction.
            for (Map.Entry<String, String[]> entry : edgeEnds.entrySet()) {
                String[] ends = entry.getValue();
                double c = edgeC.get(entry.getKey());
                double flow = (gridLevel.get(ends[0]) - gridLevel.get(ends[1])) * c;
                delta.put(ends[0], delta.get(ends[0]) - flow);
                delta.put(ends[1], delta.get(ends[1]) + flow);
            }

            double maxDiff = 0;
            for (String n : nodes) {
                double d = DT * delta.get(n);
                maxDiff = Math.max(maxDiff, Math.abs(d));
                gridLevel.put(n, gridLevel.get(n) + d);
            }
            gridTicksRun = t;

            if (maxDiff < EPSILON) {
                gridConverged = true;
                break;
            }
        }

        // Compute final flows on each edge (positive = a -> b direction).
        gridFlow.clear();
        for (Map.Entry<String, String[]> entry : edgeEnds.entrySet()) {
            String[] ends = entry.getValue();
            double c = edgeC.get(entry.getKey());
            double flow = (gridLevel.get(ends[0]) - gridLevel.get(ends[1])) * c;
            gridFlow.put(ends[0] + "->" + ends[1], flow);
        }
    }
}