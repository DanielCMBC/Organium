"""Matches compacted tags in Hauptwerk's compiled OrganiumProbe organ definition to the values that
make_probe.py planted, so each tag is identified by evidence rather than by guessing.

For every planted parameter it prints an evidence table: the custom-organ parameter, its planted
values, each candidate tag, and the observed values. A tag only counts as a match for a parameter
if every pipe of every rank lies on the planted line (within 0.01). Verdicts:
  CONFIRMED  exactly one tag matches every pipe of all ranks
  AMBIGUOUS  more than one tag does
  PARTIAL    best tags match at least one whole rank, but not all (listed, not counted as a mapping)
  NOT FOUND  no tag matches any rank
All remaining tags are listed afterwards with sample values, for manual review (e.g. positions).

Usage: python -I decode_probe.py <compiled OrganiumProbe.Organ_Hauptwerk_xml>
"""
import re
import sys
from collections import defaultdict

TOLERANCE = 0.01
LINEAR = {  # custom-organ parameter pair: (value at pipe 36, value at pipe 96) for rank r
    'Amp_PipeMIDINoteNum036/096_LevelAdjustDecibels': lambda r: (-3.17 - r, 2.29 + r),
    'Amp_PipeMIDINoteNum036/096_WindModelModDepthAdjustDecibels': lambda r: (0.61 + r, -0.73 - r),
    'Amp_PipeMIDINoteNum036/096_TremulantModDepthAdjustDecibels': lambda r: (0.83 + r, -0.97 - r),
    'Pitch_PipeMIDINoteNum036/096_TuningAdjustPercentSemitones': lambda r: (4.41 + r, -4.53 - r),
    'Pitch_PipeMIDINoteNum036/096_WindModelModDepthAdjustPercent': lambda r: (61.0 + r, 73.0 + r),
    'Pitch_PipeMIDINoteNum036/096_TremulantModDepthAdjustPercent': lambda r: (83.0 + r, 97.0 + r),
    'HarmonicShaping_PipeMIDINoteNum036/096_3rdAndUpperHarmLvlAdjDb': lambda r: (-1.37 - r, -1.59 - r),
}
CONSTANT = {  # per-rank constant parameters
    'Position_RankDistanceFromListenerMetres': lambda r: 1.25 + 0.5 * r,
}
LAYOUT = lambda r: r % 6 + 1
RANK_NAMES = ['Swell: Open Diapason 8', 'Pedal: Bourdon 16', 'Swell: Violin Diapason 8',
              'Swell: Lieblich Gedact 8', 'Swell: Clarinet 8', 'Swell: Principal 4', 'Swell: Clear Flute 4']


def table(src, name):
    m = re.search(r'<ObjectList ObjectType="%s">(.*?)</ObjectList>' % name, src, re.S)
    if not m:
        return []
    rows = re.findall(r'<(?:o|%s)>(.*?)</(?:o|%s)>' % (name, name), m.group(1), re.S)
    return [dict(re.findall(r'<([A-Za-z_][A-Za-z0-9_]*)>([^<]*)</\1>', row)) for row in rows]


def num(v):
    try:
        return float(v)
    except (TypeError, ValueError):
        return None


def score(by_rank, tag, want):
    """(ranks fully matched, pipes matched, pipes total) for want(r, note) -> expected value."""
    ranks_ok = pipes_ok = total = 0
    for r, rows in by_rank.items():
        rank_ok = True
        for n, row in rows:
            total += 1
            v = num(row.get(tag))
            if v is not None and abs(v - want(r, n)) <= TOLERANCE:
                pipes_ok += 1
            else:
                rank_ok = False
        ranks_ok += rank_ok
    return ranks_ok, pipes_ok, total


