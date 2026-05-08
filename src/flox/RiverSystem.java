package flox;

import java.util.*;

class RiverSystem {
    final String name;
    Map<String, River> rivers = new LinkedHashMap<>();
    Map<String, Dam> dams = new LinkedHashMap<>();
    Map<String, List<String>> connections = new HashMap<>(); // name -> downstream names
    List<String> roots = new ArrayList<>(); // Rivers with no upstream

    RiverSystem(String name) {
        this.name = name;
    }

    void addRiver(River river) {
        rivers.put(river.name, river);
    }

    void addDam(Dam dam) {
        dams.put(dam.name, dam);
    }

    void addConnection(String from, String to) {
        connections.computeIfAbsent(from, k -> new ArrayList<>()).add(to);
    }

    void identifyRoots() {
        roots.clear(); // Clear previous roots to prevent duplicates

        Set<String> allNodes = new HashSet<>();
        allNodes.addAll(rivers.keySet());
        allNodes.addAll(dams.keySet());

        Set<String> hasUpstream = new HashSet<>();
        for (List<String> downstream : connections.values()) {
            hasUpstream.addAll(downstream);
        }

        for (String node : allNodes) {
            if (!hasUpstream.contains(node)) {
                River r = rivers.get(node);
                if (r != null && !r.isTerminator()) {
                    roots.add(node);
                }
            }
        }
    }

    // Find the final outlet (terminator with no downstream connections)
    String getFinalOutlet() {
        for (River river : rivers.values()) {
            if (river.isTerminator()) {
                // Check if this terminator has no downstream connections
                List<String> downstream = connections.get(river.name);
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

                // Get inflow from upstream
                for (Map.Entry<String, List<String>> entry : connections.entrySet()) {
                    if (entry.getValue().contains(node)) {
                        flow += flowThisDay.getOrDefault(entry.getKey(), 0.0);
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

        for (List<String> downstream : connections.values()) {
            for (String node : downstream) {
                inDegree.put(node, inDegree.getOrDefault(node, 0) + 1);
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

            List<String> downstream = connections.get(node);
            if (downstream != null) {
                for (String next : downstream) {
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
}