"""Lists note and controller events (with times in seconds) from a standard MIDI file.

Usage: python -I midi_events.py <file.mid>
"""
import struct
import sys


def events(path):
    d = open(path, 'rb').read()
    if d[:4] != b'MThd':
        raise SystemExit('not a MIDI file')
    _, ntracks, division = struct.unpack('>HHH', d[8:14])
    out = []
    i = 14
    for _ in range(ntracks):
        n = struct.unpack('>I', d[i + 4:i + 8])[0]
        trk = d[i + 8:i + 8 + n]
        i += 8 + n
        p = tick = status = 0
        tempo = 500000  # us per quarter note until a tempo event
        seconds = 0.0
        while p < len(trk):
            v = 0
            while True:
                b = trk[p]
                p += 1
                v = (v << 7) | (b & 0x7F)
                if b < 0x80:
                    break
            tick += v
            seconds += v * tempo / 1e6 / division
            b = trk[p]
            if b == 0xFF:
                typ, ln = trk[p + 1], trk[p + 2]
                if typ == 0x51:
                    tempo = int.from_bytes(trk[p + 3:p + 6], 'big')
                p += 3 + ln
                continue
            if b in (0xF0, 0xF7):
                ln = trk[p + 1]
                p += 2 + ln
                continue
            if b >= 0x80:
                status = b
                p += 1
            hi, ch = status & 0xF0, (status & 0x0F) + 1
            if hi in (0x80, 0x90, 0xA0, 0xB0, 0xE0):
                a1, a2 = trk[p], trk[p + 1]
                p += 2
                if hi == 0x90 and a2 > 0:
                    out.append((seconds, 'on', ch, a1, a2))
                elif hi in (0x80, 0x90):
                    out.append((seconds, 'off', ch, a1, a2))
                elif hi == 0xB0:
                    out.append((seconds, 'cc', ch, a1, a2))
            else:
                p += 1
    return sorted(out)


if __name__ == '__main__':
    for t, kind, ch, a1, a2 in events(sys.argv[1]):
        print(f'{t:9.3f} s  {kind:<3} ch {ch:2d}  {a1:3d}  {a2:3d}')
