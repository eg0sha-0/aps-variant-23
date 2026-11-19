from __future__ import annotations

from collections import deque
from dataclasses import dataclass, field
from heapq import heappop, heappush
from math import isfinite
from random import Random
from statistics import mean, variance
from typing import Callable


COMPLETED = 0
ARRIVAL = 1


@dataclass(frozen=True)
class Config:
    rates: tuple[float, ...] = (0.8, 0.6, 0.4)
    service: tuple[tuple[float, float], ...] = ((2, 5), (2, 5))
    capacity: int = 3
    horizon: float = 12
    seed: int | None = None

    def __post_init__(self) -> None:
        if not self.rates or any(not isfinite(r) or r <= 0 for r in self.rates):
            raise ValueError("Интенсивности источников должны быть конечными и больше нуля.")
        if not self.service or any(
            not (0 < a < b < float("inf")) for a, b in self.service
        ):
            raise ValueError("Для каждого принтера должно выполняться 0 < a < b.")
        if self.capacity < 1 or not isfinite(self.horizon) or self.horizon <= 0:
            raise ValueError("Нужно хотя бы одно место в буфере и конечный горизонт больше нуля.")


@dataclass
class PrintJob:
    id: str
    source_id: int
    arrived_at: float
    state: str = "CREATED"
    started_at: float | None = None
    completed_at: float | None = None

    def start(self, now: float) -> None:
        self.state, self.started_at = "PRINTING", now

    def complete(self, now: float) -> None:
        self.state, self.completed_at = "COMPLETED", now


@dataclass
class Source:
    id: int
    rate: float
    rng: Random
    serial: int = 0

    # Создаёт новую заявку с номером источника и временем поступления.
    def generate(self, now: float) -> PrintJob:
        self.serial += 1
        return PrintJob(f"{self.id}.{self.serial}", self.id, now)

    # Рассчитывает время следующего поступления по экспоненциальному закону.
    def next_arrival(self, now: float) -> float:
        return now + self.rng.expovariate(self.rate)


@dataclass
class Buffer:
    capacity: int
    queue: list[PrintJob] = field(default_factory=list)

    def is_full(self) -> bool:
        return len(self.queue) >= self.capacity

    def append(self, job: PrintJob) -> None:
        job.state = "WAITING"
        self.queue.append(job)

    def remove_by_id(self, job_id: str) -> PrintJob:
        index = next(i for i, job in enumerate(self.queue) if job.id == job_id)
        return self.queue.pop(index)


@dataclass
class Printer:
    id: int
    bounds: tuple[float, float]
    rng: Random
    current_job: PrintJob | None = None
    completion_time: float | None = None

    # Начинает печать и определяет время её окончания по равномерному закону.
    def start(self, job: PrintJob, now: float, duration: float | None = None) -> float:
        assert self.current_job is None
        if duration is None:
            duration = self.rng.uniform(*self.bounds)
        job.start(now)
        self.current_job = job
        self.completion_time = now + duration
        return self.completion_time

    def complete(self, now: float, job_id: str) -> PrintJob:
        job = self.current_job
        assert job is not None and job.id == job_id and self.completion_time == now
        job.complete(now)
        self.current_job = self.completion_time = None
        return job


class Statistics:
    def __init__(self, source_count: int, printer_count: int) -> None:
        self.generated = dict.fromkeys(range(1, source_count + 1), 0)
        self.rejected = self.generated.copy()
        self.started = self.generated.copy()
        self.completed = self.generated.copy()
        self.times: dict[int, list[tuple[float, float, float]]] = {
            i: [] for i in self.generated
        }
        self.busy_times = dict.fromkeys(range(1, printer_count + 1), 0.0)

    def on_completed(self, job: PrintJob, printer_id: int) -> None:
        assert job.started_at is not None and job.completed_at is not None
        service = job.completed_at - job.started_at
        self.completed[job.source_id] += 1
        self.times[job.source_id].append(
            (job.started_at - job.arrived_at, service, job.completed_at - job.arrived_at)
        )
        self.busy_times[printer_id] += service

    # Считает отказы, средние времена и дисперсии для итоговой таблицы.
    def summary(self, source: int | None = None) -> dict[str, int | float | None]:
        keys = tuple(self.generated) if source is None else (source,)
        generated = sum(self.generated[i] for i in keys)
        rejected = sum(self.rejected[i] for i in keys)
        completed = sum(self.completed[i] for i in keys)
        samples = [sample for i in keys for sample in self.times[i]]
        waits, service, total = zip(*samples) if samples else ((), (), ())
        return {
            "generated": generated, "rejected": rejected, "completed": completed,
            "loss": rejected / generated if generated else None,
            "wait": mean(waits) if waits else None,
            "service": mean(service) if service else None,
            "total": mean(total) if total else None,
            "wait_variance": variance(waits) if len(waits) > 1 else None,
            "service_variance": variance(service) if len(service) > 1 else None,
        }


