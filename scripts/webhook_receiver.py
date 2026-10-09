"""Tiny local webhook receiver for manual tests: prints every APG webhook and verifies its signature.

Usage: python scripts/webhook_receiver.py <signing-secret> [port]   (default port 9099)
Point a webhook endpoint at http://localhost:9099/hook (dev only: needs MERCHANT_ALLOW_INSECURE_WEBHOOK_URLS=true).
"""
import hashlib
import hmac
import sys
import time
from http.server import BaseHTTPRequestHandler, HTTPServer

SECRET = sys.argv[1].encode() if len(sys.argv) > 1 else b""
PORT = int(sys.argv[2]) if len(sys.argv) > 2 else 9099


class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        body = self.rfile.read(int(self.headers.get("Content-Length", 0)))
        sig = self.headers.get("X-APG-Signature", "")
        parts = dict(p.split("=", 1) for p in sig.split(",") if "=" in p)
        expected = hmac.new(SECRET, (parts.get("t", "") + "." + body.decode()).encode(), hashlib.sha256).hexdigest()
        fresh = abs(time.time() - int(parts.get("t", "0") or 0)) < 300
        ok = hmac.compare_digest(expected, parts.get("v1", "")) and fresh
        print(f"[{time.strftime('%H:%M:%S')}] {self.headers.get('X-APG-Event-Type')} id={self.headers.get('X-APG-Event-Id')} "
              f"signature={'VALID' if ok else 'INVALID'} body={body.decode()}", flush=True)
        self.send_response(200 if ok else 400)
        self.end_headers()
        self.wfile.write(b"ok")

    def log_message(self, *args):
        pass


HTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
