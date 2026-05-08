package flox;

class Dam {
    // 1 L/s sustained for one day = 86400 L = 0.0864 ML.
    // Storage is in ML; flow is in L/s. Convert at the boundary.
    private static final double L_PER_S_TO_ML_PER_DAY = 0.0864;

    final String name;
    final double capacity;    // ML
    final double threshold;   // fraction, 0.8 = 80%
    final double normalRate;  // fraction of inflow passed through below threshold
    final double openRate;    // fraction of inflow passed through above threshold
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
    // inflow and outflow are L/s; storage is ML. Conversion is applied
    // when mutating storage so the threshold is meaningful.
    double processFlow(double inflowLps) {
        // Convert today's inflow into a daily volume and add to storage.
        double inflowVolumeMl = inflowLps * L_PER_S_TO_ML_PER_DAY;
        currentLevel += inflowVolumeMl;

        // Decide outflow rate based on current storage vs threshold.
        double outflowLps;
        if (currentLevel >= capacity * threshold) {
            outflowLps = inflowLps * openRate;
        } else {
            outflowLps = inflowLps * normalRate;
        }

        // Cap outflow to what's actually in storage.
        double outflowVolumeMl = outflowLps * L_PER_S_TO_ML_PER_DAY;
        if (outflowVolumeMl > currentLevel) {
            outflowVolumeMl = currentLevel;
            outflowLps = outflowVolumeMl / L_PER_S_TO_ML_PER_DAY;
        }

        // Subtract outflow from storage; cap at capacity (overflow is lost).
        currentLevel -= outflowVolumeMl;
        if (currentLevel > capacity) currentLevel = capacity;

        return outflowLps;
    }

    void reset() {
        currentLevel = capacity * 0.5;
    }
}