class AdmissionDispatcher:
    def __init__(self, buffer: Buffer, statistics: Statistics) -> None:
        self.buffer, self.statistics = buffer, statistics

    # Ставит новую заявку в конец буфера или отказывает, если буфер заполнен.
    def admit(self, job: PrintJob, actions: list[str]) -> bool:
        self.statistics.generated[job.source_id] += 1
        actions.append(f"Источник И{job.source_id} создал заявку {job.id}.")
        if self.buffer.is_full():
            job.state = "REJECTED"
            self.statistics.rejected[job.source_id] += 1
            actions.append(
                f"Диспетчер постановки отклонил {job.id}: буфер полон (Д1ОО5)."
            )
            return False
        self.buffer.append(job)
        actions.append(f"Диспетчер постановки добавил {job.id} в конец буфера (Д1ОЗ2).")
        return True


class SelectionDispatcher:
    def __init__(
        self, buffer: Buffer, printers: list[Printer], statistics: Statistics,
        duration_for: Callable[[PrintJob], float] | None = None,
    ) -> None:
        self.buffer, self.printers, self.statistics = buffer, printers, statistics
        self.duration_for = duration_for
        self.member_ids: tuple[str, ...] = ()
        self.pending_ids: deque[str] = deque()
        self.in_flight_ids: set[str] = set()

    # Отмечает выполненную заявку и закрывает пакет после завершения всех его заданий.
    def on_completed(self, job: PrintJob, actions: list[str]) -> None:
        self.in_flight_ids.remove(job.id)
        if not self.in_flight_ids and not self.pending_ids:
            actions.append("Весь пакет напечатан. Диспетчер выбора закрыл его.")
            self.member_ids = ()

    # Фиксирует пакет по приоритету источника и назначает его заявки свободным принтерам.
    def dispatch(self, now: float, actions: list[str]) -> list[tuple[float, int, str]]:
        completions = []

        while (printer := next((p for p in self.printers if p.current_job is None), None)):
            if not self.member_ids:
                if not self.buffer.queue:
                    break
                source_id = min(job.source_id for job in self.buffer.queue)
                self.member_ids = tuple(
                    job.id for job in self.buffer.queue if job.source_id == source_id
                )
                self.pending_ids.extend(self.member_ids)
                actions.append(f"Создан пакет И{source_id}: {', '.join(self.member_ids)} (Д2Б5).")
            if not self.pending_ids:
                if self.buffer.queue:
                    actions.append("Свободные принтеры ждут завершения текущего пакета.")
                break
            job = self.buffer.remove_by_id(self.pending_ids.popleft())
            duration = self.duration_for(job) if self.duration_for else None
            end = printer.start(job, now, duration)
            self.in_flight_ids.add(job.id)
            self.statistics.started[job.source_id] += 1
            completions.append((end, printer.id, job.id))
            actions.append(
                f"Из буфера взята {job.id}; П{printer.id} печатает её до {end:.3f} (Д2П1, ПЗ2)."
            )
        return completions


@dataclass(frozen=True, order=True)
class Event:
    time: float
    kind: int
    sequence: int
    owner_id: int = field(compare=False)
    job_id: str | None = field(default=None, compare=False)


