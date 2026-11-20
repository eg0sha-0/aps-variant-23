import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Scanner;

public final class Main {
    private static final String[] NAMES = {"Бухгалтерия", "Отдел кадров", "Аналитический отдел"};

    public static void main(String[] args) {
        System.setOut(new java.io.PrintStream(System.out, true, StandardCharsets.UTF_8));
        System.setErr(new java.io.PrintStream(System.err, true, StandardCharsets.UTF_8));
        Scanner input = new Scanner(System.in, StandardCharsets.UTF_8);
        try {
            boolean defaults = Arrays.asList(args).contains("--defaults");
            boolean noPause = Arrays.asList(args).contains("--no-pause");
            boolean automaticFlag = Arrays.asList(args).contains("--auto");
            for (String arg : args)
                if (!arg.equals("--defaults") && !arg.equals("--no-pause") && !arg.equals("--auto"))
                    throw new IllegalArgumentException("Допустимы --defaults, --no-pause и --auto.");
            System.out.println("Ковальчук Егор. Система печати — вариант 23.");
            System.out.println("Поступления Exp(λ), печать U(a,b); время в минутах.");
            boolean automatic = automaticFlag || (!defaults && !noPause
                    && readInt(input, "Режим (1 — пошаговый ОД2, 2 — автоматический ОР1)", 1, 1, 2) == 2);
            Simulation.Config config = defaults
                    ? Simulation.Config.defaults(new Random().nextLong()) : readConfig(input, automatic);
            if (automatic) {
                int limit = defaults ? AutomaticRun.DEFAULT_LIMIT : readInt(input,
                        "Лимит заявок на один прогон", AutomaticRun.DEFAULT_LIMIT,
                        AutomaticRun.INITIAL_SAMPLE, 1_000_000);
                runAutomatic(config, limit);
            } else {
                run(new Simulation(config), input, noPause);
            }
        } catch (IllegalArgumentException ex) {
            System.err.println("Ошибка: " + ex.getMessage());
            System.exit(1);
        } catch (java.util.NoSuchElementException ex) {
            System.out.println("\nВвод завершён. Программа остановлена.");
        }
    }

    // Запрашивает параметры; Enter оставляет показанное значение по умолчанию.
    private static Simulation.Config readConfig(Scanner input, boolean automatic) {
        System.out.println("Настройка: Enter — значение в скобках.");
        int sourceCount = readInt(input, "Число подразделений", 3, 1, 100);
        int printerCount = readInt(input, "Число принтеров", 2, 1, 100);
        int capacity = readInt(input, "Мест в буфере", 3, 1, 10000);
        List<Double> rates = new ArrayList<>();
        double[] defaults = {0.8, 0.6, 0.4};
        for (int i = 0; i < sourceCount; i++)
            rates.add(readDouble(input, "λ источника И" + (i + 1) + ", заявок/мин",
                    i < defaults.length ? defaults[i] : 0.4, 0, 10000));
        List<Simulation.Bounds> bounds = new ArrayList<>();
        for (int i = 1; i <= printerCount; i++) {
            double min = readDouble(input, "Минимум печати П" + i, 2, 0, 1000000);
            double max = readDouble(input, "Максимум печати П" + i,
                    Math.max(5, min + 1), min, Double.MAX_VALUE);
            bounds.add(new Simulation.Bounds(min, max));
        }
        double horizon = automatic ? 12 : readDouble(input, "Горизонт поступлений", 12, 0, 1000000);
        long seed;
        while (true) {
            System.out.print("Seed [Enter — новый случайный запуск]: ");
            String line = input.nextLine().trim();
            try {
                seed = line.isEmpty() ? new Random().nextLong() : Long.parseLong(line);
                break;
            } catch (NumberFormatException ex) {
                System.out.println("Введите целое число.");
            }
        }
        return new Simulation.Config(rates, bounds, capacity, horizon, seed);
    }

    private static int readInt(Scanner input, String prompt, int fallback, int min, int max) {
        while (true) {
            System.out.print(prompt + " [" + fallback + "]: ");
            String line = input.nextLine().trim();
            if (line.isEmpty()) return fallback;
            try {
                int n = Integer.parseInt(line);
                if (n >= min && n <= max) return n;
            } catch (NumberFormatException ignored) { }
            System.out.println("Введите целое число от " + min + " до " + max + ".");
        }
    }

