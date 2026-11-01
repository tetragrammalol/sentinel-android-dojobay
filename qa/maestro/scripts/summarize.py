#!/usr/bin/env python3
"""Turn a run folder's junit.xml (+ flow metadata + logcat) into summary.md."""
import sys, os, re, glob, xml.etree.ElementTree as ET
try:
    import yaml
except ImportError:
    yaml = None
run = sys.argv[1]
suite = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
meta = {}
for f in glob.glob(os.path.join(suite, 'flows', '*.yaml')):
    head = open(f).read().split('\n---', 1)[0]
    d = yaml.safe_load(head) if yaml else {}
    name = d.get('name') or os.path.basename(f)[:-5]
    meta[name] = d
rows, counts = [], {}
jx = os.path.join(run, 'junit.xml')
if os.path.exists(jx):
    for tc in ET.parse(jx).getroot().iter('testcase'):
        name = tc.get('name'); t = float(tc.get('time') or 0)
        fail = tc.find('failure'); err = tc.find('error'); skip = tc.find('skipped')
        status = 'pass'
        msg = ''
        if fail is not None or err is not None:
            status = 'fail'; e = fail if fail is not None else err
            msg = (e.get('message') or e.text or '').strip().replace('\n', ' ')[:300]
        elif skip is not None:
            status = 'skipped'
        m = meta.get(name, {})
        props = m.get('properties') or {}
        exp = str(props.get('expect', 'pass'))
        verdict = status
        if status == 'fail' and exp.startswith('fail'):
            verdict = 'expected-fail'
        elif status == 'pass' and exp.startswith('fail'):
            verdict = 'UNEXPECTED-PASS'
        counts[verdict] = counts.get(verdict, 0) + 1
        rows.append((name, verdict, f'{t:.0f}s', ','.join(m.get('tags') or []), props.get('plan', ''), (props.get('known', '') + ' ' + msg).strip()))
print(f'# Maestro run {os.path.basename(run)}\n')
if os.path.exists(os.path.join(run, 'app.txt')):
    print('```\n' + open(os.path.join(run, 'app.txt')).read().strip() + '\n```\n')
print('**Totals:** ' + ', '.join(f'{k} {v}' for k, v in sorted(counts.items())) + f' (of {len(rows)})\n')
print('| Flow | Result | Time | Tags | Plan case | Notes |\n|---|---|---|---|---|---|')
for r in rows:
    print('| ' + ' | '.join(str(x).replace('|', '/') for x in r) + ' |')
lc = os.path.join(run, 'logcat.redacted.txt')
if os.path.exists(lc):
    s = open(lc, errors='ignore').read()
    fat = len(re.findall(r'FATAL EXCEPTION', s)); anr = len(re.findall(r'ANR in com\.samourai', s))
    print(f'\n**Logcat:** {fat} FATAL EXCEPTION, {anr} app ANR(s). File: logcat.redacted.txt')
    for m in re.finditer(r'(\d\d-\d\d \d\d:\d\d:\d\d).{0,40}(FATAL EXCEPTION|ANR in com\.samourai\S*)', s):
        print(f'- {m.group(1)} {m.group(2)}')
