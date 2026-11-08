"""Traces switch linkages for a switch ID in the compiled probe organ (diagnostic)."""
import re
import sys
src = open('compiled/OrganiumProbe.Organ_Hauptwerk_xml', encoding='utf-8').read()


def table(name):
    m = re.search(r'<ObjectList ObjectType="%s">(.*?)</ObjectList>' % name, src, re.S)
    return [dict(re.findall(r'<([a-z][a-z0-9]*)>([^<]*)</\1>', o)) for o in re.findall(r'<o>(.*?)</o>', m.group(1), re.S)] if m else []


sw = {r['a']: r for r in table('Switch')}
links = table('SwitchLinkage')
print(len(sw), 'switches,', len(links), 'linkages')


def show(i):
    r = sw.get(i, {})
    return f"{i} '{r.get('b')}' e={r.get('e', 'N')} clickable={r.get('i', 'N')} img={r.get('k')}"


for target in sys.argv[1:]:
    for l in links:
        if target in (l.get('a'), l.get('b'), l.get('d')):
            print('LINK', l)
            print('     ', show(l.get('a')), '->', show(l.get('b')))
for i, r in sw.items():
    name = r.get('b', '').lower()
    if any(k in name for k in ('diapason', 'blower', 'master', 'wind', 'motor')):
        print('SW', show(i))
