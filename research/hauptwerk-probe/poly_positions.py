"""Lists, for the ranks behind one stop of an organ definition, each note's pipe X/Y/Z (tags n/p/q),
level (layer h) and stereo balance (layer r, rule-derived), and whether all those ranks agree.

Usage: python -I poly_positions.py <organ.xml> <stop name substring> [out.csv]
"""
import csv
import re
import sys
from collections import defaultdict

src = open(sys.argv[1], encoding='utf-8').read()


def table(name):
    m = re.search(r'<ObjectList ObjectType="%s">(.*?)</ObjectList>' % name, src, re.S)
    return [dict(re.findall(r'<([a-z][a-z0-9]*)>([^<]*)</\1>', o)) for o in re.findall(r'<o>(.*?)</o>', m.group(1), re.S)] if m else []


stops = [s for s in table('Stop') if sys.argv[2].lower() in s.get('b', '').lower()]
stop_ids = {s['a'] for s in stops}
ranks = {r['d'] for r in table('StopRank') if r.get('a') in stop_ids}
layers = {l['b']: l for l in table('Pipe_SoundEngine01_Layer')}
by_note = defaultdict(list)
for p in table('Pipe_SoundEngine01'):
    if p.get('b') in ranks:
        lay = layers.get(p['a'], {})
        f = lambda d, k: float(d.get(k, 0))
        by_note[int(float(p.get('d', 60)))].append((f(p, 'n'), f(p, 'p'), f(p, 'q'), f(lay, 'h'), f(lay, 'r')))
print(f'stops {[s["b"] for s in stops]} -> {len(ranks)} ranks')
rows = []
for note in sorted(by_note):
    vals = by_note[note]
    agree = len(set(vals)) == 1
    x, y, z, h, r = vals[0]
    rows.append({'note': note, 'pipes': len(vals), 'x_m': x, 'y_m': y, 'z_m': z, 'level_db': h, 'balance_pct': r, 'ranks_agree': agree})
print('all ranks agree on every note:', all(r['ranks_agree'] for r in rows))
print(' '.join(f"{r['note']}:{r['x_m']:+.3f}" for r in rows))
print('distinct Y:', sorted({r['y_m'] for r in rows}), ' Z:', sorted({r['z_m'] for r in rows}),
      ' level dB:', sorted({r['level_db'] for r in rows}), ' balance:', sorted({r['balance_pct'] for r in rows}))
if len(sys.argv) > 3:
    with open(sys.argv[3], 'w', newline='') as f:
        w = csv.DictWriter(f, fieldnames=list(rows[0]))
        w.writeheader()
        w.writerows(rows)
