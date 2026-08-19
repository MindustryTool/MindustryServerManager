#!/usr/bin/env python3
"""Parse Mindustry plugin startup [timing] output and print a machine-readable summary.

Usage:
  python startup_benchmark.py <logfile>            # print summary for one log
  python startup_benchmark.py <logfile> --baseline baseline.txt [--threshold 2000]
  python startup_benchmark.py -                     # read log from stdin

Durations are parsed from TimeUtils.measure lines of the form:
    [timing] <operation> took <duration>
where <duration> is like "5s402ms", "2s168ms", "566ms", or "0ms".
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

DURATION_RE = re.compile(r"(?:(\d+)d)?(?:(\d+)h)?(?:(\d+)m)?(?:(\d+)s)?(?:(\d+)ms)?")
TIMING_RE = re.compile(r"\[timing\]\s+(.+?)\s+took\s+((?:\d+(?:ms|s|m|h|d))+)\s*$")
LOADED_RE = re.compile(r"Plugin loaded in\s+((?:\d+(?:ms|s|m|h|d))+)\s*$")


def parse_duration(text: str) -> int:
    """Parse a duration string like '1s208ms' into milliseconds."""
    match = DURATION_RE.fullmatch(text)
    if not match:
        raise ValueError(f"invalid duration: {text!r}")
    d, h, m, s, ms = (int(v) if v else 0 for v in match.groups())
    return ((d * 24 * 3600) + (h * 3600) + (m * 60) + s) * 1000 + ms


def parse_log(text: str):
    timings: dict[str, int] = {}
    for line in text.splitlines():
        tm = TIMING_RE.search(line)
        if tm:
            name, duration = tm.group(1).strip(), tm.group(2)
            timings[name] = parse_duration(duration)
        lm = LOADED_RE.search(line)
        if lm:
            timings["plugin.loaded"] = parse_duration(lm.group(1))
    return timings


def summarize(timings: dict[str, int]) -> dict:
    scan = timings.get("component scan", 0)
    components = {
        name: ms
        for name, ms in timings.items()
        if name.startswith("initialize ")
    }
    return {
        "component_scan_ms": scan,
        "plugin_loaded_ms": timings.get("plugin.loaded", 0),
        "component_count": len(components),
        "top_components_ms": dict(
            sorted(components.items(), key=lambda kv: kv[1], reverse=True)[:10]
        ),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("logfile", nargs="?", default="-", help="log file, or '-' for stdin")
    parser.add_argument("--baseline", help="baseline timing file (same format as logfile)")
    parser.add_argument("--threshold", type=int, default=2000,
                        help="max allowed component scan ms before the check fails")
    args = parser.parse_args()

    text = sys.stdin.read() if args.logfile == "-" else Path(args.logfile).read_text(
        encoding="utf-8", errors="replace")
    timings = parse_log(text)
    summary = summarize(timings)

    print("== startup timing summary ==")
    print(json.dumps(summary, indent=2, ensure_ascii=False))
    print()
    print(f"component scan: {summary['component_scan_ms']}ms")
    print(f"plugin load:    {summary['plugin_loaded_ms']}ms")
    print(f"components:     {summary['component_count']}")

    if args.baseline:
        base_timings = parse_log(Path(args.baseline).read_text(encoding="utf-8", errors="replace"))
        base_scan = base_timings.get("component scan", 0)
        print()
        print(f"baseline component scan: {base_scan}ms "
              f"(delta {(summary['component_scan_ms'] - base_scan):+d}ms)")

    scan = summary["component_scan_ms"]
    if scan > args.threshold:
        print()
        print(f"FAIL: component scan {scan}ms exceeds threshold {args.threshold}ms")
        return 1

    print(f"PASS: component scan {scan}ms within threshold {args.threshold}ms")
    return 0


if __name__ == "__main__":
    sys.exit(main())