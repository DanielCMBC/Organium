"""Builds controlled spatial-test organs from probe variant F (stops driven directly by their tabs).

Each output organ:
  - engages only the Swell Open Diapason (rank 2) at load (stops first engaged after loading were
    observed to stay silent in this Hauptwerk setup);
  - takes rank 2 out of the swell box (EnclosurePipe rows removed, pipe output to open air);
  - gives every rank-2 pipe the same source sample (SAMPLE_FILE) at its original speed: one Sample
    row per pipe, whose stated pitch (method 3, harmonic 8, MIDI note) equals the pipe's own note;
  - sets every rank-2 layer to neutral voicing (no level, tuning, depth or harmonic offsets);
  - assigns the X/Y/Z design of the chosen experiment to the natural keys 36..91, in order (the
    sharps get the same values as the natural below them and are not played);
  - raises the organ level (AudioOut_AmplitudeLevelAdjustDecibels) to ORGAN_DB so 16-bit recordings
    keep quantization error small.

Usage: python -I build_spatial_probe.py <variantF.xml> <experiment> <out.xml>
Experiments: see DESIGNS, or a design file `file:<path.csv>:<organ id offset>` with 33 rows of
x_m,y_m,z_m (header required), assigned to the natural keys 36..91 in order.
"""
import re
import sys

SAMPLE_FILE = 'PolyphonyTestingOrganRank/055-G.wav'
ORGAN_DB = '1e+1'
RANK = '2'          # Swell: Open Diapason 8
TAB = '10137'       # its stop tab
NATURALS = [n for n in range(36, 92) if n % 12 in (0, 2, 4, 5, 7, 9, 11)]   # 33 keys
FIRST_ID = 800400


def sweep(lo, hi):
    return [lo + (hi - lo) * k / (len(NATURALS) - 1) for k in range(len(NATURALS))]


# name: (organ id offset, [(x, y, z) per natural key])
DESIGNS = {
    'x-y3': (0, [(x, 3.0, 0.0) for x in sweep(-3.2, 3.2)]),
    'x-y1': (1, [(x, 1.0, 0.0) for x in sweep(-3.2, 3.2)]),
    'x-y6': (2, [(x, 6.0, 0.0) for x in sweep(-3.2, 3.2)]),
    'y-x1': (3, [(1.0, y, 0.0) for y in sweep(0.25, 16.25)]),
    'z-x1': (4, [(1.0, 3.0, z) for z in sweep(-3.2, 3.2)]),
    'y-x0': (5, [(0.0, y, 0.0) for y in sweep(0.25, 16.25)]),
}


def table_span(src, name):
    m = re.search(r'(<ObjectList ObjectType="%s">)(.*?)(</ObjectList>)' % name, src, re.S)
    return m


def rows(src, name):
    return re.findall(r'<o>(.*?)</o>', table_span(src, name).group(2), re.S)


def fields(row):
    return dict(re.findall(r'<([a-z][a-z0-9]*)>([^<]*)</\1>', row))


def put(d):
    return '<o>' + ''.join(f'<{k}>{v}</{k}>' for k, v in d.items()) + '</o>'


def replace_table(src, name, new_rows):
    m = table_span(src, name)
    return src[:m.start()] + m.group(1) + '\n' + '\n'.join(new_rows) + '\n' + m.group(3) + src[m.end():]


def fmt(v):
    return f'{v:.6g}'


