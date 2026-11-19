import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class SelectionDispatcher {
    record Completion(double time, int printerId, String jobId) { }
    record View(List<String> members, List<String> pending, Set<String> inFlight) { }

    private final Buffer buffer;
    private final List<Printer> printers;
    private final Map<String, Double> scriptedDurations;
    private List<String> memberIds = List.of();
    private final ArrayDeque<String> pendingIds = new ArrayDeque<>();
    private final Set<String> inFlightIds = new HashSet<>();

    SelectionDispatcher(Buffer buffer, List<Printer> printers, Map<String, Double> durations) {
        this.buffer = buffer;
        this.printers = List.copyOf(printers);
        this.scriptedDurations = Map.copyOf(durations);
    }

    // Закрывает пакет только после завершения всех его ожидающих и печатаемых заявок.
    void onCompleted(PrintJob job, List<String> actions) {
        if (!inFlightIds.remove(job.id())) throw new IllegalStateException("Заявка не из пакета.");
        if (pendingIds.isEmpty() && inFlightIds.isEmpty()) {
            memberIds = List.of();
            actions.add("Все задания пакета выполнены. Пакет закрыт.");
        }
    }

    private void formBatch(List<String> actions) {
        List<PrintJob.View> queue = buffer.snapshot();
        int sourceId = queue.stream().mapToInt(PrintJob.View::sourceId).min().orElseThrow();
        memberIds = queue.stream().filter(j -> j.sourceId() == sourceId).map(PrintJob.View::id).toList();
        pendingIds.addAll(memberIds);
        actions.add("Сформирован фиксированный пакет И" + sourceId + ": "
                + String.join(", ", memberIds) + " (Д2Б5).");
    }

    private Printer findFreePrinter() {
        return printers.stream().filter(Printer::isFree)
                .min(java.util.Comparator.comparingInt(Printer::id)).orElse(null);
    }

    // Выбирает заявки текущего пакета FIFO и назначает свободным принтерам по номеру.
    List<Completion> dispatch(double time, List<String> actions) {
        List<Completion> events = new ArrayList<>();
        Printer printer;
        while ((printer = findFreePrinter()) != null) {
            if (memberIds.isEmpty()) {
                if (buffer.snapshot().isEmpty()) break;
                formBatch(actions);
            }
            if (pendingIds.isEmpty()) {
                actions.add("Свободные принтеры ждут полного завершения текущего пакета.");
                break;
            }
            PrintJob job = buffer.removeById(pendingIds.removeFirst());
            actions.add("Из пакета выбрана " + job.id() + "; после извлечения буфер сдвинут.");
            double end = printer.start(job, time, scriptedDurations.get(job.id()));
            inFlightIds.add(job.id());
            events.add(new Completion(end, printer.id(), job.id()));
            actions.add(String.format(java.util.Locale.ROOT,
                    "Заявка %s передана П%d до t=%.4f (Д2П1, ПЗ2).", job.id(), printer.id(), end));
        }
        return List.copyOf(events);
    }

    View snapshot() {
        return new View(memberIds, List.copyOf(pendingIds), Set.copyOf(inFlightIds));
    }
}
