"""Measures held notes in a Hauptwerk recording, located from the audio itself.

Hauptwerk's recorded MIDI and WAV are not time-aligned (a ~2 s offset was observed), so notes are
found in the audio. A note onset is the first 10 ms block above THRESHOLD_DB after at least
MIN_GAP_S of silence. The first onset is the first held key; every later key is expected at the
first onset plus its logged hold time (play_sequence.ps1 log), and the nearest detected onset within
MATCH_S is used, so unrelated sounds between notes are never mistaken for a note.

Per note:
  sustain  onset + SUSTAIN_START .. + SUSTAIN_LENGTH (inside the hold)
  release  from key-up to key-up + RELEASE_SPAN: only blocks above THRESHOLD_DB are included

Usage: python -I analyze_audio.py <take.wav> <holds.csv> <notes, comma-separated, in hold order> <out.csv>
"""
import csv
import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))  # -I omits the script folder
from analyze_take import read_wav  # noqa: E402

THRESHOLD_DB = -100.0
MIN_GAP_S = 0.5
MATCH_S = 0.25
SUSTAIN_START = 0.5
SUSTAIN_LENGTH = 1.5
RELEASE_SPAN = 2.5
BLOCK_S = 0.01
CHIME_LENGTH = 0.22          # measured: 0.22 s every 7.00 s at -63.1 dBFS, identical in both channels
CHIME_MARGIN_BEFORE = 0.08
CHIME_MARGIN_AFTER = 0.08


def sumsq(x, a, b):
    return sum(v * v for v in x[a:b])


def db(v):
    return 20 * math.log10(v) if v > 0 else float('-inf')


