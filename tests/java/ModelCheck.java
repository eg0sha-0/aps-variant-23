import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

public final class ModelCheck {
    private static long checkedEvents;

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static Simulation.Config config(int capacity, int printers, double horizon) {
        return new Simulation.Config(List.of(0.8, 0.6, 0.4),
                java.util.Collections.nCopies(printers, new Simulation.Bounds(2, 5)),
                capacity, horizon, 23);
    }

    private static List<String> jobs(Simulation sim) {
        return sim.snapshot().printers().stream().map(p -> p.job() == null ? "-" : p.job().id()).toList();
    }

    private static void fixedScenario() {
        Simulation sim = new Simulation(config(3, 2, 5),
                List.of(new Simulation.Arrival(0, 1), new Simulation.Arrival(1, 2),
                        new Simulation.Arrival(2, 2), new Simulation.Arrival(3, 3),
                        new Simulation.Arrival(3.5, 1), new Simulation.Arrival(5, 2)),
                Map.of("1.1", 4.0, "2.1", 2.0, "2.2", 5.0, "2.3", 2.0, "3.1", 2.0));
        while (sim.step()) {
            double time = sim.snapshot().time();
            if (time == 0) check(jobs(sim).equals(List.of("1.1", "-")), "Happy Flow");
            if (time == 3.5) {
                check(sim.snapshot().statistics().get(1).rejected() == 1, "Отказ новой заявке");
                check(sim.snapshot().queue().stream().map(PrintJob.View::id).toList()
                        .equals(List.of("2.1", "2.2", "3.1")), "Очередь не должна измениться");
            }
            if (time == 4) check(jobs(sim).equals(List.of("2.1", "2.2")), "Пакет кадров");
            if (time == 5) check(sim.snapshot().batch().members().equals(List.of("2.1", "2.2")), "Пакет не расширяется");
            if (time == 6) check(jobs(sim).equals(List.of("-", "2.2")), "Простой на границе пакета");
            if (time == 9) check(jobs(sim).equals(List.of("2.3", "-")), "Приоритет источника");
        }
        Statistics.Row total = sim.snapshot().statistics().get(0);
        check(total.generated() == 6 && total.rejected() == 1 && total.completed() == 5, "Баланс");
        check(sim.snapshot().busyTimes().equals(List.of(10.0, 5.0)), "Занятость принтеров");
        check(Math.abs(total.waiting() - 3.4) < 1e-9, "Среднее ожидание");
        check(Math.abs(total.waitingVariance() - 8.8) < 1e-9, "Дисперсия ожидания");
    }

    private static void equalTimes() {
        Simulation sim = new Simulation(config(1, 1, 2),
                List.of(new Simulation.Arrival(0, 1), new Simulation.Arrival(1, 2),
                        new Simulation.Arrival(2, 3)),
                Map.of("1.1", 2.0, "2.1", 2.0, "3.1", 2.0));
        sim.step(); sim.step(); sim.step();
        check(sim.lastEvent().kind() == Simulation.Kind.COMPLETED, "Завершение раньше поступления");
        sim.step();
        check(sim.lastEvent().kind() == Simulation.Kind.ARRIVAL, "Поступление при равном времени");
        while (sim.step()) { }
        check(sim.snapshot().statistics().get(0).rejected() == 0, "Освобождённое место");
        check(sim.observationTime() == 6, "Дообслуживание после горизонта");
    }

    private static void pendingFifo() {
        Simulation sim = new Simulation(config(4, 2, 4),
                List.of(new Simulation.Arrival(0, 2), new Simulation.Arrival(1, 1),
                        new Simulation.Arrival(2, 1), new Simulation.Arrival(3, 1),
                        new Simulation.Arrival(4, 1)),
                Map.of("2.1", 5.0, "1.1", 2.0, "1.2", 2.0, "1.3", 2.0, "1.4", 2.0));
        for (int i = 0; i < 6; i++) sim.step();
        check(jobs(sim).equals(List.of("1.1", "1.2")), "FIFO внутри пакета");
        check(sim.snapshot().batch().pending().equals(List.of("1.3", "1.4")), "Ожидающие остаются в буфере");
        sim.step();
        check(jobs(sim).equals(List.of("1.3", "1.2")), "Выбор следующей заявки FIFO");
    }

