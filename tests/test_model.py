import unittest

from printsim.model import ARRIVAL, COMPLETED, Config, Simulation, demo


class SimulationTests(unittest.TestCase):
    def test_demo_covers_overflow_fixed_batch_and_priority(self):
        sim = demo()
        snapshots = {}
        while result := sim.step():
            event, _ = result
            snapshots[event.time] = (
                [job.id for job in sim.buffer.queue],
                sim.selection.member_ids,
                [p.current_job.id if p.current_job else None for p in sim.printers],
            )

        self.assertEqual(snapshots[0], ([], ("1.1",), ["1.1", None]))
        self.assertEqual(snapshots[3.5], (["2.1", "2.2", "3.1"], ("1.1",), ["1.1", None]))
        self.assertEqual(snapshots[4], (["3.1"], ("2.1", "2.2"), ["2.1", "2.2"]))
        self.assertEqual(snapshots[5], (["3.1", "2.3"], ("2.1", "2.2"), ["2.1", "2.2"]))
        self.assertEqual(snapshots[6], (["3.1", "2.3"], ("2.1", "2.2"), [None, "2.2"]))
        self.assertEqual(snapshots[9], (["3.1"], ("2.3",), ["2.3", None]))
        self.assertEqual(snapshots[13], ([], (), [None, None]))
        self.assertEqual(sim.statistics.summary()["generated"], 6)
        self.assertEqual(sim.statistics.summary()["completed"], 5)
        self.assertEqual(sim.statistics.rejected, {1: 1, 2: 0, 3: 0})
        self.assertEqual(sim.statistics.busy_times, {1: 10.0, 2: 5.0})

    def test_completion_precedes_arrival_and_releases_buffer_space(self):
        sim = Simulation(
            Config(capacity=1, service=((1, 2),), horizon=2),
            arrivals=((0, 1), (1, 2), (2, 3)),
            duration_for=lambda job: 2,
        )
        events = []
        while result := sim.step():
            events.append((result[0].time, result[0].kind))
        self.assertEqual(events[2:4], [(2, COMPLETED), (2, ARRIVAL)])
        self.assertEqual(sim.statistics.summary()["completed"], 3)
        self.assertEqual(sim.statistics.summary()["rejected"], 0)
        self.assertEqual(sim.observation_time, 6)

    def test_waiting_members_refill_printers_in_fifo_order(self):
        sim = Simulation(
            Config(capacity=4, horizon=4),
            arrivals=((0, 2), (1, 1), (2, 1), (3, 1), (4, 1)),
            duration_for=lambda job: 5 if job.id == "2.1" else 2,
        )
        for _ in range(6):
            sim.step()
        self.assertEqual([p.current_job.id for p in sim.printers], ["1.1", "1.2"])
        self.assertEqual(list(sim.selection.pending_ids), ["1.3", "1.4"])
        sim.step()
        self.assertEqual([p.current_job.id for p in sim.printers], ["1.3", "1.2"])
        sim.step()
        self.assertEqual([p.current_job.id for p in sim.printers], ["1.3", "1.4"])

    def test_random_runs_repeat_and_respect_invariants(self):
        config = Config(horizon=60, seed=123, capacity=5)
        traces = []
        for _ in range(2):
            sim = Simulation(config)
            trace = []
            while result := sim.step():
                event, actions = result
                trace.append((event, actions))
                self.assertLessEqual(len(sim.buffer.queue), config.capacity)
                members = set(sim.selection.member_ids)
                printing = {p.current_job.id for p in sim.printers if p.current_job}
                self.assertEqual(printing, sim.selection.in_flight_ids)
                self.assertTrue(printing <= members)
                self.assertTrue(set(sim.selection.pending_ids) <= members)
                self.assertEqual(len({job.split(".")[0] for job in members}), bool(members))
                if event.kind == ARRIVAL:
                    self.assertLessEqual(event.time, config.horizon)
            traces.append(trace)
            stats = sim.statistics
            for i in stats.generated:
                self.assertEqual(stats.generated[i], stats.rejected[i] + stats.completed[i])
        self.assertEqual(traces[0], traces[1])

    def test_empty_run_has_defined_observation_and_no_invented_statistics(self):
        sim = Simulation(Config(horizon=2), arrivals=())
        self.assertIsNone(sim.step())
        self.assertEqual(sim.observation_time, 2)
        self.assertIsNone(sim.statistics.summary()["loss"])
        self.assertIsNone(sim.statistics.summary()["wait"])


if __name__ == "__main__":
    unittest.main()