def chime_grid(left, right, rate, length_s):
    """Intervals of Hauptwerk's periodic unpanned chime: stretches where both channels are
    bit-identical and non-zero (>= 0.15 s, seen between notes), extended to the whole take on the
    fitted period, since during notes the pipes hide the identity. Returns (period, phase, intervals)."""
    block = rate // 100
    starts, run = [], None
    for k in range(len(left) // block):
        a, b = k * block, (k + 1) * block
        sl, sr = left[a:b], right[a:b]
        same = (any(sl) or any(sr)) and all(x == y for x, y in zip(sl, sr))
        if same and run is None:
            run = k
        elif not same and run is not None:
            if 15 <= k - run <= 35:   # chime is 0.22 s; a centred (X = 0) pipe is also bit-identical but lasts the whole hold
                starts.append(run / 100)
            run = None
    if len(starts) < 2:
        return None, None, []
    # index each start on a ~7 s grid, then least-squares fit start = phase + k * period
    rough = 7.0
    ks = [round((s - starts[0]) / rough) for s in starts]
    n = len(ks)
    mk, ms = sum(ks) / n, sum(starts) / n
    var = sum((k - mk) ** 2 for k in ks)
    period = sum((k - mk) * (s - ms) for k, s in zip(ks, starts)) / var if var else rough
    first = ms - period * mk
    phase = first - period * math.floor(first / period)
    intervals = [(phase + k * period - CHIME_MARGIN_BEFORE, phase + k * period + CHIME_LENGTH + CHIME_MARGIN_AFTER)
                 for k in range(int(length_s / period) + 2)]
    return period, phase, intervals


def block_levels(left, right, rate):
    block = int(BLOCK_S * rate)
    out = []
    for k in range(len(left) // block):
        a, b = k * block, (k + 1) * block
        out.append((a, b, sumsq(left, a, b), sumsq(right, a, b)))
    return block, out


def main():
    wav, holds_csv, notes_arg, out = sys.argv[1:5]
    rate, left, right = read_wav(wav)
    holds = [(int(h['start_ms']) / 1000, int(h['end_ms']) / 1000) for h in csv.DictReader(open(holds_csv))]
    notes = [int(n) for n in notes_arg.split(',')]
    block, levels = block_levels(left, right, rate)
    loud = [db(math.sqrt(max(l, r) / block) / 32768) > THRESHOLD_DB for _, _, l, r in levels]

    period, phase, chimes = chime_grid(left, right, rate, len(left) / rate)
    in_chime = lambda t0, t1: any(t0 < c1 and t1 > c0 for c0, c1 in chimes)
    # chime blocks count as silence, so a chime neither starts a note nor hides a note's onset
    onsets, quiet = [], MIN_GAP_S
    for k, is_loud in enumerate(loud):
        if is_loud and not in_chime(k * BLOCK_S, (k + 1) * BLOCK_S):
            if quiet >= MIN_GAP_S:
                onsets.append(k * BLOCK_S)
            quiet = 0.0
        else:
            quiet += BLOCK_S
    if not onsets:
        raise SystemExit('no sound found')
    first = onsets[0]
    print(f'{os.path.basename(wav)}: {len(onsets)} onsets, {len(holds)} holds, {len(notes)} notes; '
          f'chime period {period} s, phase {phase} s')

    rows = []
    for k, ((h0, h1), note) in enumerate(zip(holds, notes)):
        expected = first + (h0 - holds[0][0])
        near = min(onsets, key=lambda t: abs(t - expected))
        if abs(near - expected) > MATCH_S:
            rows.append({'index': k, 'note': note, 'matched': False})
            continue
        # sustain: blocks in the window that don't overlap a chime
        j0 = int((near + SUSTAIN_START) / BLOCK_S)
        j1 = j0 + int(SUSTAIN_LENGTH / BLOCK_S)
        keep = [levels[j] for j in range(j0, j1) if not in_chime(j * BLOCK_S, (j + 1) * BLOCK_S)]
        n = len(keep) * block
        rl = math.sqrt(sum(x[2] for x in keep) / n) / 32768 if n else 0.0
        rr = math.sqrt(sum(x[3] for x in keep) / n) / 32768 if n else 0.0
        pl = max((abs(v) for x in keep for v in left[x[0]:x[1]]), default=0) / 32768
        pr = max((abs(v) for x in keep for v in right[x[0]:x[1]]), default=0) / 32768
        # per-block L/R ratio: robust to anything added equally to both channels in a few blocks
        ratios = sorted(10 * math.log10(x[2] / x[3]) for x in keep if x[2] > 0 and x[3] > 0)
        q = lambda f: ratios[min(len(ratios) - 1, int(f * len(ratios)))] if ratios else None
        # release: blocks above threshold between key-up and key-up + RELEASE_SPAN, chimes excluded
        up = near + (h1 - h0)
        k0, k1 = int(up / BLOCK_S), min(len(levels), int((up + RELEASE_SPAN) / BLOCK_S))
        active = [levels[j] for j in range(k0, k1) if loud[j] and not in_chime(j * BLOCK_S, (j + 1) * BLOCK_S)]
        nl = sum(x[2] for x in active)
        nr = sum(x[3] for x in active)
        cnt = len(active) * block
        rel_l = math.sqrt(nl / cnt) / 32768 if cnt else 0.0
        rel_r = math.sqrt(nr / cnt) / 32768 if cnt else 0.0
        first_active = next((j for j in range(k0, k1) if loud[j] and not in_chime(j * BLOCK_S, (j + 1) * BLOCK_S)), None)
        rows.append({'index': k, 'note': note, 'matched': True, 'onset_s': round(near, 3),
                     'match_error_s': round(near - expected, 3),
                     'sustain_used_s': round(len(keep) * BLOCK_S, 2),
                     'rms_l_db': round(db(rl), 3), 'rms_r_db': round(db(rr), 3),
                     'peak_l_db': round(db(pl), 3), 'peak_r_db': round(db(pr), 3),
                     'l_minus_r_db': round(db(rl) - db(rr), 3) if rl > 0 and rr > 0 else None,
                     'block_l_minus_r_median_db': round(q(0.5), 4) if ratios else None,
                     'block_l_minus_r_p10_db': round(q(0.1), 4) if ratios else None,
                     'block_l_minus_r_p90_db': round(q(0.9), 4) if ratios else None,
                     'power_db': round(10 * math.log10(rl * rl + rr * rr), 3) if (rl or rr) else None,
                     'release_delay_s': round(first_active * BLOCK_S - up, 3) if first_active is not None else None,
                     'release_active_s': round(len(active) * BLOCK_S, 3),
                     'release_l_minus_r_db': round(db(rel_l) - db(rel_r), 3) if rel_l > 0 and rel_r > 0 else None,
                     'release_rms_db': round(db(math.sqrt((rel_l ** 2 + rel_r ** 2) / 2)), 3) if cnt else None})
    fields = list(max(rows, key=len))
    with open(out, 'w', newline='') as f:
        w = csv.DictWriter(f, fieldnames=fields)
        w.writeheader()
        w.writerows(rows)
    for r in rows:
        if not r['matched']:
            print(f"  #{r['index']:2d} note {r['note']:3d}: NOT MATCHED")
            continue
        print(f"  #{r['index']:2d} note {r['note']:3d} at {r['onset_s']:7.2f}s (err {r['match_error_s']:+.2f}, used {r['sustain_used_s']} s): "
              f"L {r['rms_l_db']:7.2f} R {r['rms_r_db']:7.2f} L-R {r['l_minus_r_db']:+7.3f} "
              f"block median {r['block_l_minus_r_median_db']:+8.4f} [{r['block_l_minus_r_p10_db']:+.4f}, {r['block_l_minus_r_p90_db']:+.4f}] | "
              f"release after {r['release_delay_s']} s for {r['release_active_s']} s, L-R {r['release_l_minus_r_db']}")


if __name__ == '__main__':
    main()
