import java.util.ArrayList;
import java.util.List;

final class Statistics {
    record Row(int sourceId, long generated, long rejected, long completed, Double loss,
               Double waiting, Double service, Double total, Double waitingVariance,
               Double serviceVariance) { }

    private static final class Moments {
        private long count;
        private double mean;
        private double m2;

        void add(double value) {
            count++;
            double delta = value - mean;
            mean += delta / count;
            m2 += delta * (value - mean);
        }

        Double average() { return count == 0 ? null : mean; }
        Double variance() { return count < 2 ? null : m2 / (count - 1); }
    }

    private final long[] generated;
    private final long[] rejected;
    private final long[] completed;
    private final Moments[] waits;
    private final Moments[] services;
    private final Moments[] totals;
    private final double[] busyTimes;

    Statistics(int sourceCount, int printerCount) {
        generated = new long[sourceCount + 1];
        rejected = new long[sourceCount + 1];
        completed = new long[sourceCount + 1];
        waits = new Moments[sourceCount + 1];
        services = new Moments[sourceCount + 1];
        totals = new Moments[sourceCount + 1];
        for (int i = 0; i <= sourceCount; i++) {
            waits[i] = new Moments();
            services[i] = new Moments();
            totals[i] = new Moments();
        }
        busyTimes = new double[printerCount];
    }

    void onGenerated(PrintJob job) { generated[job.sourceId()]++; generated[0]++; }
    void onRejected(PrintJob job) { rejected[job.sourceId()]++; rejected[0]++; }

    // Учитывает время только полностью обслуженной заявки, отказам не назначает нулевое время.
    void onCompleted(PrintJob job, int printerId) {
        PrintJob.View v = job.view();
        if (v.state() != PrintJob.State.COMPLETED)
            throw new IllegalStateException("Заявка ещё не завершена.");
        double service = v.completedAt() - v.startedAt();
        for (int i : new int[] {0, job.sourceId()}) {
            completed[i]++;
            waits[i].add(v.startedAt() - v.arrivedAt());
            services[i].add(service);
            totals[i].add(v.completedAt() - v.arrivedAt());
        }
        busyTimes[printerId - 1] += service;
    }

    // Строка 0 содержит общую статистику, остальные строки соответствуют источникам.
    List<Row> snapshot() {
        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < generated.length; i++)
            rows.add(new Row(i, generated[i], rejected[i], completed[i],
                    generated[i] == 0 ? null : (double) rejected[i] / generated[i],
                    waits[i].average(), services[i].average(), totals[i].average(),
                    waits[i].variance(), services[i].variance()));
        return List.copyOf(rows);
    }

    List<Double> busyTimes() {
        List<Double> result = new ArrayList<>();
        for (double time : busyTimes) result.add(time);
        return List.copyOf(result);
    }
}
