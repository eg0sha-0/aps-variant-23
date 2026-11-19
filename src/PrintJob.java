final class PrintJob {
    enum State { CREATED, WAITING, PRINTING, COMPLETED, REJECTED }

    record View(String id, int sourceId, State state, double arrivedAt,
                Double startedAt, Double completedAt) { }

    private final String id;
    private final int sourceId;
    private final double arrivedAt;
    private State state = State.CREATED;
    private Double startedAt;
    private Double completedAt;

    PrintJob(String id, int sourceId, double arrivedAt) {
        this.id = id;
        this.sourceId = sourceId;
        this.arrivedAt = arrivedAt;
    }

    String id() { return id; }
    int sourceId() { return sourceId; }

    private void require(State expected) {
        if (state != expected) throw new IllegalStateException("Недопустимое состояние заявки " + id);
    }

    void markWaiting() {
        require(State.CREATED);
        state = State.WAITING;
    }

    void reject() {
        require(State.CREATED);
        state = State.REJECTED;
    }

    void start(double time) {
        require(State.WAITING);
        if (time < arrivedAt) throw new IllegalArgumentException("Начало раньше поступления.");
        startedAt = time;
        state = State.PRINTING;
    }

    void complete(double time) {
        require(State.PRINTING);
        if (time <= startedAt) throw new IllegalArgumentException("Некорректное время завершения.");
        completedAt = time;
        state = State.COMPLETED;
    }

    View view() { return new View(id, sourceId, state, arrivedAt, startedAt, completedAt); }
}
