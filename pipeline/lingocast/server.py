"""LAN Wi-Fi HTTP sync server with HTTP Range support for Android course pack import."""

from __future__ import annotations

import mimetypes
import os
import re
import socket
from functools import partial
from http import HTTPStatus
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path


class RangeHTTPRequestHandler(SimpleHTTPRequestHandler):
    """HTTP handler supporting byte-range requests so Android resume & video download work smoothly."""

    def end_headers(self) -> None:
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Access-Control-Allow-Origin", "*")
        super().end_headers()

    def send_head(self):
        path = Path(self.translate_path(self.path))
        if path.is_dir():
            return super().send_head()
        if not path.is_file():
            self.send_error(HTTPStatus.NOT_FOUND, "File not found")
            return None

        range_header = self.headers.get("Range")
        file_size = path.stat().st_size
        ctype, _ = mimetypes.guess_type(str(path))
        if not ctype:
            ctype = "application/octet-stream"

        if range_header:
            m = re.match(r"bytes=(\d*)-(\d*)", range_header.strip())
            if m:
                start = int(m.group(1)) if m.group(1) else 0
                end = int(m.group(2)) if m.group(2) else file_size - 1
                if start >= file_size or end < start:
                    self.send_error(HTTPStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                    return None
                end = min(end, file_size - 1)
                length = end - start + 1
                fh = path.open("rb")
                fh.seek(start)
                self.send_response(HTTPStatus.PARTIAL_CONTENT)
                self.send_header("Content-Type", ctype)
                self.send_header("Content-Range", f"bytes {start}-{end}/{file_size}")
                self.send_header("Content-Length", str(length))
                self.end_headers()
                self._range_limit = length
                return fh

        self._range_limit = None
        fh = path.open("rb")
        self.send_response(HTTPStatus.OK)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(file_size))
        self.end_headers()
        return fh

    def copyfile(self, source, outputfile) -> None:
        limit = getattr(self, "_range_limit", None)
        if limit is None:
            super().copyfile(source, outputfile)
            return
        remaining = limit
        bufsize = 64 * 1024
        while remaining > 0:
            chunk = source.read(min(bufsize, remaining))
            if not chunk:
                break
            outputfile.write(chunk)
            remaining -= len(chunk)


def get_lan_ips() -> list[str]:
    ips: list[str] = []
    try:
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.connect(("8.8.8.8", 80))
        ips.append(sock.getsockname()[0])
        sock.close()
    except OSError:
        pass
    try:
        hostname = socket.gethostname()
        for info in socket.getaddrinfo(hostname, None, socket.AF_INET):
            ip = info[4][0]
            if ip not in ips and not ip.startswith("127."):
                ips.append(ip)
    except OSError:
        pass
    return ips or ["127.0.0.1"]


def serve_packs(packs_root: Path, port: int = 8765, bind: str = "0.0.0.0") -> None:
    if not packs_root.is_dir():
        raise FileNotFoundError(f"Packs directory not found: {packs_root}")
    handler = partial(RangeHTTPRequestHandler, directory=str(packs_root))
    server = ThreadingHTTPServer((bind, port), handler)
    ips = get_lan_ips()
    print(f"[serve] Serving LingoCast packs from: {packs_root}")
    for ip in ips:
        print(f"  -> Android Wi-Fi Sync URL: http://{ip}:{port}")
    print("Press Ctrl+C to stop.")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n[serve] Stopped.")
    finally:
        server.server_close()
