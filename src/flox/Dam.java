package flox;

class Dam {
    // 1 L/s sustained for one day = 86400 L = 0.0864 ML.
    // Storage is in ML; flow is in L/s. Convert at the boundary.
    private static final double L_PER_S_TO_ML_PER_DAY = 0.0864;

    final String name;
    final double capacity;    // ML
    final double threshold;   // fraction, 0.8 = 80%
    final double normalRate;  // absolute outflow in ML/day, below threshold
    final double openRate;    // absolute outflow in ML/day, at or above threshold
    double currentLevel;      // ML
    double[] dailyOutflow;    // L/s, one entry per simulation day

    Dam(String name, double capacity, double threshold, double normalRate, double openRate) {
        this.name = name;
        this.capacity = capacity;
        this.threshold = threshold;
        this.normalRate = normalRate;
        this.openRate = openRate;
        this.currentLevel = capacity * 0.5; // Start at 50% full
        this.dailyOutflow = new double[10];
    }

    // Process one day of inflow and return that day's outflow.
    // Outflow is a fixed daily volume (normalRate below threshold,
    // openRate at or above), independent of inflow. A real reservoir
    // releases according to an operational schedule, not as a fraction
    // of what happens to flow in.
    //
    // inflow and outflow are returned in L/s; storage is in ML.
    double processFlow(double inflowLps) {
        double inflowVolumeMl = inflowLps * L_PER_S_TO_ML_PER_DAY;
        currentLevel += inflowVolumeMl;

        double outflowVolumeMl = (currentLevel >= capacity * threshold)
                ? openRate : normalRate;
        if (outflowVolumeMl > currentLevel) outflowVolumeMl = currentLevel;

        currentLevel -= outflowVolumeMl;
        if (currentLevel > capacity) currentLevel = capacity;

        return outflowVolumeMl / L_PER_S_TO_ML_PER_DAY;
    }

    void reset() {
        currentLevel = capacity * 0.5;
    }
}
