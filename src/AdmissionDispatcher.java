import java.util.List;

final class AdmissionDispatcher {
    private final Buffer buffer;
    private final Statistics statistics;

    AdmissionDispatcher(Buffer buffer, Statistics statistics) {
        this.buffer = buffer;
        this.statistics = statistics;
    }

    // Принимает заявку в конец буфера или отказывает именно новой заявке по Д1ОО5.
    boolean admit(PrintJob job, List<String> actions) {
        statistics.onGenerated(job);
        actions.add("Источник И" + job.sourceId() + " создал заявку " + job.id() + ".");
        if (buffer.isFull()) {
            job.reject();
            statistics.onRejected(job);
            actions.add("Диспетчер постановки отклонил " + job.id() + ": буфер полон (Д1ОО5).");
            return false;
        }
        buffer.append(job);
        actions.add("Заявка " + job.id() + " добавлена в конец буфера (Д1ОЗ2).");
        return true;
    }
}