def report(name, planted, by_rank, tags, want):
    scored = sorted(((score(by_rank, t, want), t) for t in tags), reverse=True)
    full = [t for (ranks, pipes, total), t in scored if ranks == len(by_rank)]
    best = scored[0] if scored else None
    if len(full) == 1:
        verdict, shown = 'CONFIRMED', full
    elif len(full) > 1:
        verdict, shown = 'AMBIGUOUS', full
    elif best and best[0][0] > 0:  # at least one whole rank on the line; single-pipe coincidences don't count
        verdict, shown = 'PARTIAL', [t for (ranks, pipes, total), t in scored if ranks == best[0][0]]
    else:
        verdict, shown = 'NOT FOUND', []
    print(f'\n{name}\n  planted: {planted}\n  verdict: {verdict}')
    for t in shown:
        (ranks, pipes, total) = next(s for s, tt in scored if tt == t)
        rows0 = by_rank[min(by_rank)]
        lo_n, lo = rows0[0]
        hi_n, hi = rows0[-1]
        print(f'  tag <{t}>: {ranks}/{len(by_rank)} ranks, {pipes}/{total} pipes on the planted values; '
              f'rank 0 observed pipe {lo_n} = {lo.get(t)} (want {want(min(by_rank), lo_n):.4f}), '
              f'pipe {hi_n} = {hi.get(t)} (want {want(min(by_rank), hi_n):.4f})')
    return set(full)


def main():
    src = open(sys.argv[1], encoding='utf-8').read()
    print('FileFormat:', re.search(r'FileFormat="([^"]*)"', src).group(1))
    ranks = {r['a']: r.get('b', '') for r in table(src, 'Rank')}
    if not ranks:
        sys.exit('No Rank table with compacted tags: is the file uncompacted? Inspect it directly.')
    rank_index = {rid: RANK_NAMES.index(n) for rid, n in ranks.items() if n in RANK_NAMES}
    print('probe ranks found:', sorted(rank_index.values()), 'of', list(range(len(RANK_NAMES))))

    pipes = {p['a']: p for p in table(src, 'Pipe_SoundEngine01')}
    layers = table(src, 'Pipe_SoundEngine01_Layer')
    note = lambda p: int(num(p.get('d', '60')))

    groups = {'Pipe_SoundEngine01': defaultdict(list), 'Pipe_SoundEngine01_Layer': defaultdict(list)}
    for p in pipes.values():
        if p.get('b') in rank_index:
            groups['Pipe_SoundEngine01'][rank_index[p['b']]].append((note(p), p))
    for layer in layers:
        p = pipes.get(layer.get('b'))
        if p and p.get('b') in rank_index:
            groups['Pipe_SoundEngine01_Layer'][rank_index[p['b']]].append((note(p), layer))
    for g in groups.values():
        for rows in g.values():
            rows.sort(key=lambda x: x[0])

    explained = defaultdict(set)
    for kind, by_rank in groups.items():
        if not by_rank:
            print(f'\n=== {kind}: no rows for probe ranks')
            continue
        tags = sorted({t for rows in by_rank.values() for _, row in rows for t in row})
        print(f'\n=== {kind}: {len(tags)} tags, {sum(len(v) for v in by_rank.values())} rows')
        for name, sig in LINEAR.items():
            want = lambda r, n, sig=sig: sig(r)[0] + (sig(r)[1] - sig(r)[0]) * (n - 36) / 60
            planted = (f'pipe 36 = {sig(0)[0]:.2f}, pipe 96 = {sig(0)[1]:.2f} for rank 0, linear between; '
                       f'rank 6: {sig(6)[0]:.2f} -> {sig(6)[1]:.2f}')
            explained[kind] |= report(name, planted, by_rank, tags, want)
        for name, sig in CONSTANT.items():
            explained[kind] |= report(name, 'rank r: %.2f + 0.5 r on every pipe' % sig(0), by_rank, tags,
                                      lambda r, n, sig=sig: sig(r))

        print(f'\n--- {kind}: other tags (layout codes per rank: {[LAYOUT(r) for r in sorted(by_rank)]})')
        for t in tags:
            if t in explained[kind] or t in ('a', 'b'):
                continue
            per_rank = {r: [row.get(t, '-') for _, row in rows[:8]] for r, rows in sorted(by_rank.items())}
            const = all(len({row.get(t) for _, row in rows}) == 1 for rows in by_rank.values())
            print(f'  <{t}> {"rank-constant" if const else "varies by pipe"}')
            for r, vals in per_rank.items():
                print(f'      rank {r} (layout {LAYOUT(r)}): {" ".join(vals)}')


main()
