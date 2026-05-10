package flox;

import java.util.*;

class RiverSystem {
    enum Mode { RIVER, MARKOV }

    static class Edge {
        final String target;
        final double weight;

        Edge(String target, double weight) {
            this.target = target;
            this.weight = weight;
        }
    }

    final String name;
    Mode mode = Mode.RIVER;
    Map<String, River> rivers = new LinkedHashMap<>();
    Map<String, Dam> dams = new LinkedHashMap<>();
    Map<String, List<Edge>> connections = new LinkedHashMap<>(); // name -> outgoing edges, insertion-ordered
    List<String> roots = new ArrayList<>(); // Rivers with no upstream

    // Markov-mode state.
    Map<String, Double> initialMass = new LinkedHashMap<>();
    Map<String, double[]> markovHistory = new LinkedHashMap<>(); // node -> mass per tick
    int markovTicksRun = 0;
    boolean markovConverged = false;

    RiverSystem(String name) {
        this.name = name;
    }

    void addRiver(River river) {
        rivers.put(river.name, river);
    }

    void addDam(Dam dam) {
        dams.put(dam.name, dam);
    }

    void addConnection(String from, String to, double weight) {
        connections.computeIfAbsent(from, k -> new ArrayList<>()).add(new Edge(to, weight));
    }

    void identifyRoots() {
        roots.clear();
        Set<String> hasUpstream = new HashSet<>();
        for (List<Edge> downstream : connections.values()) {
            for (Edge e : downstream) hasUpstream.add(e.target);
        }
        // Iterate rivers in declaration (insertion) order. Dams and outlets
        // are excluded as roots: dams always have an upstream, outlets are
        // terminators.
        for (River r : rivers.values()) {
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
                double flow = 0;

                // Get inflow from upstream, weighted per-edge.
                for (Map.Entry<String, List<Edge>> entry : connections.entrySet()) {
                    for (Edge e : entry.getValue()) {
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
        final List<String> nodes = new ArrayList<>(rivers.keySet());
        nodes.addAll(dams.keySet());

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
}