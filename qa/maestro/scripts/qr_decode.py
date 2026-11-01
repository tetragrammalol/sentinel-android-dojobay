#!/usr/bin/env python3
"""Decode QR codes in a PNG (OpenCV). Prints one JSON list of decoded strings. Usage: qr_decode.py img.png"""
import sys, json, cv2
img = cv2.imread(sys.argv[1])
out = []
try:
    ok, texts, _, _ = cv2.QRCodeDetector().detectAndDecodeMulti(img)
    out = [t for t in (texts or []) if t]
except Exception:
    pass
if not out:
    t, _, _ = cv2.QRCodeDetector().detectAndDecode(img)
    if t: out = [t]
print(json.dumps(out))
