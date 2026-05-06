package flox;

class River {
    final String name;
    final double baseFlow;
    final double peakMultiplier;
    final double decayRate;
    double[] dailyFlow; // Flow for 10 days
    double totalAccumulated; // Total water received (for terminators)

    River(String name, double baseFlow, double peakMultiplier, double decayRate) {
        this.name = name;
        this.baseFlow = baseFlow;
        this.peakMultiplier = peakMultiplier;
        this.decayRate = decayRate;
        this.dailyFlow = new double[10];
        this.totalAccumulated = 0;
    }

    // Calculate flow for a given day and rainfall
    double calculateFlow(int day, double rainfall) {
        if (day == 0) {
            // Day 0: 30% of peak (initial runoff)
            return baseFlow + (rainfall * peakMultiplier * 0.3);
        } else {
            // Day 1+: exponential decay
            double decayFactor = Math.pow(decayRate, day - 1);
            return baseFlow + (rainfall * peakMultiplier * decayFactor);
        }
    }

    boolean isTerminator() {
        return baseFlow == 0 && peakMultiplier == 0;
    }
}