import java.util.ArrayList;
import java.util.List;

final class Buffer {
    private final int capacity;
    private final List<PrintJob> queue = new ArrayList<>();

    Buffer(int capacity) { this.capacity = capacity; }
    boolean isFull() { return queue.size() >= capacity; }

    void append(PrintJob job) {
        if (isFull()) throw new IllegalStateException("Буфер заполнен.");
        job.markWaiting();
        queue.add(job);
    }

    // Удаление из ArrayList сдвигает последующие заявки к началу.
    PrintJob removeById(String id) {
        for (int i = 0; i < queue.size(); i++)
            if (queue.get(i).id().equals(id)) return queue.remove(i);
        throw new IllegalStateException("Заявка не найдена: " + id);
    }

    List<PrintJob.View> snapshot() {
        return queue.stream().map(PrintJob::view).toList();
    }
}
