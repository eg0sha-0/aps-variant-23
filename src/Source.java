import java.util.Random;

final class Source {
    private final int id;
    private final double rate;
    private final Random random;
    private int serial;

    Source(int id, double rate, long seed) {
        this.id = id;
        this.rate = rate;
        this.random = new Random(seed);
    }

    int id() { return id; }

    // Создаёт заявку с уникальным номером внутри источника.
    PrintJob generate(double time) {
        return new PrintJob(id + "." + (++serial), id, time);
    }

    // Пуассоновскому потоку соответствуют экспоненциальные интервалы между заявками.
    double nextArrival(double time) {
        double u;
        do { u = random.nextDouble(); } while (u == 0);
        double next = time - Math.log(u) / rate;
        if (!Double.isFinite(next) || next <= time)
            throw new IllegalArgumentException("Слишком большая или малая интенсивность для расчёта времени.");
        return next;
    }
}
