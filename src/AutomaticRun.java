import java.util.function.Consumer;

final class AutomaticRun {
    static final int INITIAL_SAMPLE = 100;
    static final int DEFAULT_LIMIT = 100_000;
    private static final double RELATIVE_ERROR = 0.10;
    private static final double QUANTILE = 1.643;

    record Trial(int generated, double loss, Double change, long required, boolean reached) { }
    record Result(Simulation simulation, Trial trial) { }

    // Оценивает долю отказов на растущих выборках и сверяет соседние оценки по методичке.
    static Result run(Simulation.Config config, int limit, Consumer<Trial> report) {
        if (limit < INITIAL_SAMPLE) throw new IllegalArgumentException("Лимит должен быть не меньше 100.");
        Double previous = null;
        int size = INITIAL_SAMPLE;
        while (true) {
            // Один seed сохраняет одинаковое начало последовательности поступлений между прогонами.
            Simulation sim = new Simulation(config, size);
            while (sim.step()) { }
            Statistics.Row total = sim.snapshot().statistics().get(0);
            double loss = total.loss();
            long required = loss > 0 && loss < 1
                    ? (long) Math.ceil(QUANTILE * QUANTILE * (1 - loss)
                            / (loss * RELATIVE_ERROR * RELATIVE_ERROR))
                    : Long.MAX_VALUE;
            Double change = previous != null && previous > 0
                    ? Math.abs(loss - previous) / previous : null;
            boolean reached = required <= size && change != null && change < RELATIVE_ERROR;
            Trial trial = new Trial(size, loss, change, required, reached);
            report.accept(trial);
            if (reached || size == limit) return new Result(sim, trial);

            long next = required != Long.MAX_VALUE && required > size
                    ? required : 2L * size;
            size = (int) Math.min(limit, next);
            previous = loss;
        }
    }
}
