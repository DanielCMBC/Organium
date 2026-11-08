"""Converts each measured pipe's L-R ratio to the pan parameter p of the measured constant-sum law
(gL ~ 1 - p, gR ~ 1 + p, so p = (1 - r)/(1 + r) with r = gL/gR), and lists p with the pipe's
distance d = sqrt(X^2 + Y^2) and angle theta = atan(X/Y), pooled over all sweeps.

Usage: python -I pan_parameter.py <design.csv>:<analysis.csv> [...]  > out.csv
"""
import csv
import math
import sys

w = csv.writer(sys.stdout)
w.writerow(['sweep', 'note', 'x_m', 'y_m', 'z_m', 'd_m', 'theta_deg', 'l_minus_r_db', 'p', 'p_over_sin2theta'])
for arg in sys.argv[1:]:
    dpath, apath = arg.split(':')
    design = {int(r['note']): r for r in csv.DictReader(open(dpath))}
    for r in csv.DictReader(open(apath)):
        if r.get('matched') != 'True':
            continue
        n = int(r['note'])
        x, y, z = (float(design[n][k]) for k in ('x_m', 'y_m', 'z_m'))
        lr = float(r['block_l_minus_r_median_db'])
        ratio = 10 ** (lr / 20)
        p = (1 - ratio) / (1 + ratio)
        t = math.atan2(x, y)
        s2 = math.sin(2 * t)
        w.writerow([dpath.split('-', 1)[1].replace('-design.csv', ''), n, x, y, z, round(math.hypot(x, y), 4),
                    round(math.degrees(t), 3), lr, round(p, 6), round(p / s2, 6) if abs(s2) > 1e-9 else ''])
