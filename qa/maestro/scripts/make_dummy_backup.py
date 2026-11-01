#!/usr/bin/env python3
"""Make a dummy Sentinel backup blob (same format as the app: OpenSSL 'Salted__' + PBKDF2-SHA256
15000 + AES-256-CBC, wrapped in {version,time,payload}) holding NO real data: 0 collections, no Dojo.
It is encrypted with a random password that is thrown away, so the suite can only ever use it to
check the wrong-password and corrupt-backup errors (SET-05/SET-06); it can never import anything.
Writes data/backup.env with DUMMY_BACKUP and DUMMY_BACKUP_TRUNC (public, no secrets)."""
import os, json, base64, hashlib, time
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
pw = base64.b64encode(os.urandom(24))          # discarded
salt = os.urandom(8)
k = hashlib.pbkdf2_hmac('sha256', pw, salt, 15000, 48)
pt = json.dumps({"collections": [], "prefs": {"testnet": True}}).encode()
pad = 16 - len(pt) % 16; pt += bytes([pad]) * pad
e = Cipher(algorithms.AES(k[:32]), modes.CBC(k[32:])).encryptor()
ct = e.update(pt) + e.finalize()
blob = json.dumps({"version": 2, "time": int(time.time() * 1000),
                   "payload": base64.b64encode(b'Salted__' + salt + ct).decode()}, separators=(',', ':'))
trunc = blob[:len(blob) // 2] + '"}'
out = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'data', 'backup.env')
with open(out, 'w') as f:
    f.write('# Dummy backup blobs for SET-05/06: empty payload, random discarded password. Regenerate: scripts/make_dummy_backup.py\n')
    f.write(f'DUMMY_BACKUP={blob}\nDUMMY_BACKUP_TRUNC={trunc}\n')
print('wrote', os.path.normpath(out), len(blob))