class Simulation:
    def __init__(
        self, config: Config, *,
        arrivals: tuple[tuple[float, int], ...] | None = None,
        duration_for: Callable[[PrintJob], float] | None = None,
    ) -> None:
        self.config = config
        self.time = 0.0
        self.step_number = 0
        self.calendar: list[Event] = []
        self._sequence = 0
        self._scripted = arrivals is not None
        rng = Random(config.seed)
        self.sources = [
            Source(i, rate, Random(rng.getrandbits(64))) for i, rate in enumerate(config.rates, 1)
        ]
        self.printers = [
            Printer(i, bounds, Random(rng.getrandbits(64)))
            for i, bounds in enumerate(config.service, 1)
        ]
        self.buffer = Buffer(config.capacity)
        self.statistics = Statistics(len(self.sources), len(self.printers))
        self.admission = AdmissionDispatcher(self.buffer, self.statistics)
        self.selection = SelectionDispatcher(
            self.buffer, self.printers, self.statistics, duration_for
        )
        if arrivals is None:
            for source in self.sources:
                self._schedule_arrival(source)
        else:
            for time, source_id in arrivals:
                if not (0 <= time <= config.horizon and 1 <= source_id <= len(self.sources)):
                    raise ValueError("Поступление сценария вне горизонта или источник неизвестен.")
                self.schedule(time, ARRIVAL, source_id)

    # Добавляет событие в календарь с учётом времени и приоритета.
    def schedule(self, time: float, kind: int, owner_id: int, job_id: str | None = None) -> None:
        self._sequence += 1
        heappush(self.calendar, Event(time, kind, self._sequence, owner_id, job_id))

    def _schedule_arrival(self, source: Source) -> None:
        time = source.next_arrival(self.time)
        if time <= self.config.horizon:
            self.schedule(time, ARRIVAL, source.id)

    # Обрабатывает ближайшее событие и все вызванные им действия системы.
    def step(self) -> tuple[Event, list[str]] | None:
        if not self.calendar:
            assert not self.buffer.queue and not self.selection.member_ids
            return None
        event = heappop(self.calendar)
        self.time = event.time
        self.step_number += 1
        actions: list[str] = []
        if event.kind == ARRIVAL:
            source = self.sources[event.owner_id - 1]
            dispatch = self.admission.admit(source.generate(self.time), actions)
            if not self._scripted:
                self._schedule_arrival(source)
        else:
            printer = self.printers[event.owner_id - 1]
            assert event.job_id is not None
            job = printer.complete(self.time, event.job_id)
            actions.append(f"П{printer.id} закончил печать {job.id} и освободился.")
            self.statistics.on_completed(job, printer.id)
            self.selection.on_completed(job, actions)
            dispatch = True
        if dispatch:
            for time, printer_id, job_id in self.selection.dispatch(self.time, actions):
                self.schedule(time, COMPLETED, printer_id, job_id)
        return event, actions

    def run(self) -> None:
        while self.step() is not None:
            pass

    @property
    def observation_time(self) -> float:
        return max(self.config.horizon, self.time)


def demo() -> Simulation:
    durations = {"1.1": 4, "2.1": 2, "2.2": 5, "2.3": 2, "3.1": 2}
    return Simulation(
        Config(horizon=5),
        arrivals=((0, 1), (1, 2), (2, 2), (3, 3), (3.5, 1), (5, 2)),
        duration_for=lambda job: durations[job.id],
    )




import argparse




DEPARTMENTS = ("Бухгалтерия", "Отдел кадров", "Аналитический отдел")


def table(headers: tuple[str, ...], rows: list[tuple[object, ...]]) -> None:
    lines = [headers, *(tuple(map(str, row)) for row in rows)]
    widths = [max(map(len, column)) for column in zip(*lines)]
    for i, row in enumerate(lines):
        print(" | ".join(cell.ljust(width) for cell, width in zip(row, widths)).rstrip())
        if i == 0:
            print("-+-".join("-" * width for width in widths))


def listing(items) -> str:
    return ", ".join(items) or "—"


def fmt(value: float | None) -> str:
    return "—" if value is None else f"{value:.3f}"


def percent(value: float | None) -> str:
    return "—" if value is None else f"{value:.1%}"


def event_name(event: Event) -> str:
    if event.kind == ARRIVAL:
        return f"Поступление от И{event.owner_id}"
    return f"Окончание печати {event.job_id} на П{event.owner_id}"


# Показывает очередь, пакет, принтеры, статистику и календарь после шага.
def show_state(sim: Simulation, result: tuple[Event, list[str]] | None = None) -> None:
    selection = sim.selection
    queue = [job.id for job in sim.buffer.queue]
    printers = [
        f"П{p.id}: {p.current_job.id} до {fmt(p.completion_time)}"
        if p.current_job else f"П{p.id}: свободен"
        for p in sim.printers
    ]
    print(f"\nШаг {sim.step_number} | Модельное время: {fmt(sim.time)} мин")
    if result:
        event, actions = result
        print(f"Событие: {event_name(event)}")
        for action in actions:
            print(f"  • {action}")
    else:
        print("Начальное состояние")
    print("\nСхема системы:")
    sources = ", ".join(f"И{s.id}" for s in sim.sources)
    print(f"  {sources} → Диспетчер постановки → Буфер [{listing(queue)}]"
          f" ({len(queue)}/{sim.buffer.capacity})")
    print(f"  Буфер → Диспетчер выбора → {'; '.join(printers)}")
    print(f"  Диспетчер постановки → Статистика (отказы)")
    print(f"  Принтеры → Статистика (выполненные задания)")
    print(f"Пакет: {listing(selection.member_ids)} | Ждут: {listing(selection.pending_ids)}"
          f" | Печатаются: {listing(job for job in selection.member_ids if job in selection.in_flight_ids)}")

    future = sorted(sim.calendar)
    next_arrival = {}
    for event in future:
        if event.kind == ARRIVAL:
            next_arrival.setdefault(event.owner_id, event.time)
    stats = sim.statistics
    print("\nИсточники:")
    table(
        ("Источник", "След. поступление", "Поступило", "Отказано", "Выполнено", "% отказов"),
        [
            (f"И{s.id}", fmt(next_arrival.get(s.id)), stats.generated[s.id], stats.rejected[s.id],
             stats.completed[s.id], percent(stats.rejected[s.id] / stats.generated[s.id]
                                            if stats.generated[s.id] else None))
            for s in sim.sources
        ],
    )
    generated, rejected = sum(stats.generated.values()), sum(stats.rejected.values())
    print(f"Всего отказов: {rejected} из {generated} ({percent(rejected / generated if generated else None)}).")

    print("\nКалендарь будущих событий:")
    if future:
        table(("Время, мин", "Событие"), [(fmt(e.time), event_name(e)) for e in future])
    else:
        print("  Нет будущих событий.")


