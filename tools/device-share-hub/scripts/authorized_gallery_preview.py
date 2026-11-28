"""Isolated TLS-only authorization preview; never a replacement for production service.

Run only against a disposable test library and a certificate that the test
client verifies out of band. No LAN discovery, ADB reverse, clipboard watcher
or cloud enrollment is started here. Existing production credentials are not
imported; the normal gallery service and mobile apps remain unchanged.
"""
import argparse
import json
import ssl
from http.server import ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlsplit

from device_authorization import Registry
from online_gallery_service import OnlineGalleryHandler, WorkScanner

class AuthorizedGalleryHandler(OnlineGalleryHandler):
    registry = None

    def send_cors_headers(self):
        # Native clients do not require browser CORS. No remote admin UI.
        self.send_header("Cache-Control", "no-store")

    def _token(self):
        value = self.headers.get("Authorization", "")
        return value[7:] if value.startswith("Bearer ") else ""

    def _secure(self):
        return isinstance(self.connection, ssl.SSLSocket)

    def _gate(self, method, path):
        if not self._secure():
            self.send_json(403, {"ok": False, "error": "TLS required"})
            return False
        try:
            allowed = self.registry.authorize(self._token(), method, path)
        except Exception:
            self.send_json(503, {"ok": False, "error": "Authorization unavailable"})
            return False
        if not allowed:
            self.send_json(403, {"ok": False, "error": "Device permission required"})
        return allowed

    def do_OPTIONS(self):
        self.send_json(405, {"ok": False})

    def do_GET(self):
        path = urlsplit(self.path).path
        if not self._secure():
            return self.send_json(403, {"ok": False, "error": "TLS required"})
        if path == "/api/online/ping":
            return self.send_json(200, {"ok": True, "pairingRequired": True})
        if path == "/api/online/device-pair/status":
            try:
                return self.send_json(200, self.registry.status(self._token()))
            except ValueError:
                return self.send_json(401, {"ok": False, "error": "Invalid device credential"})
        if self._gate("GET", path):
            return super().do_GET()

    def do_POST(self):
        path = urlsplit(self.path).path
        if path == "/api/online/device-pair/request":
            if not self._secure():
                return self.send_json(403, {"ok": False, "error": "TLS required"})
            try:
                size = int(self.headers.get("Content-Length", "0"))
                if not 0 < size <= 4096:
                    raise ValueError("invalid length")
                body = json.loads(self.rfile.read(size))
                if not isinstance(body, dict):
                    raise ValueError("invalid body")
                status = self.registry.request(self._token(), body.get("name"))
                return self.send_json(200, status)
            except (ValueError, UnicodeError):
                return self.send_json(400, {"ok": False, "error": "Invalid pairing request"})
        if self._gate("POST", path):
            return super().do_POST()

    # Override the legacy opportunistic sync: it is outside the preview scope.
    def _maybe_background_phone_sync(self, *args, **kwargs):
        return None

def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--root", required=True, help="Disposable test library only")
    p.add_argument("--db", required=True, help="Disposable authorization database")
    p.add_argument("--cert", required=True)
    p.add_argument("--key", required=True)
    p.add_argument("--host", default="127.0.0.1")
    p.add_argument("--port", type=int, default=45936)
    args = p.parse_args()
    if not Path(args.root).is_dir():
        p.error("test library must exist")
    scanner = WorkScanner(args.root)
    scanner.scan(force=True)
    AuthorizedGalleryHandler.scanner = scanner
    AuthorizedGalleryHandler.registry = Registry(args.db)
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    ctx.minimum_version = ssl.TLSVersion.TLSv1_2
    ctx.load_cert_chain(args.cert, args.key)
    with ThreadingHTTPServer((args.host, args.port), AuthorizedGalleryHandler) as server:
        server.scanner = scanner
        server.socket = ctx.wrap_socket(server.socket, server_side=True)
        print("Isolated authorization preview listening on HTTPS port", args.port)
        server.serve_forever()

if __name__ == "__main__":
    main()
