"""Aligns a calibration recording's MIDI note-ons with logged click times, giving x -> (channel, note).

Usage: python -I align_calibration.py <clicks.csv> <recording.mid>
"""
import csv
import os
import sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))  # -I omits the script folder
from midi_events import events  # noqa: E402

clicks = [(int(r['x']), int(r['start_ms']) / 1000, int(r['end_ms']) / 1000) for r in csv.DictReader(open(sys.argv[1]))]
ons = [(t, ch, n) for t, kind, ch, n, v in events(sys.argv[2]) if kind == 'on']
best = None
for k in range(len(clicks)):  # assume the first note-on came from click k
    off = ons[0][0] - clicks[k][1]
    matched = sum(1 for t, ch, n in ons if any(abs(t - off - c[1]) < 0.06 for c in clicks))
    if best is None or matched > best[0]:
        best = (matched, off)
matched, off = best
print(f'{len(ons)} note-ons, {len(clicks)} clicks, {matched} aligned (offset {off:.3f} s)')
rows = []
for x, t0, t1 in clicks:
    hit = [(ch, n) for t, ch, n in ons if abs(t - off - t0) < 0.06]
    rows.append((x, hit[0] if hit else None))
    print(f'  x {x:5d}: ' + (f'ch {hit[0][0]} note {hit[0][1]}' if hit else '-- no key'))
