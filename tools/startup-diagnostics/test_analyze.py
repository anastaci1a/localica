import unittest
from analyze import frames, percentiles, summarize, scheduling, sections

HEADER = "Flags,IntendedVsync,Vsync,HandleInputStart,AnimationStart,PerformTraversalsStart,DrawStart,SyncQueued,SyncStart,IssueDrawCommandsStart,SwapBuffers,FrameCompleted,FrameDeadline,"


def row(start=1_000_000_000, flags=0, duration=20_000_000):
    return ",".join(map(str, [flags, start] + [start + x * 1_000_000 for x in range(1, 10)] +
                        [start + duration, start + 16_666_667])) + ","


class FrameStatsTest(unittest.TestCase):
    def test_background_threads_with_the_same_name_keep_distinct_cpu_intervals(self):
        trace = "\n".join([
            " a-1 [000] .... 1.000000: sched_switch: prev_comm=x prev_pid=1 prev_prio=0 prev_state=S ==> next_comm=DefaultDispatch next_pid=8 next_prio=0",
            " w-8 [000] .... 1.100000: sched_switch: prev_comm=DefaultDispatch prev_pid=8 prev_prio=0 prev_state=S ==> next_comm=DefaultDispatch next_pid=9 next_prio=0",
            " w-9 [000] .... 1.300000: sched_switch: prev_comm=DefaultDispatch prev_pid=9 prev_prio=0 prev_state=S ==> next_comm=x next_pid=1 next_prio=0",
        ])
        threads = scheduling(trace, "TID NAME\n7 app\n8 DefaultDispatch\n9 DefaultDispatch", 7, 1000, 1000)["threads"]
        by_tid = {value["tid"]: value for value in threads.values()}
        self.assertEqual({7, 8, 9}, set(by_tid))
        self.assertEqual((100, 200), (by_tid[8]["runningMs"], by_tid[9]["runningMs"]))

    def test_trace_slices_retain_nested_wall_time_without_calling_it_cpu_time(self):
        trace = "\n".join([
            " app-7 [000] .... 0.900000: tracing_mark_write: B|7|parent",
            " app-7 [000] .... 1.100000: tracing_mark_write: B|7|child",
            " app-7 [000] .... 1.300000: tracing_mark_write: E",
            " app-7 [000] .... 1.500000: tracing_mark_write: E|7",
        ])
        result = sections(trace, {"Main": {"tid": 7}}, 1000, 1000)
        self.assertEqual(500, result["threads"]["Main"][0]["inclusiveMs"])
        self.assertEqual(200, result["threads"]["Main"][1]["inclusiveMs"])
        self.assertEqual(0, result["unclosedSlices"])

    def test_scheduler_bounds_and_unknown_intervals(self):
        trace = "\n".join([
            " a-1 [000] .... 0.900000: sched_switch: prev_comm=x prev_pid=1 prev_prio=0 prev_state=S ==> next_comm=app next_pid=7 next_prio=0",
            " app-7 [000] .... 1.100000: sched_switch: prev_comm=app prev_pid=7 prev_prio=0 prev_state=R+ ==> next_comm=x next_pid=1 next_prio=0",
            " a-1 [000] .... 1.300000: sched_switch: prev_comm=x prev_pid=1 prev_prio=0 prev_state=S ==> next_comm=app next_pid=7 next_prio=0",
            " app-7 [000] .... 1.400000: sched_switch: prev_comm=app prev_pid=7 prev_prio=0 prev_state=S ==> next_comm=x next_pid=1 next_prio=0",
        ])
        result = scheduling(trace, "TID NAME\n8 RenderThread", 7, 1000, 1000)["threads"]
        self.assertEqual(200, result["Main"]["runningMs"])
        self.assertEqual(200, result["Main"]["runnableMs"])
        self.assertEqual(600, result["Main"]["sleepingMs"])
        self.assertEqual(1000, result["RenderThread"]["unknownMs"])

    def test_nearest_rank_percentiles_and_empty_input(self):
        result = percentiles([n * 1_000_000 for n in range(1, 101)])
        self.assertEqual((50, 95, 99), (result["p50Ms"], result["p95Ms"], result["p99Ms"]))
        self.assertIsNone(percentiles([])["p95Ms"])

    def test_flagged_unfinished_outside_and_duplicate_frames_are_not_samples(self):
        raw = "\n".join([HEADER, row(), row(), row(flags=1), row(start=500), row(duration=-1)])
        selected, rejected = frames(raw, 1_000, 6_000)
        self.assertEqual(1, len(selected))
        self.assertEqual({"flagged": 1, "unfinished": 1, "outsideWindow": 1, "duplicate": 1}, rejected)

    def test_first_five_seconds_and_deadlines_are_distinct(self):
        raw = "\n".join([HEADER, row(), row(start=7_000_000_000)])
        short = summarize(raw, {"launchUptimeMs": 1_000}, 5_000)
        full = summarize(raw, {"launchUptimeMs": 1_000})
        self.assertEqual((1, 2), (short["frames"], full["frames"]))
        self.assertEqual(1, short["deadlineMisses"])
        self.assertEqual(20, short["phases"]["total"]["p95Ms"])
        self.assertEqual(1, short["phases"]["layout"]["p50Ms"])

    def test_api26_without_deadline_is_explicitly_unknown(self):
        raw = "\n".join([HEADER.replace(",FrameDeadline", ""), row().rsplit(",", 2)[0] + ","])
        result = summarize(raw, {"launchUptimeMs": 1_000})
        self.assertEqual(1, result["frames"])
        self.assertEqual(1, result["over16_7ms"])
        self.assertIsNone(result["deadlineMisses"])

    def test_modern_frame_timeline_column_does_not_hide_all_frames(self):
        raw = "\n".join([HEADER.replace("Flags,", "Flags,FrameTimelineVsyncId,"),
                          row().replace("0,", "0,42,", 1)])
        result = summarize(raw, {"launchUptimeMs": 1_000})
        self.assertEqual(1, result["frames"])
        self.assertEqual(20, result["phases"]["total"]["p95Ms"])


if __name__ == "__main__":
    unittest.main()
