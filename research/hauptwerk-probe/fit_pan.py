"""Compares measured left/right level differences with pipe positions and fits candidate
position-to-stereo-gain models, none of which is assumed in advance.

Inputs: analyze_audio.py CSVs (block-median L-R per note), the note -> X/Y table from
poly_positions.py, and the source sample files (to relate recorded level to sample level).

Usage: python -I fit_pan.py <positions.csv> <samples folder> <take.csv> [<take.csv> ...]
"""
import array
import csv
import math
import struct
import sys

LN10_20 = 20 / math.log(10)


def load_takes(paths):
    takes = {}
    for p in paths:
        rows = {}
        for r in csv.DictReader(open(p)):
            if r.get('matched') == 'True' and r.get('block_l_minus_r_median_db'):
                rows[int(r['note'])] = r
        takes[p] = rows
    return takes


def sample_sustain_rms(folder, note):
    """RMS of the sample's sustain loop (smpl chunk), the part a held note repeats."""
    names = {36: 'C', 37: 'C#', 38: 'D', 39: 'D#', 40: 'E', 41: 'F', 42: 'F#', 43: 'G', 44: 'G#', 45: 'A', 46: 'A#', 47: 'B'}
    path = f'{folder}/{note:03d}-{names[36 + (note - 36) % 12]}.wav'
    d = open(path, 'rb').read()
    i = d.find(b'smpl')
    loop_start, loop_end = struct.unpack('<II', d[i + 8 + 44:i + 8 + 52])
    j = d.find(b'data')
    n = struct.unpack('<I', d[j + 4:j + 8])[0]
    a = array.array('h')
    a.frombytes(d[j + 8:j + 8 + n])
    seg = a[loop_start:loop_end + 1]
    return math.sqrt(sum(v * v for v in seg) / len(seg)) / 32768


# ---- candidate models: each maps (X, Y, params) -> predicted L-R in dB; none is assumed -------------
def m_linear_pan(x, y, w):            # gL = 1 - x/w, gR = 1 + x/w
    p = x / w
    return LN10_20 * math.log((1 - p) / (1 + p)) if abs(p) < 1 else float('nan')


def m_const_power(x, y, w):           # gL = cos(pi/4 (1 + x/w)), gR = sin(...)
    t = math.pi / 4 * (1 + x / w)
    return LN10_20 * math.log(math.cos(t) / math.sin(t)) if 0 < t < math.pi / 2 else float('nan')


def m_const_power_angle(x, y, d):     # as above with p = sin(atan(x / d))
    p = x / math.hypot(x, d)
    t = math.pi / 4 * (1 + p)
    return LN10_20 * math.log(math.cos(t) / math.sin(t))


def m_linear_pan_angle(x, y, d):      # linear pan with p = sin(atan(x / d))
    p = x / math.hypot(x, d)
    return LN10_20 * math.log((1 - p) / (1 + p))


def m_two_speakers(x, y, s, a):       # gain ~ distance^-a to speakers at (-s, 0) and (+s, 0), pipe at (x, y)
    dl, dr = math.hypot(x + s, y), math.hypot(x - s, y)
    return a * LN10_20 * math.log(dr / dl)


def m_scaled_angle(x, d, c, constant_power):  # p = c * sin(atan(x / d)), linear or sin/cos law
    p = c * x / math.hypot(x, d)
    if not -1 < p < 1:
        return float('nan')
    if constant_power:
        t = math.pi / 4 * (1 + p)
        return LN10_20 * math.log(math.cos(t) / math.sin(t))
    return LN10_20 * math.log((1 - p) / (1 + p))


def m_db_per_metre(x, y, k):          # L-R linear in X
    return -k * x


MODELS = [
    ('linear pan, p = X/W', m_linear_pan, [(0.5, 50.0)]),
    ('constant-power sin/cos, p = X/W', m_const_power, [(2.2, 50.0)]),
    ('constant-power, p = sin(atan(X/D))', m_const_power_angle, [(0.05, 50.0)]),
    ('linear pan, p = sin(atan(X/D))', m_linear_pan_angle, [(0.05, 50.0)]),
    ('inverse distance (a=1) to speakers at +-S', lambda x, y, s: m_two_speakers(x, y, s, 1.0), [(0.05, 20.0)]),
    ('inverse square (a=2) to speakers at +-S', lambda x, y, s: m_two_speakers(x, y, s, 2.0), [(0.05, 20.0)]),
    ('distance^-a to speakers at +-S', m_two_speakers, [(0.05, 20.0), (0.2, 6.0)]),
    ('dB linear in X', m_db_per_metre, [(0.1, 20.0)]),
    # further two-parameter families, to test whether a good two-parameter fit is unique
    ('linear pan, p = c X/sqrt(X^2+D^2)', lambda x, y, c, d: m_scaled_angle(x, d, c, False), [(0.05, 1.0), (0.05, 20.0)]),
    ('constant-power, p = c X/sqrt(X^2+D^2)', lambda x, y, c, d: m_scaled_angle(x, d, c, True), [(0.05, 1.0), (0.05, 20.0)]),
    ('dB = -k atan(X/D)', lambda x, y, k, d: -k * math.atan(x / d), [(0.2, 50.0), (0.05, 20.0)]),
]


