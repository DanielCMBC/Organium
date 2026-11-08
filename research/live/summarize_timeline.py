"""Summarizes a LiveStress timeline.csv per section: peak voices and pipes, worst render time and
callback gap, overruns and gaps, audio-thread allocation, GC, heap and free RAM.

Usage: python -I summarize_timeline.py <timeline.csv> [<jvm.log>]
"""
import csv
import re
import sys
from collections import OrderedDict

rows = list(csv.DictReader(open(sys.argv[1])))
by = OrderedDict()
prev = None
for r in rows:
    s = by.setdefault(r['section'], {'secs': 0, 'voices': 0, 'pipes': 0, 'render': 0.0, 'gap': 0.0,
                                     'over': 0, 'gaps': 0, 'alloc': 0, 'gc': 0, 'heap': 0, 'free': 10 ** 9})
    s['secs'] += 1
    s['voices'] = max(s['voices'], int(r['voices']))
    s['pipes'] = max(s['pipes'], int(r['sounding_pipes']))
    s['render'] = max(s['render'], float(r['max_render_ms']))
    s['gap'] = max(s['gap'], float(r['max_gap_ms']))
    s['heap'] = max(s['heap'], int(r['heap_used_mb']))
    s['free'] = min(s['free'], int(r['free_ram_mb']))
    if prev:
        s['over'] += int(r['overruns']) - int(prev['overruns'])
        s['gaps'] += int(r['gaps']) - int(prev['gaps'])
        s['alloc'] += int(r['audio_alloc_bytes']) - int(prev['audio_alloc_bytes'])
        s['gc'] += int(r['gc_count']) - int(prev['gc_count'])
    prev = r
print(f"{'section':<16}{'s':>4}{'voices':>8}{'pipes':>7}{'render ms':>11}{'gap ms':>9}{'overruns':>9}{'gaps':>6}"
      f"{'alloc B':>9}{'GCs':>5}{'heap MB':>9}{'free MB':>9}")
for name, s in by.items():
    print(f"{name:<16}{s['secs']:>4}{s['voices']:>8}{s['pipes']:>7}{s['render']:>11.3f}{s['gap']:>9.2f}{s['over']:>9}"
          f"{s['gaps']:>6}{s['alloc']:>9}{s['gc']:>5}{s['heap']:>9}{s['free']:>9}")

if len(sys.argv) > 2:
    pauses = []
    for line in open(sys.argv[2], encoding='utf-8', errors='replace'):
        m = re.search(r'\[([\d.]+)s\].*?Pause ([^(]+?)(?: \(.*?\))*.*? ([\d.]+)ms', line)
        if m and 'Pause' in line:
            pauses.append((float(m.group(1)), m.group(2).strip(), float(m.group(3))))
        m2 = re.search(r'\[([\d.]+)s\]\[info\]\[safepoint\].*?Total: (\d+) ns', line)
        if m2:
            pauses.append((float(m2.group(1)), 'safepoint', int(m2.group(2)) / 1e6))
    gc = [p for p in pauses if p[1] != 'safepoint']
    sp = [p for p in pauses if p[1] == 'safepoint']
    print(f'\nGC pauses: {len(gc)}' + (f", longest {max(p[2] for p in gc):.2f} ms" if gc else ''))
    for p in sorted(gc, key=lambda p: -p[2])[:5]:
        print(f'   at {p[0]:8.1f} s  {p[1]:<40} {p[2]:.2f} ms')
    if sp:
        print(f'safepoints: {len(sp)}, longest {max(p[2] for p in sp):.2f} ms, total {sum(p[2] for p in sp):.1f} ms')
