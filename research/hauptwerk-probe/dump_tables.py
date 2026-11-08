"""Diagnostic: prints selected tables of one or more organ definitions (first N rows each), plus row
counts for every table, so the key -> division -> stop -> rank -> pipe chains can be compared.

Usage: python -I dump_tables.py <rows> <Table,Table,...> <organ.xml> [<organ.xml> ...]
"""
import re
import sys

rows, names = int(sys.argv[1]), sys.argv[2].split(',')
for path in sys.argv[3:]:
    src = open(path, encoding='utf-8').read()
    tables = {n: re.findall(r'<o>(.*?)</o>', body, re.S)
              for n, body in re.findall(r'<ObjectList ObjectType="([^"]+)">(.*?)</ObjectList>', src, re.S)}
    print(f'##### {path.split("/")[-1]}')
    print('  counts:', {n: len(v) for n, v in tables.items() if v})
    for n in names:
        print(f'  == {n} ({len(tables.get(n, []))})')
        for o in tables.get(n, [])[:rows]:
            print('    ', dict(re.findall(r'<([A-Za-z_][A-Za-z0-9_]*)>([^<]*)</\1>', o)))
