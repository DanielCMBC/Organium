"""Builds the empirical X/Y pan table from the grid takes and validates it on held-out points.

Pan parameter p in [-1, 1] (from the measured constant-sum law gL = 1 - p, gR = 1 + p):
p = (1 - r) / (1 + r), r = gL / gR = 10^(L-R dB / 20), using the block-median L-R.

Table: p on X_NODES (X >= 0; p(0, Y) = 0 is added as a column) x Y_NODES. p(-X, Y) = -p(X, Y).
Y = 0 is a measured row of its own and is used only for Y exactly 0; for Y != 0, interpolation
uses the rows on the same side of zero, so the Y = 0 special case never leaks into neighbours.
Outside the domain the coordinates are clamped to the edges (X to 4 m, Y to [-4, 8] m).

Validation: every measured point that is not a grid node (63 random test points, plus the earlier
line sweeps and the symmetry organ) is predicted and compared in per-channel gain (dB).

Usage: python -I build_pan_table.py   (writes analysis/pan-table.csv and analysis/pan-validation.csv)
"""
import csv
import glob
import math
import os

HERE = os.path.dirname(os.path.abspath(__file__))
NATURALS = [n for n in range(36, 92) if n % 12 in (0, 2, 4, 5, 7, 9, 11)]


def p_of(lr_db):
    r = 10 ** (lr_db / 20)
    return (1 - r) / (1 + r)


def measured():
    """All (x, y, p, source) points: grid organs (design files) and earlier sweeps (design csv)."""
    pts = []
    for k in range(15):
        if not os.path.exists(f'{HERE}/spatial/designs/grid-{k:02d}.csv'):
            continue
        design = list(csv.DictReader(open(f'{HERE}/spatial/designs/grid-{k:02d}.csv')))
        path = f'{HERE}/analysis/sp-grid-{k:02d}.csv'
        if not os.path.exists(path):
            continue
        res = {int(r['note']): r for r in csv.DictReader(open(path)) if r.get('matched') == 'True'}
        for note, d in zip(NATURALS, design):
            if note in res:
                pts.append((float(d['x_m']), float(d['y_m']), p_of(float(res[note]['block_l_minus_r_median_db'])), f'grid-{k:02d}'))
    for sweep, take in [('x-y1', 'x-y1'), ('x-y3', 'x-y3'), ('x-y6', 'x-y6'), ('y-x1', 'y-x1'), ('y-x0', 'y-x0'), ('sym-front-back', 'sym')]:
        design = {int(r['note']): r for r in csv.DictReader(open(f'{HERE}/spatial/OrganiumSpatial-{sweep}-design.csv'))}
        for r in csv.DictReader(open(f'{HERE}/analysis/sp-{take}-A.csv')):
            if r.get('matched') == 'True':
                d = design[int(r['note'])]
                pts.append((float(d['x_m']), float(d['y_m']), p_of(float(r['block_l_minus_r_median_db'])), sweep))
    return pts


def load_nodes():
    import importlib.util
    spec = importlib.util.spec_from_file_location('g', f'{HERE}/make_grid_designs.py')
    src = open(f'{HERE}/make_grid_designs.py').read()
    ns = {}
    exec(src.split('def main')[0], ns)
    extra = {}
    exec(open(f'{HERE}/make_grid_extra.py').read().split('def main')[0].replace("ns = {}", ''), extra)
    return sorted([0.0] + ns['X_NODES'] + extra['EXTRA_X']), ns['Y_NODES']


def bracket(nodes, v):
    """Index i with nodes[i] <= v <= nodes[i+1] (clamped), and the fraction."""
    if v <= nodes[0]:
        return 0, 0.0
    if v >= nodes[-1]:
        return len(nodes) - 2, 1.0
    for i in range(len(nodes) - 1):
        if nodes[i] <= v <= nodes[i + 1]:
            return i, (v - nodes[i]) / (nodes[i + 1] - nodes[i])


def cubic_1d(nodes, vals, v):
    """Monotone cubic Hermite (Fritsch-Carlson) on non-uniform nodes; clamped outside."""
    v = min(max(v, nodes[0]), nodes[-1])
    n = len(nodes)
    h = [nodes[i + 1] - nodes[i] for i in range(n - 1)]
    s = [(vals[i + 1] - vals[i]) / h[i] for i in range(n - 1)]
    m = [s[0]] + [0.0] * (n - 2) + [s[-1]]
    for i in range(1, n - 1):
        if s[i - 1] * s[i] <= 0:
            m[i] = 0.0
        else:
            w1, w2 = 2 * h[i] + h[i - 1], h[i] + 2 * h[i - 1]
            m[i] = (w1 + w2) / (w1 / s[i - 1] + w2 / s[i])
    i, t = bracket(nodes, v)
    t2, t3 = t * t, t * t * t
    return ((2 * t3 - 3 * t2 + 1) * vals[i] + (t3 - 2 * t2 + t) * h[i] * m[i]
            + (-2 * t3 + 3 * t2) * vals[i + 1] + (t3 - t2) * h[i] * m[i + 1])


