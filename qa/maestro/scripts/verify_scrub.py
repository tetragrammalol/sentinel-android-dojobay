#!/usr/bin/env python3
"""Check run folders for leftover secrets (prints counts only, never values).
Usage: verify_scrub.py [--secrets FILE] [DIR ...]   (default: runs/)"""
import os, re, sys
a = sys.argv[1:]; sec = os.environ.get('SENTINEL_SECRETS_FILE', os.path.join(os.environ.get('SENTINEL_QA_HOME', os.path.expanduser('~/.sentinel-qa')), 'secret', 'sentinel.env'))
if a[:1] == ['--secrets']: sec = a[1]; a = a[2:]
dirs = a or [os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'runs')]
vals = [l.split('=', 1)[1].strip().encode() for l in open(sec) if '=' in l and not l.startswith('#')]
vals = [v for v in vals if len(v) >= 5]
n = {'secret': 0, 'pin-key-id': 0, 'onion': 0}
for d in dirs:
    for root, _, fs in os.walk(d):
        for f in fs:
            b = open(os.path.join(root, f), 'rb').read()
            n['secret'] += any(v in b for v in vals)
            n['pin-key-id'] += bool(re.search(rb'id/t[a-j]\b', b))
            n['onion'] += bool(re.search(rb'[a-z2-7]{16,56}\.onion', b))
print('files with: ' + ', '.join(f'{k}={v}' for k, v in n.items()))
sys.exit(1 if any(n.values()) else 0)
