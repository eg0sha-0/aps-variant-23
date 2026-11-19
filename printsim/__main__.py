from __future__ import annotations

import argparse

from .model import ARRIVAL, Config, Event, Simulation, demo


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
