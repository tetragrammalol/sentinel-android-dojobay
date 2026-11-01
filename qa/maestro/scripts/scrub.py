#!/usr/bin/env python3
"""Scrub a Maestro run folder so no secret survives on disk.

Maestro's own debug files (maestro.log, commands*.json) record every env var and every
evaluated selector, so they would contain the PIN (whole, and digit by digit from the
lock-screen key taps). This script:
  1. replaces the PIN string itself everywhere (text files) with <PIN-REDACTED>;
  2. on any line that mentions the PIN-key selector (t[a-j] / "Tap PIN key" / textRegex),
     replaces single-digit selector values with D;
  2b. inside a PIN-entry block (Prepare PIN entry .. Clear PIN state) and on any line naming a
      key id t[a-j]: masks key text, bounds, tap coordinates and the key id;
  3. redacts Tor onion hostnames to <dojo>.onion;
  4. re-scans every file (text and binary) for the PIN; any file still containing it is deleted.
Secrets are read from the env file given with --secrets (KEY=value lines); nothing is printed.
Usage: scrub.py --secrets FILE DIR [DIR...]
"""
import os, re, sys

def load(path):
    vals = {}
    for line in open(path):
        line = line.strip()
        if line and not line.startswith('#') and '=' in line:
            k, v = line.split('=', 1)
            vals[k.strip()] = v.strip()
    return vals

args = sys.argv[1:]
if len(args) < 3 or args[0] != '--secrets':
    sys.exit(__doc__)
sec = load(args[1])
secrets = [v for k, v in sec.items() if v and len(v) >= 5]
dirs = args[2:]
ONION = re.compile(r'\b[a-z2-7]{16,56}\.onion\b')
PINLINE = re.compile(r't\[a-j\]|Tap PIN key|Wrong PIN key|textRegex|matching regex|PIN key')
KEYID = re.compile(r'id/t[a-j]\b')
DIGIT_SEL = [
    re.compile(r'(textRegex["\']?\s*[:=]\s*["\']?)\d(?=["\',)\s}]|$)'),
    re.compile(r'((?:Text|text) matching regex:?\s*["\']?)\d\b'),
    re.compile(r'("text"\s*:\s*")\d(")'),
    re.compile(r'(text:\s*["\']?)\d(?=["\',)\s}]|$)'),
]
changed = removed = 0
for d in dirs:
    for root, _, files in os.walk(d):
        for fn in files:
            p = os.path.join(root, fn)
            try:
                raw = open(p, 'rb').read()
            except OSError:
                continue
            try:
                txt = raw.decode('utf-8')
                is_text = True
            except UnicodeDecodeError:
                is_text = False
            if is_text:
                new = txt
                for s in secrets:
                    new = new.replace(s, '<SECRET-REDACTED>')
                new = ONION.sub('<dojo>.onion', new)
                out = []
                inpin = False
                for line in new.split('\n'):
                    if 'Prepare PIN entry' in line:
                        inpin = True
                    if inpin or KEYID.search(line):
                        # tapped key text, its bounds and tap coordinates would reveal the PIN order
                        line = re.sub(r'\btext=\d\b', 'text=D', line)
                        line = re.sub(r'bounds=\[[0-9,\]\[]+\]', 'bounds=[..]', line)
                        line = re.sub(r'Bounds\([^)]*\)', 'Bounds(..)', line)
                        line = re.sub(r'\(\d+, ?\d+\)', '(x, y)', line)
                        line = re.sub(r'(id/t)[a-j]\b', r'\1?', line)
                    if 'Clear PIN state' in line:
                        inpin = False
                    if PINLINE.search(line) or inpin:
                        for rx in DIGIT_SEL:
                            line = rx.sub(lambda m: m.group(1) + 'D' + (m.group(2) if m.lastindex and m.lastindex >= 2 else ''), line)
                    out.append(line)
                new = '\n'.join(out)
                if new != txt:
                    open(p, 'w').write(new)
                    changed += 1
                raw = new.encode()
            if any(s.encode() in raw for s in secrets):
                os.remove(p)
                removed += 1
print(f'scrub: {changed} file(s) redacted, {removed} file(s) deleted (secret still present)')