    private static double readDouble(Scanner input, String prompt, double fallback,
                                     double exclusiveMin, double max) {
        while (true) {
            System.out.print(prompt + " [" + fallback + "]: ");
            String line = input.nextLine().trim().replace(',', '.');
            if (line.isEmpty()) return fallback;
            try {
                double n = Double.parseDouble(line);
                if (Double.isFinite(n) && n > exclusiveMin && n <= max) return n;
            } catch (NumberFormatException ignored) { }
            System.out.println("Введите конечное число больше " + exclusiveMin + ", не более " + max + ".");
        }
    }

    // По Enter выполняет событие, j выводит журнал, число N выполняет несколько шагов.
    private static void run(Simulation sim, Scanner input, boolean noPause) {
        System.out.println("Seed: " + sim.config().seed() + ". Сохраните число для повторения прогона.");
        for (int i = 0; i < sim.config().rates().size(); i++)
            System.out.println("И" + (i + 1) + " — " + (i < NAMES.length ? NAMES[i] : "Подразделение " + (i + 1))
                    + ", λ=" + sim.config().rates().get(i));
        showState(sim);
        while (sim.hasEvents()) {
            int count = 1;
            if (!noPause) {
                System.out.print("\nEnter — шаг; N — N шагов; j — журнал; q — выход: ");
                if (!input.hasNextLine()) return;
                String command = input.nextLine().trim().toLowerCase(Locale.ROOT);
                if (command.equals("q")) return;
                if (command.equals("j")) {
                    List<List<String>> rows = sim.journal().stream()
                            .map(e -> List.of("" + e.step(), f(e.time()), e.action())).toList();
                    table(List.of("Шаг", "Время", "Действие"), rows);
                    continue;
                }
                if (!command.isEmpty()) {
                    try { count = Integer.parseInt(command); }
                    catch (NumberFormatException ex) { count = 0; }
                    if (count < 1 || count > 10000) {
                        System.out.println("Введите Enter, число 1..10000, j или q.");
                        continue;
                    }
                }
            }
            for (int i = 0; i < count && sim.step(); i++) showState(sim);
        }
        showSummary(sim);
    }

    // Показывает формализованную схему и состояния компонентов после события.
    private static void showState(Simulation sim) {
        Simulation.View v = sim.snapshot();
        System.out.println("\n=== Шаг " + v.step() + ", t=" + f(v.time()) + " мин ===");
        if (sim.lastEvent() != null) System.out.println(eventName(sim.lastEvent()));
        List<List<String>> actions = new ArrayList<>();
        for (String action : sim.lastActions()) actions.add(List.of("" + v.step(), f(v.time()), action));
        if (!actions.isEmpty()) table(List.of("Шаг", "Время", "Действие"), actions);
        System.out.println("Схема: Источники → Диспетчер постановки → Буфер → Диспетчер выбора → Принтеры → Выход");
        System.out.println("                          ↓ отказ                                      ↓ выполнено");
        System.out.println("                      Статистика ←────────────────────────────────────┘");
        System.out.println("\nИсточники:");
        List<List<String>> rows = new ArrayList<>();
        for (Statistics.Row s : v.statistics()) {
            if (s.sourceId() == 0) continue;
            Double next = v.calendar().stream()
                    .filter(e -> e.kind() == Simulation.Kind.ARRIVAL && e.ownerId() == s.sourceId())
                    .map(Simulation.EventView::time).findFirst().orElse(null);
            rows.add(List.of("И" + s.sourceId(), "" + s.sourceId(), f(next), "" + s.generated(),
                    "" + s.rejected(), "" + s.completed(), pct(s.loss())));
        }
        table(List.of("Источник", "Приоритет", "След. заявка", "Создано", "Отказ", "Готово", "% отказа"), rows);
        System.out.println("\nБуфер (" + v.queue().size() + "/" + sim.config().capacity() + "):");
        rows = new ArrayList<>();
        for (int i = 0; i < sim.config().capacity(); i++) {
            PrintJob.View job = i < v.queue().size() ? v.queue().get(i) : null;
            rows.add(job == null ? List.of("" + (i + 1), "—", "—", "—", "—")
                    : List.of("" + (i + 1), job.id(), "И" + job.sourceId(), f(job.arrivedAt()),
                    f(v.time() - job.arrivedAt())));
        }
        table(List.of("Место", "Заявка", "Источник", "Пришла в", "Ждёт, мин"), rows);
        System.out.println("\nПринтеры:");
        rows = v.printers().stream().map(p -> List.of("П" + p.id(),
                p.job() == null ? "свободен" : "печатает", p.job() == null ? "—" : p.job().id(),
                f(p.completionTime()))).toList();
        table(List.of("Принтер", "Состояние", "Заявка", "Завершит в"), rows);
        SelectionDispatcher.View batch = v.batch();
        System.out.println("Пакет: " + ids(batch.members()) + "; ожидают: " + ids(batch.pending())
                + "; печатаются: " + ids(batch.members().stream().filter(batch.inFlight()::contains).toList()));
        System.out.println("Указателей нет: Д1ОЗ2 — очередь, Д2П1 — поиск всегда от № 1.");
        Statistics.Row total = v.statistics().get(0);
        System.out.println("Всего отказов: " + total.rejected() + "/" + total.generated() + " (" + pct(total.loss()) + ").");
        System.out.println("\nКалендарь будущих событий:");
        table(List.of("Время", "Событие"), v.calendar().stream()
                .map(e -> List.of(f(e.time()), eventName(e))).toList());
    }

