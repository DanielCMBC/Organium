"""Startup timing of one LiveStress run: when the first callback came, every interval between
callbacks over 1.5 periods (8 ms) with what the harness was doing around it, and the worst
interval in successive time windows.

Usage: python -I startup_gaps.py <run dir>
"""
import csv
import os
import sys

run = sys.argv[1]
cb = list(csv.DictReader(open(os.path.join(run, 'startup-callbacks.csv'))))
ev = [(float(r['t_ms']), r['event']) for r in csv.DictReader(open(os.path.join(run, 'startup-events.csv')))]
PERIOD = 256 / 48.0


def around(t0, t1):
    before = [e for e in ev if e[0] <= t0]
    inside = [e for e in ev if t0 < e[0] <= t1]
    return ([before[-1]] if before else []) + inside


t = [float(r['t_ms']) for r in cb]
gap = [float(r['interval_ms']) if r['interval_ms'] else 0.0 for r in cb]
ren = [float(r['render_ms']) for r in cb]
print(f"{os.path.basename(run)}: {len(cb)} callbacks traced, first at {t[0]:.1f} ms after ASIO start")
# garbage collections and safepoints from the JVM log, moved onto the ASIO-start clock
up0 = [(float(r['t_ms']), int(r['uptime_ms'])) for r in csv.DictReader(open(os.path.join(run, 'startup-events.csv')))
       if 'uptime_ms' in r and r['event'] == 'ASIO start called']
log = os.path.join(run, 'jvm.log')
if up0 and os.path.exists(log):
    import re
    base = up0[0][1] - up0[0][0]
    for line in open(log, encoding='utf-8', errors='replace'):
        m = re.match(r'\[([\d.]+)s\] (GC\(\d+\) \w+ Collection \([^)]*\)(?: .*? ([\d.]+)s)?)$', line.strip())
        if m:
            ev.append((float(m.group(1)) * 1000 - base, m.group(2)[:60]))
    ev.sort()
for e in ev:
    if e[0] < 1500 or 'priority' in e[1]:
        print(f"   event {e[0]:9.1f} ms  {e[1]}")
big = [i for i in range(1, len(cb)) if gap[i] > 1.5 * PERIOD]
print(f"intervals over {1.5 * PERIOD:.1f} ms: {len(big)}")
for i in big:
    ctx = '; '.join(f"{e[1]} @{e[0]:.1f}" for e in around(t[i - 1], t[i]))
    print(f"   #{i:5d} at {t[i - 1]:9.1f} -> {t[i]:9.1f} ms: {gap[i]:7.2f} ms "
          f"(render before {ren[i - 1]:.3f} ms, after {ren[i]:.3f} ms)  [{ctx}]")
for lo, hi in [(0, 100), (100, 500), (500, 1000), (1000, 5000), (5000, 1e9)]:
    w = [gap[i] for i in range(1, len(cb)) if lo <= t[i] < hi]
    if w:
        print(f"   window {lo:>5}-{hi if hi < 1e9 else 'end':>5} ms: {len(w):5d} intervals, worst {max(w):6.2f} ms")
