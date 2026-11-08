"""Generates src/organium/organ/spatial/HauptwerkPanData.java from analysis/pan-table.csv.

The Java file holds only the measured numbers; the lookup rules live in MeasuredPanTable.java.
Re-run after re-measuring: python -I export_pan_table.py
"""
import csv
import hashlib
import os

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, 'analysis', 'pan-table.csv')
OUT = os.path.join(HERE, '..', '..', 'src', 'organium', 'organ', 'spatial', 'HauptwerkPanData.java')

rows = list(csv.reader(open(SRC)))
xs = [float(v) for v in rows[0][1:]]
ys = [float(r[0]) for r in rows[1:]]
ps = [[float(v) for v in r[1:]] for r in rows[1:]]
digest = hashlib.sha256(open(SRC, 'rb').read()).hexdigest()[:16]


def arr(vals):
    return '{' + ', '.join(repr(v) for v in vals) + '}'


body = f'''package organium.organ.spatial;

/**
 * Pan parameter measured from Hauptwerk 4.2.1 (Free Edition), generated from
 * research/hauptwerk-probe/analysis/pan-table.csv (sha256 {digest}...) by export_pan_table.py.
 * Do not edit by hand. P[iy][ix] is the pan at (X[ix], Y[iy]) metres; X >= 0 only (the law is
 * antisymmetric in X). Measurement method: research/hauptwerk-gain-pan-report.md, section 3f.
 */
final class HauptwerkPanData {{
    static final double[] X = {arr(xs)};
    static final double[] Y = {arr(ys)};
    static final double[][] P = {{
''' + ''.join(f'            {arr(r)},\n' for r in ps) + '''    };

    private HauptwerkPanData() {
    }
}
'''
os.makedirs(os.path.dirname(OUT), exist_ok=True)
open(OUT, 'w', encoding='utf-8', newline='\n').write(body)
print(f'wrote {os.path.normpath(OUT)}: {len(ys)} x {len(xs)} values')
