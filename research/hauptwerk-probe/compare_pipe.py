"""Diagnostic: prints the compiled rows for one pipe (by note) in two organ definitions side by side,
following Pipe -> Layer -> Attack/Release sample -> Sample, plus their wind compartment rows.

Usage: python -I compare_pipe.py <organA.xml> <rankIdA> <organB.xml> <rankIdB> <note>
"""
import re
import sys


def tables(path):
    src = open(path, encoding='utf-8').read()
    out = {}
    for name, body in re.findall(r'<ObjectList ObjectType="([^"]+)">(.*?)</ObjectList>', src, re.S):
        out[name] = [dict(re.findall(r'<([A-Za-z_][A-Za-z0-9_]*)>([^<]*)</\1>', o))
                     for o in re.findall(r'<o>(.*?)</o>', body, re.S)]
    return out


def chain(t, rank, note):
    pipe = next(p for p in t['Pipe_SoundEngine01'] if p.get('b') == rank and p.get('d', '60') == str(note))
    layer = next(l for l in t['Pipe_SoundEngine01_Layer'] if l.get('b') == pipe['a'])
    attack = [a for a in t['Pipe_SoundEngine01_AttackSample'] if a.get('b') == layer['a']]
    release = [r for r in t['Pipe_SoundEngine01_ReleaseSample'] if r.get('b') == layer['a']]
    samples = {s['a']: s for s in t['Sample']}
    rows = {'Pipe': pipe, 'Layer': layer}
    for i, a in enumerate(attack):
        rows[f'Attack{i}'] = a
        rows[f'Attack{i}.Sample'] = samples.get(a.get('c'), {})
    for i, r in enumerate(release):
        rows[f'Release{i}'] = r
    return rows


def main():
    a, ra, b, rb, note = sys.argv[1:6]
    ta, tb = tables(a), tables(b)
    ca, cb = chain(ta, ra, note), chain(tb, rb, note)
    for key in sorted(set(ca) | set(cb), key=lambda k: (k.split('.')[0], k)):
        x, y = ca.get(key, {}), cb.get(key, {})
        print(f'== {key}')
        for f in sorted(set(x) | set(y), key=lambda s: (len(s), s)):
            mark = '' if x.get(f) == y.get(f) else '   <-- differs'
            print(f'   {f:<4} A={x.get(f, "-"):<24} B={y.get(f, "-"):<24}{mark}')
    for t, name in ((ta, 'A'), (tb, 'B')):
        print(f'== {name} WindCompartment rows: {len(t.get("WindCompartment", []))}')
        for w in t.get('WindCompartment', [])[:6]:
            print('   ', w)


main()
