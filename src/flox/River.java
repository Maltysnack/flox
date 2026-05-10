package flox;

class River {
    final String name;
    final double baseFlow;       // L/s
    final double peakMultiplier; // unitless; multiplies rainfall (mm) into flow
    final double decayRate;      // fraction of surge retained each subsequent day
    final double supply;         // grid mode only: + generator, - load, 0 relay
    double[] dailyFlow;          // L/s, one entry per simulation day
    double totalAccumulated;     // L/s-days, accumulated for terminators

    River(String name, double baseFlow, double peakMultiplier, double decayRate) {
        this(name, baseFlow, peakMultiplier, decayRate, 0);
    }

    River(String name, double baseFlow, double peakMultiplier, double decayRate, double supply) {
        this.name = name;
        this.baseFlow = baseFlow;
        this.peakMultiplier = peakMultiplier;
        this.decayRate = decayRate;
        this.supply = supply;
        this.dailyFlow = new double[10];
        this.totalAccumulated = 0;
    }

    // Flow on a given simulation day, given the full rainfall series so far.
    // Each non-zero entry rainfall[d] kicks off its own hydrograph; flow is
    // the superposition of every event that has happened up to (and including)
    // day. response(0) is the rising limb (0.3 of peak), response(1) is the
    // peak itself, response(k>=1) is decay^(k-1) of the peak.
    double calculateFlow(int day, double[] rainfall) {
        double total = baseFlow;
        for (int d = 0; d <= day && d < rainfall.length; d++) {
            double r = rainfall[d];
            if (r == 0) continue;
            int k = day - d;
            double factor = (k == 0) ? 0.3 : Math.pow(decayRate, k - 1);
            total += r * peakMultiplier * factor;
        }
        return total;
    }

    boolean isTerminator() {
        return baseFlow == 0 && peakMultiplier == 0;
    }
}
