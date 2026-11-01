#!/usr/bin/env python3
"""Merge several run folders into one summary (stdout, markdown).
Args: run folders and/or *-batches.txt lists, oldest first. A flow's LAST result wins; earlier
different results are listed in the 'history' column (so a re-run shows flaky vs. real)."""
import sys, os, re, glob, xml.etree.ElementTree as ET, yaml
suite = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
meta = {}
for f in glob.glob(os.path.join(suite, 'flows', '*.yaml')):
    d = yaml.safe_load(open(f).read().split('\n---', 1)[0]); meta[d.get('name')] = d
runs = []
for a in sys.argv[1:]:
    if a.endswith('.txt'): runs += [l.strip().rstrip('/') for l in open(a) if l.strip()]
    else: runs.append(a.rstrip('/'))
res, hist, order = {}, {}, []
fat = anr = 0
for r in runs:
    jx = os.path.join(r, 'junit.xml')
    if not os.path.exists(jx): continue
    for tc in ET.parse(jx).getroot().iter('testcase'):
        n = tc.get('name'); e = tc.find('failure') if tc.find('failure') is not None else tc.find('error')
        st = 'fail' if e is not None else ('skipped' if tc.find('skipped') is not None else 'pass')
        msg = ((e.get('message') or e.text or '') if e is not None else '').strip().replace('\n', ' ')[:220]
        exp = str(((meta.get(n) or {}).get('properties') or {}).get('expect', 'pass'))
        v = 'expected-fail' if st == 'fail' and exp.startswith('fail') else ('UNEXPECTED-PASS' if st == 'pass' and exp.startswith('fail') else st)
        if n not in order: order.append(n)
        hist.setdefault(n, []).append(v)
        res[n] = (v, float(tc.get('time') or 0), msg, os.path.basename(r))
    lc = os.path.join(r, 'logcat.redacted.txt')
    if os.path.exists(lc):
        s = open(lc, errors='ignore').read()
        fat += len(re.findall(r'FATAL EXCEPTION', s)); anr += len(re.findall(r'ANR in com\.samourai', s))
cnt = {}
for n in order: cnt[res[n][0]] = cnt.get(res[n][0], 0) + 1
print('# Merged Maestro results\n\nRuns: ' + ', '.join(os.path.basename(r) for r in runs) + '\n')
print('**Totals (last result per flow):** ' + ', '.join(f'{k} {v}' for k, v in sorted(cnt.items())) + f' (of {len(order)})\n')
print(f'**Logcat over all runs:** {fat} FATAL EXCEPTION, {anr} app ANR(s)\n')
print('| Flow | Result | History | Time | Run folder | Plan case | Notes |\n|---|---|---|---|---|---|---|')
for n in order:
    v, t, msg, rf = res[n]; p = (meta.get(n) or {}).get('properties') or {}
    print('| ' + ' | '.join(str(x).replace('|', '/') for x in (n, v, '→'.join(hist[n]), f'{t:.0f}s', rf, p.get('plan', ''), (str(p.get('known', '')) + ' ' + msg).strip())) + ' |')