# Выводит итоговые показатели системы и загрузку принтеров.
def show_summary(sim: Simulation) -> None:
    print(f"\nМоделирование завершено. Горизонт поступлений: {fmt(sim.config.horizon)} мин."
          f" Интервал наблюдения: {fmt(sim.observation_time)} мин.")
    rows = []
    for source in [*(s.id for s in sim.sources), None]:
        s = sim.statistics.summary(source)
        rows.append((
            f"И{source}" if source is not None else "Всего",
            s["generated"], s["rejected"], s["completed"], percent(s["loss"]),
            fmt(s["wait"]), fmt(s["service"]), fmt(s["total"]),
            fmt(s["wait_variance"]), fmt(s["service_variance"]),
        ))
    table(("Источник", "Поступило", "Отказано", "Выполнено", "% отказов",
           "Ожид., мин", "Печать, мин", "Всего, мин", "Дисп. ожид.", "Дисп. печати"), rows)
    print("\nЗагрузка принтеров:")
    for printer in sim.printers:
        busy = sim.statistics.busy_times[printer.id]
        print(f"  П{printer.id}: {percent(busy / sim.observation_time)} (печатал {fmt(busy)} мин)")
    overall = sum(sim.statistics.busy_times.values()) / (len(sim.printers) * sim.observation_time)
    print(f"  Средняя загрузка: {percent(overall)}")


def parse_bounds(value: str) -> tuple[float, float]:
    try:
        a, b = map(float, value.split(":"))
    except ValueError as exc:
        raise argparse.ArgumentTypeError("Укажите интервал в виде МИН:МАКС, например 2:5.") from exc
    return a, b


# Читает параметры запуска и переключает события по нажатию Enter.
def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Пошаговая модель системы печати, вариант 23")
    parser.add_argument("--demo", action="store_true", help="готовый сценарий для защиты")
    parser.add_argument("--no-pause", action="store_true", help="вывести все шаги без ожидания Enter")
    parser.add_argument("--rates", nargs="+", type=float, help="интенсивности источников, заявок/мин")
    parser.add_argument("--service", nargs="+", type=parse_bounds, metavar="МИН:МАКС",
                        help="интервалы печати в минутах, один интервал на принтер")
    parser.add_argument("--buffer", type=int, help="вместимость буфера")
    parser.add_argument("--horizon", type=float, help="момент прекращения поступлений, мин")
    parser.add_argument("--seed", type=int, help="число для повторения запуска; без параметра каждый запуск новый")
    args = parser.parse_args(argv)
    options = {k: v for k, v in vars(args).items()
               if k not in {"demo", "no_pause"} and v is not None}
    if args.demo and options:
        parser.error("Параметры случайного запуска нельзя совмещать с --demo.")
    try:
        if args.demo:
            sim = demo()
        else:
            if "buffer" in options:
                options["capacity"] = options.pop("buffer")
            for key in ("rates", "service"):
                if key in options:
                    options[key] = tuple(options[key])
            sim = Simulation(Config(**options))
    except ValueError as exc:
        parser.error(str(exc))

    if args.demo:
        print("Учебный сценарий: времена заранее заданы, чтобы повторить пример на защите.")
    else:
        seed_info = f"seed={sim.config.seed}" if sim.config.seed is not None else "новые случайные значения"
        print(f"Случайный запуск: {seed_info}, горизонт {sim.config.horizon:g} мин.")
    print("Источники (в порядке приоритета):")
    for source in sim.sources:
        name = DEPARTMENTS[source.id - 1] if source.id <= len(DEPARTMENTS) else f"Подразделение {source.id}"
        rate = "" if args.demo else f", λ={source.rate:g} заявок/мин"
        print(f"  И{source.id} — {name}{rate}")
    show_state(sim)
    while sim.calendar:
        if not args.no_pause:
            try:
                answer = input("\nEnter — следующий шаг; q — выход: ").strip().lower()
            except (EOFError, KeyboardInterrupt):
                answer = "q"
            if answer in {"q", "й", "выход"}:
                print("\nПошаговый просмотр остановлен.")
                return 0
            if answer:
                continue
        show_state(sim, sim.step())
    show_summary(sim)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
