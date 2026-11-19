import java.util.Random;

final class Printer {
    record View(int id, PrintJob.View job, Double completionTime) { }

    private final int id;
    private final double minTime;
    private final double maxTime;
    private final Random random;
    private PrintJob currentJob;
    private Double completionTime;

    Printer(int id, double minTime, double maxTime, long seed) {
        this.id = id;
        this.minTime = minTime;
        this.maxTime = maxTime;
        this.random = new Random(seed);
    }

    int id() { return id; }
    boolean isFree() { return currentJob == null; }

    // Начинает печать и возвращает момент её окончания, длительность равномерная U(a,b).
    double start(PrintJob job, double time, Double scriptedDuration) {
        if (!isFree()) throw new IllegalStateException("Принтер занят.");
        double duration = scriptedDuration == null
                ? minTime + random.nextDouble() * (maxTime - minTime) : scriptedDuration;
        double end = time + duration;
        if (!Double.isFinite(end) || end <= time)
            throw new IllegalArgumentException("Некорректная длительность печати.");
        job.start(time);
        currentJob = job;
        completionTime = end;
        return end;
    }

    PrintJob complete(double time, String jobId) {
        if (currentJob == null || !currentJob.id().equals(jobId) || completionTime != time)
            throw new IllegalStateException("Событие не соответствует печатаемой заявке.");
        PrintJob result = currentJob;
        result.complete(time);
        currentJob = null;
        completionTime = null;
        return result;
    }

    View view() {
        return new View(id, currentJob == null ? null : currentJob.view(), completionTime);
    }
}
