"""Summarizes a snapshot.py diff listing by folder (package folders collapsed)."""
import collections, re, sys
BS = chr(92)
lines = open(sys.argv[1], encoding='utf-8').read().splitlines()
section, groups, other, exts = None, collections.Counter(), [], collections.Counter()
for l in lines:
    if l.startswith('== '):
        section = l[3:].split(':')[0]
        continue
    m = re.match(r'\s+(C:.*?)  \((\d+) bytes\)', l)
    if not m:
        continue
    p = m.group(1)
    parts = p.split(BS)
    if 'OrganInstallationPackages' in parts and len(parts) > 6:
        groups[(section, BS.join(parts[:6]))] += 1
        exts[p.rsplit('.', 1)[-1].lower()] += 1
    else:
        other.append(f'{section:<24} {p}  ({m.group(2)} bytes)')
for (s, d), n in sorted(groups.items()):
    print(f'{s:<24} {d}{BS}...  {n} files')
print()
print('\n'.join(other))
print()
print('package file types:', dict(exts))
