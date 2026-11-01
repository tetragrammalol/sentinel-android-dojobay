#!/usr/bin/env python3
"""Tiny localhost helper for the Maestro suite (Maestro's setClipboard only feeds pasteText,
it does not set the Android clipboard the app reads). Uses the emulator's gRPC controller.
Endpoints (127.0.0.1 only):
  POST /clip/set      body = text      -> sets the device clipboard
  POST /clip/equals   body = expected  -> {"equal": bool, "len": n}
  GET  /clip/info                       -> {"len": n, "prefix": first 10 chars}
  GET  /stamp/set?NAME   /stamp/age?NAME     -> remember a time / seconds since it (-1 = never)
  GET  /qr/screen                       -> {"texts": [...]} QR codes on screen (public prefixes only)
  GET  /health
Never returns the full clipboard, so nothing sensitive lands in Maestro's logs.
Env: GRPC_ADDR (default 127.0.0.1:8554), HELPER_PORT (default 8977)."""
import os, sys, json, grpc, time, subprocess, tempfile, re
STAMPS = {}
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
sys.path.insert(0, os.environ.get('GRPC_STUB_DIR', os.path.expanduser('~/.sentinel-qa/grpcstub')))
import emulator_controller_pb2 as pb, emulator_controller_pb2_grpc as g
from google.protobuf import empty_pb2
stub = g.EmulatorControllerStub(grpc.insecure_channel(os.environ.get('GRPC_ADDR', '127.0.0.1:8554'), options=[('grpc.max_receive_message_length', 64 << 20)]))

class H(BaseHTTPRequestHandler):
    def log_message(self, *a):  # no request logging (bodies never logged either)
        pass
    def _send(self, obj, code=200):
        b = json.dumps(obj).encode()
        self.send_response(code); self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(b))); self.end_headers(); self.wfile.write(b)
    def _body(self):
        n = int(self.headers.get('Content-Length') or 0)
        return self.rfile.read(n).decode('utf-8') if n else ''
    def do_GET(self):
        try:
            if self.path == '/health':
                return self._send({'ok': True})
            if self.path.startswith('/stamp/set?'):
                STAMPS[self.path.split('?', 1)[1]] = time.time(); return self._send({'ok': True})
            if self.path.startswith('/stamp/age?'):
                t = STAMPS.get(self.path.split('?', 1)[1]); return self._send({'age': -1 if t is None else int(time.time() - t)})
            if self.path == '/qr/screen':
                r = stub.getScreenshot(pb.ImageFormat(format=pb.ImageFormat.PNG))
                with tempfile.NamedTemporaryFile(suffix='.png', delete=True) as f:
                    f.write(r.image); f.flush()
                    res = subprocess.run([os.environ.get('QR_PY', sys.executable),
                                          os.path.join(os.path.dirname(os.path.abspath(__file__)), 'qr_decode.py'), f.name],
                                         capture_output=True, text=True, timeout=60)
                texts = json.loads(res.stdout or '[]')
                pub = re.compile(r'^(bitcoin:|tb1|bc1|[mn2][1-9A-HJ-NP-Za-km-z]{25,}|[vtuxyz]pub)', re.I)
                return self._send({'texts': [t for t in texts if pub.match(t)], 'count': len(texts)})
            if self.path == '/clip/info':
                t = stub.getClipboard(empty_pb2.Empty()).text
                return self._send({'len': len(t), 'prefix': t[:10]})
            self._send({'error': 'not found'}, 404)
        except Exception as e:
            self._send({'error': type(e).__name__}, 500)
    def do_POST(self):
        try:
            body = self._body()
            if self.path == '/clip/set':
                stub.setClipboard(pb.ClipData(text=body)); return self._send({'ok': True, 'len': len(body)})
            if self.path == '/clip/equals':
                t = stub.getClipboard(empty_pb2.Empty()).text
                return self._send({'equal': t == body, 'len': len(t)})
            self._send({'error': 'not found'}, 404)
        except Exception as e:
            self._send({'error': type(e).__name__}, 500)

ThreadingHTTPServer(('127.0.0.1', int(os.environ.get('HELPER_PORT', '8977'))), H).serve_forever()
