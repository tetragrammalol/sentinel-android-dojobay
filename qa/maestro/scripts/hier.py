#!/usr/bin/env python3
"""Print a compact view of a Maestro screen-hierarchy JSON (debug aid)."""
import json, sys
def w(n, dep=0):
    a = n.get('attributes', {}); t = a.get('text') or a.get('accessibilityText') or ''; r = a.get('resource-id', '')
    if t or r: print(' ' * dep, r.split('/')[-1], repr(t)[:90], a.get('bounds', ''), 'EN' if a.get('enabled') == 'true' else 'dis')
    for c in n.get('children', []): w(c, dep + 1)
for f in sys.argv[1:]: print('==', f); w(json.load(open(f)))
