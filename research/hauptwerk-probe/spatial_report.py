"""Joins a spatial-probe design (note -> X/Y/Z) with analyze_audio.py results and prints, per pipe,
the absolute channel levels and derived quantities. All pipes play the same sample at the same
speed with neutral voicing, so level differences between pipes come from position alone.

Columns: L dB, R dB (window RMS, dBFS), L-R (block median), power = 10log(L^2+R^2),
sum = 20log(L+R) (amplitudes), all relative where noted to the pipe at the reference position.

Usage: python -I spatial_report.py <design.csv> <analysis.csv> [<analysis.csv> ...]
"""
import csv
import math
import sys

design = {int(r['note']): (float(r['x_m']), float(r['y_m']), float(r['z_m'])) for r in csv.DictReader(open(sys.argv[1]))}
takes = []
for p in sys.argv[2:]:
    takes.append({int(r['note']): r for r in csv.DictReader(open(p)) if r.get('matched') == 'True'})

ref = takes[0]
print(f'{"note":>4} {"X":>6} {"Y":>6} {"Z":>6} | {"L dB":>8} {"R dB":>8} {"L-R":>8} {"power":>8} {"ampsum":>8}' +
      ''.join(f' | take{k + 2} L-R  dPow' for k in range(len(takes) - 1)))
for n in sorted(design):
    if n not in ref:
        print(f'{n:>4} not matched')
        continue
    r = ref[n]
    x, y, z = design[n]
    l, rr = 10 ** (float(r['rms_l_db']) / 20), 10 ** (float(r['rms_r_db']) / 20)
    power = 10 * math.log10(l * l + rr * rr)
    amp = 20 * math.log10(l + rr)
    extra = ''
    for t in takes[1:]:
        if n in t:
            l2, r2 = 10 ** (float(t[n]['rms_l_db']) / 20), 10 ** (float(t[n]['rms_r_db']) / 20)
            extra += f' | {float(t[n]["block_l_minus_r_median_db"]) - float(r["block_l_minus_r_median_db"]):+8.4f} {10 * math.log10(l2 * l2 + r2 * r2) - power:+6.3f}'
    print(f'{n:>4} {x:+6.2f} {y:6.2f} {z:+6.2f} | {float(r["rms_l_db"]):8.3f} {float(r["rms_r_db"]):8.3f} '
          f'{float(r["block_l_minus_r_median_db"]):+8.4f} {power:8.3f} {amp:8.3f}{extra}')
