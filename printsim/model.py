"""Дискретно-событийная модель печати, вариант 23."""

from __future__ import annotations

from collections import deque
from dataclasses import dataclass, field
from heapq import heappop, heappush
from math import isfinite
from random import Random
from statistics import mean, variance
from typing import Callable


COMPLETED = 0  # При одинаковом времени завершение раньше поступления.
ARRIVAL = 1


@dataclass(frozen=True)
class Config:
    rates: tuple[float, ...] = (0.8, 0.6, 0.4)
    service: tuple[tuple[float, float], ...] = ((2, 5), (2, 5))
    capacity: int = 3
    horizon: float = 12
    seed: int = 23

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

    def generate(self, now: float) -> PrintJob:
        self.serial += 1
        return PrintJob(f"{self.id}.{self.serial}", self.id, now)

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

    def on_completed(self, job: PrintJob, actions: list[str]) -> None:
        self.in_flight_ids.remove(job.id)
        if not self.in_flight_ids and not self.pending_ids:
            actions.append("Весь пакет напечатан. Диспетчер выбора закрыл его.")
            self.member_ids = ()

    def dispatch(self, now: float, actions: list[str]) -> list[tuple[float, int, str]]:
        completions = []
        # Список принтеров хранится в порядке номеров.
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

    def schedule(self, time: float, kind: int, owner_id: int, job_id: str | None = None) -> None:
        self._sequence += 1
        heappush(self.calendar, Event(time, kind, self._sequence, owner_id, job_id))

    def _schedule_arrival(self, source: Source) -> None:
        time = source.next_arrival(self.time)
        if time <= self.config.horizon:
            self.schedule(time, ARRIVAL, source.id)

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
    """Ручной сценарий из объяснения, пригодный для повторения на защите."""
    durations = {"1.1": 4, "2.1": 2, "2.2": 5, "2.3": 2, "3.1": 2}
    return Simulation(
        Config(horizon=5),
        arrivals=((0, 1), (1, 2), (2, 2), (3, 3), (3.5, 1), (5, 2)),
        duration_for=lambda job: durations[job.id],
    )