    private static List<String> stress(Simulation.Config cfg) {
        Simulation sim = new Simulation(cfg);
        List<String> trace = new ArrayList<>();
        double previousTime = 0;
        List<String> previousMembers = List.of();
        while (sim.step()) {
            checkedEvents++;
            Simulation.View v = sim.snapshot();
            trace.add(sim.lastEvent() + " " + sim.lastActions());
            check(v.time() >= previousTime, "Время идёт назад");
            previousTime = v.time();
            check(v.queue().size() <= cfg.capacity(), "Переполнение");
            Set<String> printing = new HashSet<>();
            Set<Integer> printingSources = new HashSet<>();
            for (Printer.View p : v.printers()) {
                if (p.job() == null) continue;
                printing.add(p.job().id());
                printingSources.add(p.job().sourceId());
                if (p.job().startedAt() == v.time()) {
                    for (Printer.View other : v.printers())
                        check(other.id() >= p.id() || other.job() != null, "Д2П1");
                    double duration = p.completionTime() - p.job().startedAt();
                    Simulation.Bounds b = cfg.service().get(p.id() - 1);
                    check(duration >= b.min() - 1e-9 && duration <= b.max() + 1e-9, "ПЗ2");
                }
            }
            check(printingSources.size() <= 1, "Принтеры обслуживают разные пакеты");
            check(printing.equals(v.batch().inFlight()), "inFlight");
            Set<String> pending = new HashSet<>(v.batch().pending());
            check(java.util.Collections.disjoint(pending, printing), "pending пересекается с inFlight");
            check(v.batch().members().containsAll(pending) && v.batch().members().containsAll(printing), "Состав пакета");
            check(v.queue().stream().map(PrintJob.View::id).toList().containsAll(pending), "Заявка пакета потеряна");
            if (!v.batch().members().isEmpty() && !v.batch().members().equals(previousMembers)) {
                List<PrintJob.View> candidates = new ArrayList<>(v.queue());
                for (Printer.View p : v.printers()) if (p.job() != null) candidates.add(p.job());
                int min = candidates.stream().mapToInt(PrintJob.View::sourceId).min().orElseThrow();
                check(v.batch().members().get(0).startsWith(min + "."), "Приоритет источника");
                check(candidates.stream().filter(j -> j.sourceId() == min).map(PrintJob.View::id)
                        .collect(java.util.stream.Collectors.toSet()).equals(new HashSet<>(v.batch().members())), "Нефиксированный состав");
            }
            previousMembers = v.batch().members();
            for (Statistics.Row row : v.statistics()) {
                if (row.sourceId() == 0) continue;
                long queued = v.queue().stream().filter(j -> j.sourceId() == row.sourceId()).count();
                long busy = v.printers().stream().filter(p -> p.job() != null && p.job().sourceId() == row.sourceId()).count();
                check(row.generated() == row.rejected() + row.completed() + queued + busy, "Баланс на шаге");
            }
            long events = v.calendar().stream().filter(e -> e.kind() == Simulation.Kind.COMPLETED).count();
            check(events == printing.size(), "Число событий завершения");
            for (Simulation.EventView e : v.calendar()) {
                check(e.time() >= v.time(), "Событие в прошлом");
                if (e.kind() == Simulation.Kind.ARRIVAL) check(e.time() <= cfg.horizon(), "Поступление за горизонтом");
            }
        }
        Statistics.Row total = sim.snapshot().statistics().get(0);
        check(total.generated() == total.completed() + total.rejected(), "Конечный баланс");
        if (total.completed() > 0) {
            check(Math.abs(total.total() - total.waiting() - total.service()) < 1e-8, "Tсист != Tож + Tобсл");
            double busySum = sim.snapshot().busyTimes().stream().mapToDouble(Double::doubleValue).sum();
            check(Math.abs(busySum - total.service() * total.completed()) < 1e-7, "Занятость != обслуживание");
        }
        for (double busy : sim.snapshot().busyTimes())
            check(busy / sim.observationTime() <= 1.00000001, "Загрузка > 1");
        return trace;
    }

    private static void encapsulation() {
        Class<?>[] classes = {PrintJob.class, Source.class, Buffer.class, Printer.class,
                Statistics.class, AdmissionDispatcher.class, SelectionDispatcher.class, Simulation.class};
        for (Class<?> type : classes)
            for (var field : type.getDeclaredFields())
                if (!field.isSynthetic()) check(Modifier.isPrivate(field.getModifiers()), "Поле не закрыто: " + field);
        Simulation sim = new Simulation(config(3, 2, 2));
        List<Simulation.EventView> before = sim.snapshot().calendar();
        try {
            before.clear();
            throw new AssertionError("Изменяемый снимок календаря");
        } catch (UnsupportedOperationException expected) { }
        check(before.size() == sim.snapshot().calendar().size(), "Снимок повредил модель");
        try {
            sim.config().rates().set(0, 100.0);
            throw new AssertionError("Изменяемые параметры");
        } catch (UnsupportedOperationException expected) { }
    }

    private static void invalidParameters() {
        for (double rate : new double[] {0, -1, Double.NaN, Double.POSITIVE_INFINITY}) {
            try {
                new Simulation.Config(List.of(rate), List.of(new Simulation.Bounds(1, 2)), 1, 1, 1);
                throw new AssertionError("Принята некорректная интенсивность");
            } catch (IllegalArgumentException expected) { }
        }
        Simulation empty = new Simulation(config(1, 1, 2), List.of(), Map.of());
        check(!empty.step() && empty.snapshot().statistics().get(0).loss() == null, "Пустой прогон");
    }

    public static void main(String[] args) {
        fixedScenario();
        equalTimes();
        pendingFifo();
        encapsulation();
        invalidParameters();
        Random random = new Random(9);
        for (int seed = 0; seed < 400; seed++) {
            int n = 1 + random.nextInt(5), m = 1 + random.nextInt(4);
            List<Double> rates = new ArrayList<>();
            List<Simulation.Bounds> bounds = new ArrayList<>();
            for (int i = 0; i < n; i++) rates.add(0.1 + random.nextDouble() * 3);
            for (int i = 0; i < m; i++) {
                double min = 0.1 + random.nextDouble() * 3;
                bounds.add(new Simulation.Bounds(min, min + 0.1 + random.nextDouble() * 4));
            }
            stress(new Simulation.Config(rates, bounds, 1 + random.nextInt(8), 20, seed));
        }
        Simulation.Config cfg = Simulation.Config.defaults(23);
        check(stress(cfg).equals(stress(cfg)), "Seed не воспроизводит прогон");
        System.out.println("OK: сценарии, инкапсуляция, неизменяемые снимки, 400 конфигураций; событий: " + checkedEvents);
    }
}