def fit(model, bounds, data):
    """Least squares by coarse grid then coordinate refinement (no external libraries)."""
    def cost(params):
        s = 0.0
        for x, y, obs in data:
            pred = model(x, y, *params)
            if pred != pred:  # nan: parameter outside the model's valid range for this X
                return float('inf')
            s += (pred - obs) ** 2
        return s
    grids = [[lo * (hi / lo) ** (k / 60) for k in range(61)] for lo, hi in bounds]
    best = None
    if len(bounds) == 1:
        for p in grids[0]:
            c = cost([p])
            if best is None or c < best[0]:
                best = (c, [p])
    else:
        for p in grids[0]:
            for q in grids[1]:
                c = cost([p, q])
                if best is None or c < best[0]:
                    best = (c, [p, q])
    c, params = best
    step = [p * 0.05 for p in params]
    for _ in range(200):
        improved = False
        for i in range(len(params)):
            for sgn in (1, -1):
                trial = list(params)
                trial[i] += sgn * step[i]
                ct = cost(trial)
                if ct < c:
                    c, params, improved = ct, trial, True
        if not improved:
            step = [s * 0.5 for s in step]
            if max(step) < 1e-7:
                break
    residuals = [model(x, y, *params) - obs for x, y, obs in data]
    return params, math.sqrt(sum(r * r for r in residuals) / len(residuals)), max(abs(r) for r in residuals)


def main():
    positions = {int(r['note']): (float(r['x_m']), float(r['y_m'])) for r in csv.DictReader(open(sys.argv[1]))}
    folder = sys.argv[2]
    takes = load_takes(sys.argv[3:])
    names = list(takes)

    print('== Repeatability (block-median L-R, dB)')
    for a, b in zip(names, names[1:]):
        common = sorted(set(takes[a]) & set(takes[b]))
        d = [float(takes[a][n]['block_l_minus_r_median_db']) - float(takes[b][n]['block_l_minus_r_median_db']) for n in common]
        print(f'  {a.split("/")[-1]} - {b.split("/")[-1]}: {len(d)} notes, mean {sum(d) / len(d):+.4f}, '
              f'max |diff| {max(abs(v) for v in d):.4f}')

    ref = names[-1]
    data = [(positions[n][0], positions[n][1], float(r['block_l_minus_r_median_db'])) for n, r in sorted(takes[ref].items())]
    print(f'\n== Model fits to {ref.split("/")[-1]} ({len(data)} notes, X from {min(d[0] for d in data):+.3f} to {max(d[0] for d in data):+.3f} m, Y = {sorted({d[1] for d in data})})')
    results = []
    for label, model, bounds in MODELS:
        params, rms, worst = fit(model, bounds, data)
        results.append((rms, label, params, worst))
    for rms, label, params, worst in sorted(results):
        print(f'  rms {rms:8.4f} dB  max {worst:8.4f} dB  {label}  params {[round(p, 5) for p in params]}')

    print('\n== Mirror symmetry: |L-R| vs |X| for neighbouring + and - positions')
    for x, y, obs in data[:6]:
        print(f'  X {x:+.3f}: L-R {obs:+.4f}')

    print('\n== Recorded level relative to the source sample (sustain-loop RMS), per note')
    print('   note      X      L-R    gain_L dB  gain_R dB  power gain dB  amplitude-sum gain dB')
    for n, r in sorted(takes[ref].items()):
        s = sample_sustain_rms(folder, n)
        gl = float(r['rms_l_db']) - 20 * math.log10(s)
        gr = float(r['rms_r_db']) - 20 * math.log10(s)
        power = 10 * math.log10(10 ** (gl / 10) + 10 ** (gr / 10))
        amp = 20 * math.log10(10 ** (gl / 20) + 10 ** (gr / 20))
        print(f'   {n:3d}  {positions[n][0]:+6.3f}  {float(r["block_l_minus_r_median_db"]):+7.3f}  {gl:9.3f}  {gr:9.3f}  {power:12.3f}  {amp:12.3f}')


main()
