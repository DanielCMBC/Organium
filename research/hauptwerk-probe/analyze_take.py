"""Measures each held note of a recorded take: left/right RMS and peak in a steady-state window.

The window starts WINDOW_START s after the MIDI note-on (attack transient over) and lasts
WINDOW_LENGTH s (before the key is released). A "silence" window before the first note gives the
noise floor. Output: one CSV row per note.

Usage: python -I analyze_take.py <take.wav> <take.mid> <out.csv>
"""
import array
import csv
import math
import os
import struct
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))  # -I omits the script folder
from midi_events import events  # noqa: E402

WINDOW_START = 0.8
WINDOW_LENGTH = 1.5


def read_wav(path):
    d = open(path, 'rb').read()
    i, fmt, data = 12, None, None
    while i + 8 <= len(d):
        cid, n = d[i:i + 4], struct.unpack('<I', d[i + 4:i + 8])[0]
        if cid == b'fmt ':
            fmt = struct.unpack('<HHIIHH', d[i + 8:i + 24])
        elif cid == b'data':
            data = d[i + 8:i + 8 + n]
        i += 8 + n + (n & 1)
    tag, channels, rate, _, align, bits = fmt
    if channels != 2 or (tag, bits) not in ((1, 16), (1, 24), (1, 32), (3, 32), (0xFFFE, 24), (0xFFFE, 32)):
        raise SystemExit(f'expected stereo 16/24-bit PCM or 32-bit float, got format {tag}, {channels} ch, {bits} bit')
    data = data[:len(data) // align * align]
    if bits == 16:
        a = array.array('h')
        a.frombytes(data)
    elif bits == 24:  # rescaled to 16-bit units (floats), so callers' /32768 still gives full scale
        a = array.array('d', (int.from_bytes(data[k:k + 3], 'little', signed=True) / 256.0
                              for k in range(0, len(data), 3)))
    elif tag == 3:
        f = array.array('f')
        f.frombytes(data)
        a = array.array('d', (v * 32768.0 for v in f))
    else:
        i32 = array.array('i')
        i32.frombytes(data)
        a = array.array('d', (v / 65536.0 for v in i32))
    return rate, a[0::2], a[1::2]


def stats(x, start, end):
    seg = x[start:end]
    rms = math.sqrt(sum(v * v for v in seg) / len(seg)) / 32768
    peak = max(abs(v) for v in seg) / 32768
    return rms, peak


def db(v):
    return 20 * math.log10(v) if v > 0 else float('-inf')


def main():
    rate, left, right = read_wav(sys.argv[1])
    ons = [(t, ch, n) for t, kind, ch, n, v in events(sys.argv[2]) if kind == 'on']
    rows = []
    s0 = int(max(0.0, ons[0][0] - 1.0) * rate)
    floor_l, _ = stats(left, 0, max(s0, 1))
    floor_r, _ = stats(right, 0, max(s0, 1))
    for k, (t, ch, note) in enumerate(ons):
        a = int((t + WINDOW_START) * rate)
        b = a + int(WINDOW_LENGTH * rate)
        rl, pl = stats(left, a, b)
        rr, pr = stats(right, a, b)
        rows.append({'index': k, 'channel': ch, 'note': note, 'onset_s': round(t, 3),
                     'rms_l': rl, 'rms_r': rr, 'peak_l': pl, 'peak_r': pr,
                     'rms_l_db': round(db(rl), 3), 'rms_r_db': round(db(rr), 3),
                     'peak_l_db': round(db(pl), 3), 'peak_r_db': round(db(pr), 3),
                     'r_minus_l_db': round(db(rr) - db(rl), 3) if rl > 0 and rr > 0 else None,
                     'power_db': round(10 * math.log10(rl * rl + rr * rr), 3) if rl or rr else None})
    with open(sys.argv[3], 'w', newline='') as f:
        w = csv.DictWriter(f, fieldnames=list(rows[0]))
        w.writeheader()
        w.writerows(rows)
    print(f'{os.path.basename(sys.argv[1])}: {len(rows)} notes, {rate} Hz; noise floor '
          f'L {db(floor_l):.1f} dBFS, R {db(floor_r):.1f} dBFS')
    for r in rows:
        print(f"  #{r['index']:2d} ch{r['channel']} note {r['note']:3d}: L {r['rms_l_db']:8.3f}  R {r['rms_r_db']:8.3f} dB rms"
              f"  (R-L {r['r_minus_l_db']}, power {r['power_db']})  peaks L {r['peak_l_db']:.2f} R {r['peak_r_db']:.2f}")


if __name__ == '__main__':
    main()
