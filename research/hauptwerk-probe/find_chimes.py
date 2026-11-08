"""Finds stretches where both channels are bit-identical and non-zero (a centred, unpanned sound)
in a recording, and reports their start times, durations, spacing and level.

Usage: python -I find_chimes.py <take.wav>
"""
import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))  # -I omits the script folder
from analyze_take import read_wav  # noqa: E402

rate, L, R = read_wav(sys.argv[1])
block = rate // 100
events = []
run_start = None
for k in range(len(L) // block):
    a, b = k * block, (k + 1) * block
    seg_l, seg_r = L[a:b], R[a:b]
    loud = any(seg_l) or any(seg_r)
    identical = loud and all(x == y for x, y in zip(seg_l, seg_r))
    if identical and run_start is None:
        run_start = k
    elif not identical and run_start is not None:
        events.append((run_start, k))
        run_start = None
print(f'{os.path.basename(sys.argv[1])}: {len(events)} identical-channel stretches')
prev = None
for s, e in events:
    a, b = s * block, e * block
    level = math.sqrt(sum(x * x for x in L[a:b]) / (b - a)) / 32768
    gap = '' if prev is None else f'  (+{(s - prev) / 100:.2f} s)'
    print(f'  {s / 100:8.2f} s for {(e - s) / 100:.2f} s, {20 * math.log10(level):.1f} dBFS{gap}')
    prev = s
