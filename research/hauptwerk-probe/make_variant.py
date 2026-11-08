"""Makes a diagnostic variant of a compiled organ definition: new organ ID, name and file name, plus
field edits on chosen tables, so the variant loads alongside the original (Organ -> Load organ)
without touching it.

Edit syntax: Table.tag=value (set on every row), Table.tag=- (remove from every row),
Table.tag=value@other_tag=match (only rows where other_tag == match),
Table!@tag=match (delete the rows where tag == match).

Usage: python -I make_variant.py <compiled.xml> <out.xml> <organ id> <name> [edit ...]
"""
import re
import sys


def main():
    src_path, out_path, organ_id, name = sys.argv[1:5]
    edits = sys.argv[5:]
    src = open(src_path, encoding='utf-8').read()
    src = re.sub(r'(<Identification_UniqueOrganID>)[^<]*', r'\g<1>' + organ_id, src, count=1)
    src = re.sub(r'(<Identification_Name>)[^<]*', r'\g<1>' + name, src, count=1)
    src = re.sub(r'(<Identification_LCDDisplayShortName>)[^<]*', r'\g<1>' + name.replace(' ', '')[:16], src, count=1)
    for e in edits:
        if '!@' in e:
            table, cond = e.split('!@')
            ctag, cval = cond.split('=', 1)
            m = re.search(r'(<ObjectList ObjectType="%s">)(.*?)(</ObjectList>)' % re.escape(table), src, re.S)
            rows = re.findall(r'<o>.*?</o>', m.group(2), re.S)
            keep = [r for r in rows if re.search(r'<%s>%s</%s>' % (ctag, re.escape(cval), ctag), r) is None]
            src = src[:m.start()] + m.group(1) + '\n' + '\n'.join(keep) + '\n' + m.group(3) + src[m.end():]
            print(f'  {e}: deleted {len(rows) - len(keep)} rows')
            continue
        target, _, cond = e.partition('@')
        table_tag, value = target.split('=', 1)
        table, tag = table_tag.split('.')
        ctag, cval = cond.split('=', 1) if cond else (None, None)
        m = re.search(r'(<ObjectList ObjectType="%s">)(.*?)(</ObjectList>)' % re.escape(table), src, re.S)
        if not m:
            raise SystemExit(f'no table {table}')
        changed = 0

        def edit_row(row):
            nonlocal changed
            body = row.group(1)
            if ctag and re.search(r'<%s>%s</%s>' % (ctag, re.escape(cval), ctag), body) is None:
                return row.group(0)
            body = re.sub(r'<%s>[^<]*</%s>' % (tag, tag), '', body)
            if value != '-':
                body += f'<{tag}>{value}</{tag}>'
            changed += 1
            return f'<o>{body}</o>'

        new_body = re.sub(r'<o>(.*?)</o>', edit_row, m.group(2), flags=re.S)
        src = src[:m.start()] + m.group(1) + new_body + m.group(3) + src[m.end():]
        print(f'  {e}: {changed} rows')
    open(out_path, 'w', encoding='utf-8', newline='').write(src)
    print(f'wrote {out_path} (organ ID {organ_id}, "{name}")')


main()
