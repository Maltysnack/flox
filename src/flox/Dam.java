package flox;

class Dam {
    final String name;
    final double capacity;
    final double threshold; // 0.8 = 80%
    final double normalRate; // 0.8 = keep 20%, pass 80%
    final double openRate; // 1.2 = release extra
    double currentLevel; // Current water level
    double[] dailyOutflow; // Outflow for 10 days

    Dam(String name, double capacity, double threshold, double normalRate, double openRate) {
        this.name = name;
        this.capacity = capacity;
        this.threshold = threshold;
        this.normalRate = normalRate;
        this.openRate = openRate;
        this.currentLevel = capacity * 0.5; // Start at 50% full
        this.dailyOutflow = new double[10];
    }

    // Process inflow and return outflow
    double processFlow(double inflow) {
        // Add inflow to dam
        currentLevel += inflow;

        // Calculate outflow based on level
        double outflow;
        if (currentLevel >= capacity * threshold) {
            // Dam is at or above threshold - use open rate
            outflow = inflow * openRate;
        } else {
            // Dam is below threshold - use normal rate
            outflow = inflow * normalRate;
        }

        // Cap outflow to current level
        outflow = Math.min(outflow, currentLevel);

        // Remove outflow from dam
        currentLevel -= outflow;

        // Cap level at capacity
        currentLevel = Math.min(currentLevel, capacity);

        return outflow;
    }

    void reset() {
        currentLevel = capacity * 0.5;
    }
}