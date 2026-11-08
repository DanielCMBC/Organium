"""Read-only before/after snapshots of Hauptwerk's data, for finding what a single action changes.

  python -I snapshot.py take <name>            record every file under the roots (size, mtime, SHA-256),
                                               keep copies of small text/XML files, export registry keys
  python -I snapshot.py diff <before> <after>  list added/removed/changed files; show line diffs for
                                               copied text files

Snapshots are written to ./snapshots/<name>. Nothing under the roots is ever modified.
"""
import difflib
import hashlib
import json
import os
import shutil
import subprocess
import sys
import time

ROOTS = [r'C:\Hauptwerk']
REGISTRY_KEYS = [r'HKCU\Software\Hauptwerk', r'HKCU\Software\Milan Digital Audio',
                 r'HKLM\Software\Hauptwerk', r'HKLM\Software\Milan Digital Audio']
COPY_MAX_BYTES = 64 * 1024 * 1024
TEXT_SUFFIXES = ('_hauptwerk_xml', '.xml', '.txt', '.ini', '.log', '.cfg')
HERE = os.path.dirname(os.path.abspath(__file__))


def is_text(path):
    return path.lower().endswith(TEXT_SUFFIXES)


def sha256(path):
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        for chunk in iter(lambda: f.read(1 << 20), b''):
            h.update(chunk)
    return h.hexdigest()


def take(name):
    dest = os.path.join(HERE, 'snapshots', name)
    if os.path.exists(dest):
        sys.exit(f'{dest} already exists')
    os.makedirs(os.path.join(dest, 'files'))
    index = {}
    for root in ROOTS:
        for folder, _, names in os.walk(root):
            for n in names:
                path = os.path.join(folder, n)
                try:
                    st = os.stat(path)
                    index[path] = {'size': st.st_size, 'mtime': st.st_mtime, 'sha256': sha256(path)}
                except OSError as e:
                    index[path] = {'error': str(e)}
                    continue
                if is_text(path) and st.st_size <= COPY_MAX_BYTES:
                    copy = os.path.join(dest, 'files', path.replace(':', '').lstrip('\\/'))
                    os.makedirs(os.path.dirname(copy), exist_ok=True)
                    shutil.copy2(path, copy)
    for key in REGISTRY_KEYS:
        out = os.path.join(dest, 'registry-' + key.replace('\\', '_').replace(' ', '') + '.reg')
        subprocess.run(['reg', 'export', key, out, '/y'], capture_output=True)
    json.dump({'taken': time.strftime('%Y-%m-%d %H:%M:%S'), 'files': index},
              open(os.path.join(dest, 'index.json'), 'w', encoding='utf-8'), indent=1)
    print(f'{name}: {len(index)} files, '
          f'{sum(1 for f in os.listdir(dest) if f.endswith(".reg"))} registry keys exported')


def load(name):
    dest = os.path.join(HERE, 'snapshots', name)
    return dest, json.load(open(os.path.join(dest, 'index.json'), encoding='utf-8'))['files']


def read_lines(snapshot_dir, path):
    copy = os.path.join(snapshot_dir, 'files', path.replace(':', '').lstrip('\\/'))
    if not os.path.exists(copy):
        return None
    raw = open(copy, 'rb').read()
    for enc in ('utf-8', 'utf-16', 'cp1252'):
        try:
            return raw.decode(enc).splitlines()
        except UnicodeDecodeError:
            pass
    return None


def diff(before, after, max_lines=200):
    bdir, b = load(before)
    adir, a = load(after)
    added = sorted(set(a) - set(b))
    removed = sorted(set(b) - set(a))
    changed = sorted(p for p in set(a) & set(b) if a[p].get('sha256') != b[p].get('sha256'))
    touched = sorted(p for p in set(a) & set(b)
                     if a[p].get('sha256') == b[p].get('sha256') and a[p].get('mtime') != b[p].get('mtime'))
    for label, paths in (('ADDED', added), ('REMOVED', removed), ('CHANGED', changed),
                         ('REWRITTEN, SAME CONTENT', touched)):
        print(f'== {label}: {len(paths)}')
        for p in paths:
            print(f'   {p}  ({(a.get(p) or b.get(p)).get("size")} bytes)')
    for p in changed:
        old, new = read_lines(bdir, p), read_lines(adir, p)
        if old is None or new is None:
            continue
        lines = list(difflib.unified_diff(old, new, 'before', 'after', n=2, lineterm=''))
        print(f'\n--- diff {p} ({len(lines)} lines)')
        for line in lines[:max_lines]:
            print(line[:400])
        if len(lines) > max_lines:
            print(f'... {len(lines) - max_lines} more lines')
    for reg in sorted(set(os.listdir(adir)) | set(os.listdir(bdir))):
        if reg.endswith('.reg'):
            old = read_reg(bdir, reg)
            new = read_reg(adir, reg)
            if old != new:
                print(f'\n--- registry {reg}')
                for line in difflib.unified_diff(old, new, 'before', 'after', n=1, lineterm=''):
                    print(line[:400])


def read_reg(folder, name):
    path = os.path.join(folder, name)
    if not os.path.exists(path):
        return []
    return open(path, 'rb').read().decode('utf-16', errors='replace').splitlines()


if __name__ == '__main__':
    if len(sys.argv) == 3 and sys.argv[1] == 'take':
        take(sys.argv[2])
    elif len(sys.argv) == 4 and sys.argv[1] == 'diff':
        diff(sys.argv[2], sys.argv[3])
    else:
        sys.exit(__doc__)
