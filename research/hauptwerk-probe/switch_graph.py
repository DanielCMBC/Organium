"""Diagnostic: follows SwitchLinkage edges from every displayed switch whose name matches a
pattern, printing each switch's raw fields and each linkage's raw fields, down to the stop nodes.

Usage: python -I switch_graph.py <organ.xml> <name pattern (regex)>
"""
import re
import sys

src = open(sys.argv[1], encoding='utf-8').read()


def table(name):
    m = re.search(r'<ObjectList ObjectType="%s">(.*?)</ObjectList>' % name, src, re.S)
    return [dict(re.findall(r'<([a-z][a-z0-9]*)>([^<]*)</\1>', o)) for o in re.findall(r'<o>(.*?)</o>', m.group(1), re.S)] if m else []


switches = {s['a']: s for s in table('Switch')}
links = table('SwitchLinkage')
out_links = {}
for l in links:
    out_links.setdefault(l.get('a'), []).append(l)
stop_nodes = {s['d']: s.get('b') for s in table('Stop')}
groups = table('SwitchExclusiveSelectGroupElement')
in_group = {g.get('a'): g.get('b') for g in groups}
pattern = re.compile(sys.argv[2], re.I)


def walk(sid, depth, seen):
    s = switches.get(sid, {})
    tag = f"  <- STOP NODE of '{stop_nodes[sid]}'" if sid in stop_nodes else ''
    grp = f'  [exclusive group {in_group[sid]}]' if sid in in_group else ''
    print('  ' * depth + f'switch {sid}: {s}{tag}{grp}')
    if sid in seen:
        print('  ' * depth + '  (cycle)')
        return
    for l in out_links.get(sid, []):
        print('  ' * depth + f'  linkage {l}')
        walk(l.get('b'), depth + 2, seen | {sid})


print(f'{len(switches)} switches, {len(links)} linkages, {len(groups)} exclusive-group elements')
for sid, s in switches.items():
    if pattern.search(s.get('b', '')) and (s.get('k') or s.get('i')):
        walk(sid, 0, frozenset())
        print()
# also: which linkages point INTO each stop node
for node, name in stop_nodes.items():
    srcs = [l for l in links if l.get('b') == node]
    print(f'stop node {node} ({name}) is driven by: {[(l.get("a"), switches.get(l.get("a"), {}).get("b")) for l in srcs]}')