def main():
    base, exp, out = sys.argv[1:4]
    if exp.startswith('file:'):
        path, off = exp[5:].rsplit(':', 1)
        import csv
        design = [(float(r['x_m']), float(r['y_m']), float(r['z_m'])) for r in csv.DictReader(open(path))]
        if len(design) != len(NATURALS):
            raise SystemExit(f'{path}: {len(design)} rows, need {len(NATURALS)}')
        offset = int(off)
        exp = path.replace('\\', '/').rsplit('/', 1)[-1].replace('.csv', '')
    else:
        offset, design = DESIGNS[exp]
    src = open(base, encoding='utf-8').read()
    organ_id = str(FIRST_ID + offset)
    src = re.sub(r'(<Identification_UniqueOrganID>)[^<]*', r'\g<1>' + organ_id, src, count=1)
    src = re.sub(r'(<Identification_Name>)[^<]*', r'\g<1>Organium Spatial ' + exp, src, count=1)
    src = re.sub(r'(<Identification_LCDDisplayShortName>)[^<]*', r'\g<1>OrgSpatial' + exp, src, count=1)
    src = re.sub(r'(<AudioOut_AmplitudeLevelAdjustDecibels>)[^<]*', r'\g<1>' + ORGAN_DB, src, count=1)

    # per note design (sharps take the natural below)
    by_note, k = {}, 0
    for n in range(36, 92):
        if n in NATURALS:
            by_note[n] = design[k]
            k += 1
        else:
            by_note[n] = by_note[n - 1]

    pipes = [fields(r) for r in rows(src, 'Pipe_SoundEngine01')]
    rank_pipes = {p['a']: int(p.get('d', 60)) for p in pipes if p.get('b') == RANK}
    new_pipes = []
    for p in pipes:
        if p['a'] in rank_pipes:
            x, y, z = by_note[rank_pipes[p['a']]]
            for tag in ('n', 'p', 'q'):
                p.pop(tag, None)
            p['n'], p['p'], p['q'] = fmt(x), fmt(y), fmt(z)
            p['f'] = '8'          # output pitch harmonic 8 = same as the sample's stated pitch: no re-pitching
            p['s'] = '1'          # output wind to open air (no enclosure box)
        new_pipes.append(put(p))
    src = replace_table(src, 'Pipe_SoundEngine01', new_pipes)

    layers = []
    for r in rows(src, 'Pipe_SoundEngine01_Layer'):
        f = fields(r)
        if f.get('b') in rank_pipes:
            for tag in ('h', 'n', 'p', 't', 'x', 'y', 'c1', 'r', 'q'):
                f.pop(tag, None)  # neutral: no level, depth, tuning, harmonic, balance or enclosure offsets
        layers.append(put(f))
    src = replace_table(src, 'Pipe_SoundEngine01_Layer', layers)

    sample_rows = [fields(r) for r in rows(src, 'Sample')]
    next_id = max(int(s['a']) for s in sample_rows) + 1
    sample_of_pipe = {}
    for pid, note in sorted(rank_pipes.items(), key=lambda kv: kv[1]):
        sample_rows.append({'a': str(next_id), 'b': '1', 'c': SAMPLE_FILE, 'd': '3', 'e': '8', 'f': str(note)})
        sample_of_pipe[pid] = str(next_id)
        next_id += 1
    src = replace_table(src, 'Sample', [put(s) for s in sample_rows])
    for t in ('Pipe_SoundEngine01_AttackSample', 'Pipe_SoundEngine01_ReleaseSample'):
        new = []
        for r in rows(src, t):
            f = fields(r)
            if f.get('b') in sample_of_pipe:
                f['c'] = sample_of_pipe[f['b']]
            new.append(put(f))
        src = replace_table(src, t, new)

    enc = [r for r in rows(src, 'EnclosurePipe') if fields(r).get('b') not in rank_pipes]
    src = replace_table(src, 'EnclosurePipe', [f'<o>{r}</o>' for r in enc])

    sw = []
    for r in rows(src, 'Switch'):
        f = fields(r)
        if f['a'] == TAB:
            f['e'] = 'Y'
        elif f.get('e') == 'Y' and f['a'] in {'10133', '10135', '10139', '10141', '10143', '10145'}:
            f.pop('e')
        sw.append(put(f))
    src = replace_table(src, 'Switch', sw)

    open(out, 'w', encoding='utf-8', newline='').write(src)
    with open(out.replace('.Organ_Hauptwerk_xml', '-design.csv'), 'w') as f:
        f.write('note,x_m,y_m,z_m\n')
        for n in NATURALS:
            f.write('%d,%s,%s,%s\n' % ((n,) + tuple(fmt(v) for v in by_note[n])))
    print(f'{out}: organ {organ_id}, {len(rank_pipes)} rank-{RANK} pipes, {len(NATURALS)} designed keys, '
          f'{len(enc)} enclosure rows kept')


main()
