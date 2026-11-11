"""Read-only scan of a PE executable: finds every RIP-relative LEA in executable sections that
loads the address of a NUL-terminated ASCII string, and prints, around the references to given
anchor strings, the strings referenced in code order (with the instruction's address).

This is method D4 of research/hauptwerk-gain-pan-report.md, used again in
research/release-transition-report.md. Nothing is executed, patched or copied: the file is only
read, and string addresses are matched.

Usage: python -I lea_scan.py <exe> <anchor string>... [--window=0x1800]
"""
import re
import struct
import sys

path = sys.argv[1]
anchors = [a for a in sys.argv[2:] if not a.startswith("--")]
window = 0x1800
for a in sys.argv[2:]:
    if a.startswith("--window="):
        window = int(a.split("=", 1)[1], 0)

b = open(path, "rb").read()
pe = struct.unpack_from("<I", b, 0x3C)[0]
nsec = struct.unpack_from("<H", b, pe + 6)[0]
opt = struct.unpack_from("<H", b, pe + 20)[0]
secs = []
for i in range(nsec):
    o = pe + 24 + opt + i * 40
    name = b[o:o + 8].rstrip(b"\0").decode()
    vsize, rva, rawsize, rawptr = struct.unpack_from("<IIII", b, o + 8)
    chars = struct.unpack_from("<I", b, o + 36)[0]
    secs.append((name, rva, vsize, rawptr, rawsize, chars))


def rva_to_off(rva):
    for name, srva, vsize, rawptr, rawsize, chars in secs:
        if srva <= rva < srva + max(vsize, rawsize):
            off = rawptr + (rva - srva)
            return off if off < rawptr + rawsize else None
    return None


def string_at(rva):
    off = rva_to_off(rva)
    if off is None:
        return None
    end = b.find(b"\0", off, off + 200)
    if end - off < 3:
        return None
    s = b[off:end]
    if all(0x20 <= c < 0x7F for c in s):
        return s.decode()
    return None


refs = []  # (instruction rva, string)
for name, srva, vsize, rawptr, rawsize, chars in secs:
    if not chars & 0x20000000:  # executable sections only
        continue
    code = b[rawptr:rawptr + rawsize]
    # REX.W/REX.WR + 8D (LEA) + ModRM with mod=00, rm=101: [rip + disp32]
    for m in re.finditer(rb"[\x48\x4c]\x8d[\x05\x0d\x15\x1d\x25\x2d\x35\x3d]", code):
        i = m.start()
        disp = struct.unpack_from("<i", code, i + 3)[0]
        insn_rva = srva + i
        target = insn_rva + 7 + disp
        s = string_at(target)
        if s is not None:
            refs.append((insn_rva, s))
refs.sort()
print(f"{len(refs)} string-loading LEAs")
for anchor in anchors:
    sites = [r for r, s in refs if s == anchor]
    print(f"\n### anchor {anchor!r}: referenced at {[hex(x) for x in sites]}")
    for site in sites:
        print(f"--- around 0x{site:x}")
        for r, s in refs:
            if site - window <= r <= site + window:
                print(f"  0x{r:08x}{' <==' if r == site else '    '} {s}")