    private static void runAutomatic(Simulation.Config config, int limit) {
        System.out.println("\nОР1. Подбор выборки для общей доли отказов. Seed: " + config.seed());
        System.out.println("Цель по методичке: относительная точность 10%, доверительная вероятность 0,9.");
        List<List<String>> rows = new ArrayList<>();
        AutomaticRun.Result result = AutomaticRun.run(config, limit, trial ->
                rows.add(List.of("" + trial.generated(), pct(trial.loss()),
                        trial.required() == Long.MAX_VALUE ? "—" : "" + trial.required(), pct(trial.change()))));
        table(List.of("Заявок", "% отказа", "Нужно по формуле", "Изменение оценки"), rows);
        if (result.trial().reached()) {
            System.out.println("Критерий методички выполнен для общей доли отказов.");
        } else {
            System.out.println("Достигнут лимит. Заданная точность общей доли отказов не подтверждена.");
        }
        showSummary(result.simulation());
    }

    private static void showSummary(Simulation sim) {
        Simulation.View v = sim.snapshot();
        System.out.println("\nВсе принятые заявки дообслужены. Конец поступлений: "
                + f(sim.generationEnd()) + "; время наблюдения: " + f(sim.observationTime()) + " мин.");
        List<List<String>> rows = new ArrayList<>();
        for (Statistics.Row s : v.statistics())
            rows.add(List.of(s.sourceId() == 0 ? "Всего" : "И" + s.sourceId(), "" + s.generated(),
                    "" + s.rejected(), "" + s.completed(), pct(s.loss()), f(s.waiting()), f(s.service()),
                    f(s.total()), f(s.waitingVariance()), f(s.serviceVariance())));
        table(List.of("Источник", "Создано", "Отказ", "Готово", "% отказа", "Tож", "Tобсл", "Tсист", "Dож", "Dобсл"), rows);
        rows = new ArrayList<>();
        for (int i = 0; i < v.busyTimes().size(); i++)
            rows.add(List.of("П" + (i + 1), f(v.busyTimes().get(i)),
                    pct(v.busyTimes().get(i) / sim.observationTime())));
        table(List.of("Принтер", "Печатал, мин", "Загрузка"), rows);
        System.out.println("Времена — минуты, дисперсии — мин². «—» означает недостаточно наблюдений.");
    }

    private static String eventName(Simulation.EventView e) {
        return e.kind() == Simulation.Kind.ARRIVAL ? "Поступление от И" + e.ownerId()
                : "Завершение " + e.jobId() + " на П" + e.ownerId();
    }
    private static String ids(List<String> values) { return values.isEmpty() ? "—" : String.join(", ", values); }
    private static String f(Double value) { return value == null ? "—" : String.format(Locale.ROOT, "%.4f", value); }
    private static String pct(Double value) { return value == null ? "—" : String.format(Locale.ROOT, "%.1f%%", 100 * value); }

    private static void table(List<String> headers, List<List<String>> rows) {
        int[] widths = headers.stream().mapToInt(String::length).toArray();
        for (List<String> row : rows)
            for (int i = 0; i < widths.length; i++) widths[i] = Math.max(widths[i], row.get(i).length());
        printRow(headers, widths);
        System.out.println(String.join("-+-", Arrays.stream(widths).mapToObj(w -> "-".repeat(w)).toList()));
        for (List<String> row : rows) printRow(row, widths);
        if (rows.isEmpty()) System.out.println("(пусто)");
    }

    private static void printRow(List<String> row, int[] widths) {
        List<String> cells = new ArrayList<>();
        for (int i = 0; i < widths.length; i++) cells.add(row.get(i) + " ".repeat(widths[i] - row.get(i).length()));
        System.out.println(String.join(" | ", cells));
    }
}