class Table:
    def __init__(self, xs, ys, grid):
        self.xs, self.ys, self.grid = xs, ys, grid   # grid[iy][ix]
        self.neg = [i for i, y in enumerate(ys) if y < 0]
        self.pos = [i for i, y in enumerate(ys) if y > 0]
        self.zero = ys.index(0.0)

    def p(self, x, y, method):
        sign = -1.0 if x < 0 else 1.0
        x = min(abs(x), self.xs[-1])
        if y == 0.0:
            rows = [self.zero]
        else:
            rows = self.neg if y < 0 else self.pos
        ys = [self.ys[i] for i in rows]
        if method == 'bilinear':
            cols = [self._row_interp_lin(self.grid[i], x) for i in rows]
            if len(rows) == 1:
                return sign * cols[0]
            j, t = bracket(ys, min(max(y, ys[0]), ys[-1]))
            return sign * (cols[j] * (1 - t) + cols[j + 1] * t)
        cols = [cubic_1d(self.xs, self.grid[i], x) for i in rows]
        if len(rows) == 1:
            return sign * cols[0]
        return sign * cubic_1d(ys, cols, y)

    def _row_interp_lin(self, row, x):
        i, t = bracket(self.xs, x)
        return row[i] * (1 - t) + row[i + 1] * t


def gains_db(p):
    return 20 * math.log10(max(1e-9, 1 - p)), 20 * math.log10(1 + p)


def main():
    xs, ys = load_nodes()
    pts = measured()
    nodes = {}
    for x, y, p, src in pts:
        if src.startswith('grid') and x in xs and y in ys:
            nodes[(x, y)] = p
    missing = [(x, y) for y in ys for x in xs[1:] if (x, y) not in nodes]
    if missing:
        raise SystemExit(f'{len(missing)} grid nodes missing, e.g. {missing[:5]}')
    grid = [[0.0 if x == 0 else nodes[(x, y)] for x in xs] for y in ys]
    table = Table(xs, ys, grid)
    with open(f'{HERE}/analysis/pan-table.csv', 'w', newline='') as f:
        w = csv.writer(f)
        w.writerow(['y_m \\ x_m'] + xs)
        for y, row in zip(ys, grid):
            w.writerow([y] + [round(v, 7) for v in row])

    tests = [(x, y, p, src) for x, y, p, src in pts
             if not (src.startswith('grid') and (x, y) in nodes and abs(x) in xs)]
    inside = [t for t in tests if abs(t[0]) <= 4.0 and -4.0 <= t[1] <= 8.0]
    rows = []
    print(f'table {len(ys)} x {len(xs)} (Y x X); {len(tests)} held-out points, {len(inside)} inside the domain')
    for method in ('bilinear', 'cubic'):
        errs = []
        for x, y, p, src in inside:
            q = table.p(x, y, method)
            ml, mr = gains_db(p)
            pl, pr = gains_db(q)
            e = max(abs(pl - ml), abs(pr - mr))
            errs.append((e, x, y, src, p, q))
            rows.append([method, src, x, y, round(p, 6), round(q, 6), round(pl - ml, 4), round(pr - mr, 4)])
        rms = math.sqrt(sum(e[0] ** 2 for e in errs) / len(errs))
        print(f'  {method:<8}: per-channel gain error  rms {rms:.4f} dB   max {max(errs)[0]:.4f} dB')
        for e, x, y, src, p, q in sorted(errs, reverse=True)[:5]:
            print(f'      worst ({x:+.3f}, {y:+.3f}) {src:<14} measured p {p:+.5f}  table {q:+.5f}  err {e:.4f} dB')
        far = [e for e in errs if abs(e[2]) >= 0.5]
        if far:
            print(f'      |Y| >= 0.5 m only: rms {math.sqrt(sum(e[0] ** 2 for e in far) / len(far)):.4f}  max {max(far)[0]:.4f} dB ({len(far)} points)')
    with open(f'{HERE}/analysis/pan-validation.csv', 'w', newline='') as f:
        w = csv.writer(f)
        w.writerow(['method', 'source', 'x_m', 'y_m', 'p_measured', 'p_table', 'gain_l_err_db', 'gain_r_err_db'])
        w.writerows(rows)
    centre = [p for x, y, p, s in pts if x == 0]
    print(f'centre check: {len(centre)} points at X = 0, max |p| {max(abs(p) for p in centre):.2e}')
    pairs = {}
    for x, y, p, s in pts:
        pairs.setdefault((round(abs(x), 6), y), {})[x > 0] = p
    sym = [abs(v[True] + v[False]) for v in pairs.values() if True in v and False in v]
    print(f'antisymmetry check: {len(sym)} +-X pairs, max |p(X)+p(-X)| {max(sym):.2e}')


main()
