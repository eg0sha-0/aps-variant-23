import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Random;

final class Simulation {
    enum Kind { COMPLETED, ARRIVAL }

    record Bounds(double min, double max) {
        Bounds {
            if (!Double.isFinite(min) || !Double.isFinite(max) || min <= 0 || max <= min)
                throw new IllegalArgumentException("Время печати: 0 < a < b, значения конечные.");
        }
    }

    record Config(List<Double> rates, List<Bounds> service, int capacity, double horizon, long seed) {
        Config {
            rates = List.copyOf(rates);
            service = List.copyOf(service);
            if (rates.isEmpty() || rates.stream().anyMatch(r -> !Double.isFinite(r) || r <= 0))
                throw new IllegalArgumentException("Нужен хотя бы один источник с интенсивностью > 0.");
            if (service.isEmpty() || capacity < 1 || !Double.isFinite(horizon) || horizon <= 0)
                throw new IllegalArgumentException("Нужны принтер, буфер и конечный горизонт > 0.");
        }

        static Config defaults(long seed) {
            return new Config(List.of(0.8, 0.6, 0.4),
                    List.of(new Bounds(2, 5), new Bounds(2, 5)), 3, 12, seed);
        }
    }

    record Arrival(double time, int sourceId) { }
    record EventView(double time, Kind kind, int ownerId, String jobId) { }
    record JournalEntry(int step, double time, String action) { }
    record View(int step, double time, List<PrintJob.View> queue, List<Printer.View> printers,
                SelectionDispatcher.View batch, List<EventView> calendar,
                List<Statistics.Row> statistics, List<Double> busyTimes) { }

    private record Event(double time, Kind kind, long sequence, int ownerId, String jobId)
            implements Comparable<Event> {
        @Override public int compareTo(Event other) {
            int c = Double.compare(time, other.time);
            if (c == 0) c = kind.compareTo(other.kind);
            return c != 0 ? c : Long.compare(sequence, other.sequence);
        }
    }

    private final Config config;
    private final List<Source> sources = new ArrayList<>();
    private final List<Printer> printers = new ArrayList<>();
    private final Buffer buffer;
    private final Statistics statistics;
    private final AdmissionDispatcher admission;
    private final SelectionDispatcher selection;
    private final PriorityQueue<Event> calendar = new PriorityQueue<>();
    private final List<JournalEntry> journal = new ArrayList<>();
    private final boolean scripted;
    private final int arrivalLimit;
    private int arrivalsProcessed;
    private double lastArrival;
    private double time;
    private int step;
    private long sequence;
    private List<String> lastActions = List.of();
    private EventView lastEvent;

    Simulation(Config config) { this(config, null, Map.of(), 0); }

    Simulation(Config config, int arrivalLimit) {
        this(config, null, Map.of(), arrivalLimit);
        if (arrivalLimit < 1) throw new IllegalArgumentException("Нужен положительный объём выборки.");
    }

    Simulation(Config config, List<Arrival> arrivals, Map<String, Double> durations) {
        this(config, arrivals, durations, 0);
    }

    private Simulation(Config config, List<Arrival> arrivals, Map<String, Double> durations,
                       int arrivalLimit) {
        this.config = config;
        this.arrivalLimit = arrivalLimit;
        scripted = arrivals != null;
        Random random = new Random(config.seed());
        for (int i = 0; i < config.rates().size(); i++)
            sources.add(new Source(i + 1, config.rates().get(i), random.nextLong()));
        for (int i = 0; i < config.service().size(); i++) {
            Bounds b = config.service().get(i);
            printers.add(new Printer(i + 1, b.min(), b.max(), random.nextLong()));
        }
        buffer = new Buffer(config.capacity());
        statistics = new Statistics(sources.size(), printers.size());
        admission = new AdmissionDispatcher(buffer, statistics);
        selection = new SelectionDispatcher(buffer, printers, durations);
        if (arrivals == null) {
            for (Source source : sources) scheduleArrival(source);
        } else {
            for (Arrival a : arrivals) {
                if (!Double.isFinite(a.time()) || a.time() < 0 || a.time() > config.horizon()
                        || a.sourceId() < 1 || a.sourceId() > sources.size())
                    throw new IllegalArgumentException("Некорректное поступление сценария.");
                schedule(a.time(), Kind.ARRIVAL, a.sourceId(), null);
            }
        }
    }

    private void schedule(double at, Kind kind, int ownerId, String jobId) {
        if (!Double.isFinite(at) || at < time) throw new IllegalArgumentException("Некорректное событие.");
        calendar.add(new Event(at, kind, sequence++, ownerId, jobId));
    }

    private void scheduleArrival(Source source) {
        double next = source.nextArrival(time);
        if (arrivalLimit > 0 || next <= config.horizon())
            schedule(next, Kind.ARRIVAL, source.id(), null);
    }

    // Один шаг обрабатывает ближайшее событие и его последствия в тот же момент времени.
    boolean step() {
        if (calendar.isEmpty()) {
            if (!buffer.snapshot().isEmpty() || !selection.snapshot().members().isEmpty())
                throw new IllegalStateException("Потеряно событие обслуживания.");
            return false;
        }
        Event e = calendar.remove();
        time = e.time();
        step++;
        lastEvent = new EventView(time, e.kind(), e.ownerId(), e.jobId());
        List<String> actions = new ArrayList<>();
        boolean dispatch;
        if (e.kind() == Kind.ARRIVAL) {
            Source source = sources.get(e.ownerId() - 1);
            lastArrival = time;
            arrivalsProcessed++;
            dispatch = admission.admit(source.generate(time), actions);
            if (arrivalLimit > 0 && arrivalsProcessed == arrivalLimit) {
                calendar.removeIf(event -> event.kind() == Kind.ARRIVAL);
            } else if (!scripted) {
                scheduleArrival(source);
            }
        } else {
            Printer printer = printers.get(e.ownerId() - 1);
            PrintJob job = printer.complete(time, e.jobId());
            statistics.onCompleted(job, printer.id());
            actions.add("П" + printer.id() + " завершил " + job.id() + " и освободился.");
            selection.onCompleted(job, actions);
            dispatch = true;
        }
        if (dispatch) {
            for (SelectionDispatcher.Completion completion : selection.dispatch(time, actions))
                schedule(completion.time(), Kind.COMPLETED, completion.printerId(), completion.jobId());
        }
        if (arrivalLimit == 0) {
            lastActions = List.copyOf(actions);
            for (String action : actions) journal.add(new JournalEntry(step, time, action));
        }
        return true;
    }

    View snapshot() {
        List<EventView> events = calendar.stream().sorted()
                .map(e -> new EventView(e.time(), e.kind(), e.ownerId(), e.jobId())).toList();
        return new View(step, time, buffer.snapshot(), printers.stream().map(Printer::view).toList(),
                selection.snapshot(), events, statistics.snapshot(), statistics.busyTimes());
    }

    Config config() { return config; }
    boolean hasEvents() { return !calendar.isEmpty(); }
    EventView lastEvent() { return lastEvent; }
    List<String> lastActions() { return lastActions; }
    List<JournalEntry> journal() { return List.copyOf(journal); }
    double observationTime() { return arrivalLimit > 0 ? time : Math.max(config.horizon(), time); }
    double generationEnd() { return arrivalLimit > 0 ? lastArrival : config.horizon(); }
}
