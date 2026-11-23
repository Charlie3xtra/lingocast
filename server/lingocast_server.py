#!/usr/bin/env python3
"""Standalone LingoCast Subscription Feed v1 server (zero third-party dependencies).

Serves `/api/v1/feed.json`, `/feed.json`, `/subscribe.json`, `/catalog.json`,
`/health`, a landing page at `/`, and HTTP Range-capable static course pack media.
Automatically re-scans `packs_dir` when directory/manifest mtimes change so newly
added courses appear in the feed without restarting the server.
"""

from __future__ import annotations

import argparse
import hashlib
import html
import hmac
import json
import mimetypes
import os
import posixpath
import re
import socket
import sys
import threading
import time
from datetime import datetime, timezone
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any
from urllib.parse import parse_qs, unquote, urlparse

PROTOCOL_NAME = "lingocast-feed"
PROTOCOL_VERSION = 1

mimetypes.add_type("audio/mp4", ".m4a")
mimetypes.add_type("video/mp4", ".mp4")
mimetypes.add_type("application/json; charset=utf-8", ".json")
mimetypes.add_type("image/jpeg", ".jpg")
mimetypes.add_type("image/jpeg", ".jpeg")


def _utc_iso(ts: float | None = None) -> str:
    dt = datetime.fromtimestamp(ts if ts is not None else time.time(), tz=timezone.utc)
    return dt.strftime("%Y-%m-%dT%H:%M:%SZ")


def _compute_signature(packs_root: Path) -> tuple[float, tuple[tuple[str, float], ...]]:
    root_mtime = packs_root.stat().st_mtime if packs_root.exists() else 0.0
    parts: list[tuple[str, float]] = []
    cat = packs_root / "catalog.json"
    if cat.is_file():
        parts.append(("catalog.json", cat.stat().st_mtime))
    for child in sorted(packs_root.iterdir(), key=lambda p: p.name):
        if child.is_dir():
            parts.append((child.name, child.stat().st_mtime))
            mf = child / "manifest.json"
            if mf.is_file():
                parts.append((f"{child.name}/manifest.json", mf.stat().st_mtime))
    return (root_mtime, tuple(parts))


class FeedIndex:
    """Thread-safe auto-refreshing subscription feed & catalog index."""

    def __init__(self, packs_root: Path, title: str, description: str) -> None:
        self.packs_root = packs_root.resolve()
        self.title = title
        self.description = description
        self._lock = threading.Lock()
        self._last_sig: tuple[Any, ...] | None = None
        self._cached_feed: dict[str, Any] = {}
        self._cached_catalog: dict[str, Any] = {}
        self._cached_updated_at: str = _utc_iso()

    def snapshot(self) -> tuple[dict[str, Any], dict[str, Any]]:
        with self._lock:
            sig = _compute_signature(self.packs_root)
            if sig != self._last_sig:
                feed, catalog = self._build_index()
                self._cached_feed = feed
                self._cached_catalog = catalog
                self._last_sig = sig
            return self._cached_feed, self._cached_catalog

    def _build_index(self) -> tuple[dict[str, Any], dict[str, Any]]:
        series_list: list[dict[str, Any]] = []
        total_episodes = 0
        total_duration_ms = 0
        total_audio_bytes = 0
        total_video_bytes = 0
        latest_mtime = 0.0

        catalog_file = self.packs_root / "catalog.json"
        existing_meta: dict[str, dict[str, Any]] = {}
        if catalog_file.is_file():
            try:
                raw_cat = json.loads(catalog_file.read_text(encoding="utf-8"))
                for s in raw_cat.get("series", []):
                    if isinstance(s, dict) and s.get("seriesId"):
                        existing_meta[str(s["seriesId"])] = s
                latest_mtime = max(latest_mtime, catalog_file.stat().st_mtime)
            except (OSError, json.JSONDecodeError):
                pass

        for sub in sorted(self.packs_root.iterdir(), key=lambda p: p.name):
            if not sub.is_dir():
                continue
            mf_path = sub / "manifest.json"
            if not mf_path.is_file():
                continue
            try:
                manifest = json.loads(mf_path.read_text(encoding="utf-8"))
                latest_mtime = max(latest_mtime, mf_path.stat().st_mtime)
            except (OSError, json.JSONDecodeError):
                continue

            series_id = str(manifest.get("seriesId") or sub.name)
            episodes = manifest.get("episodes", [])
            ep_count = len(episodes) if isinstance(episodes, list) else int(manifest.get("episodeCount", 0))
            dur_ms = int(manifest.get("totalDurationMs", 0))
            audio_bytes = sum(int(ep.get("audioSizeBytes", 0)) for ep in episodes if isinstance(ep, dict))
            video_bytes = sum(int(ep.get("videoSizeBytes", 0)) for ep in episodes if isinstance(ep, dict))

            prev = existing_meta.get(series_id, {})
            cover_rel = str(manifest.get("coverPath") or prev.get("coverPath") or "")
            if cover_rel and not cover_rel.startswith(f"{series_id}/"):
                cover_rel = f"{series_id}/{cover_rel.lstrip('/')}"
            lcp_file = prev.get("lcpFile")

            series_entry = {
                "seriesId": series_id,
                "title": str(manifest.get("title") or prev.get("title") or series_id),
                "titleZh": str(manifest.get("titleZh") or prev.get("titleZh") or series_id),
                "description": str(manifest.get("description") or prev.get("description") or ""),
                "accent": str(manifest.get("accent") or prev.get("accent") or ""),
                "cefrRange": str(manifest.get("cefrRange") or prev.get("cefrRange") or ""),
                "coverPath": cover_rel,
                "manifestPath": f"{series_id}/manifest.json",
                "episodeCount": ep_count,
                "totalDurationMs": dur_ms,
                "audioTotalBytes": audio_bytes,
                "videoTotalBytes": video_bytes,
                "lcpFile": lcp_file,
            }
            series_list.append(series_entry)
            total_episodes += ep_count
            total_duration_ms += dur_ms
            total_audio_bytes += audio_bytes
            total_video_bytes += video_bytes

        updated_at = _utc_iso(latest_mtime if latest_mtime > 0 else time.time())
        feed_id = hashlib.sha256(self.title.encode("utf-8")).hexdigest()[:12]

        catalog_series = [
            {k: v for k, v in item.items() if k in {
                "seriesId", "title", "titleZh", "description", "accent",
                "cefrRange", "coverPath", "manifestPath", "episodeCount",
                "totalDurationMs", "audioTotalBytes", "videoTotalBytes", "lcpFile"
            }}
            for item in series_list
        ]

        feed_doc = {
            "protocol": PROTOCOL_NAME,
            "protocolVersion": PROTOCOL_VERSION,
            "feedId": f"lingocast-{feed_id}",
            "title": self.title,
            "description": self.description,
            "updatedAt": updated_at,
            "requiresAuth": False,
            "totalSeries": len(series_list),
            "totalEpisodes": total_episodes,
            "totalDurationMs": total_duration_ms,
            "totalAudioBytes": total_audio_bytes,
            "totalVideoBytes": total_video_bytes,
            "series": series_list,
        }
        catalog_doc = {
            "schemaVersion": 1,
            "generatedAt": updated_at,
            "totalEpisodes": total_episodes,
            "totalDurationMs": total_duration_ms,
            "series": catalog_series,
        }
        return feed_doc, catalog_doc


class SubscriptionHTTPHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "LingoCastFeed/1.1"

    def log_message(self, format: str, *args: Any) -> None:
        sys.stderr.write(f"[feed] {self.address_string()} - {format % args}\n")

    @property
    def app_server(self) -> "LingoCastHTTPServer":
        return self.server  # type: ignore[return-value]

    def _send_cors_headers(self) -> None:
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, HEAD, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Authorization, Range, Content-Type")
        self.send_header("Access-Control-Expose-Headers", "Content-Range, Content-Length, Accept-Ranges, ETag")
        self.send_header("Accept-Ranges", "bytes")

    def _check_auth(self, parsed_query: dict[str, list[str]]) -> bool:
        expected = self.app_server.auth_token
        if not expected:
            return True
        auth = self.headers.get("Authorization", "")
        candidate = ""
        if auth.lower().startswith("bearer "):
            candidate = auth[7:].strip()
        elif "token" in parsed_query and parsed_query["token"]:
            candidate = parsed_query["token"][0].strip()
        if candidate and hmac.compare_digest(candidate, expected):
            return True
        payload = json.dumps(
            {"error": "unauthorized", "message": "Valid subscription token required."},
            ensure_ascii=False,
        ).encode("utf-8")
        self.send_response(HTTPStatus.UNAUTHORIZED)
        self._send_cors_headers()
        self.send_header("WWW-Authenticate", 'Bearer realm="LingoCast Feed"')
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(payload)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(payload)
        return False

    def _send_json(self, obj: dict[str, Any], status: HTTPStatus = HTTPStatus.OK, cache: str = "no-cache") -> None:
        raw = json.dumps(obj, ensure_ascii=False, indent=2).encode("utf-8")
        etag = f'"{hashlib.md5(raw).hexdigest()}"'
        self.send_response(status)
        self._send_cors_headers()
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(raw)))
        self.send_header("Cache-Control", cache)
        self.send_header("ETag", etag)
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(raw)

    def _send_landing_page(self, feed: dict[str, Any]) -> None:
        title = html.escape(str(feed.get("title", "LingoCast Feed")))
        desc = html.escape(str(feed.get("description", "")))
        total_eps = int(feed.get("totalEpisodes", 0))
        total_series = int(feed.get("totalSeries", 0))
        audio_mb = round(int(feed.get("totalAudioBytes", 0)) / (1024 * 1024), 1)
        video_mb = round(int(feed.get("totalVideoBytes", 0)) / (1024 * 1024), 1)
        hours = round(int(feed.get("totalDurationMs", 0)) / 3_600_000, 1)
        requires_auth = bool(self.app_server.auth_token)
        series_cards = []
        for s in feed.get("series", []):
            st = html.escape(str(s.get("titleZh") or s.get("title")))
            sc = html.escape(str(s.get("cefrRange", "")))
            seps = int(s.get("episodeCount", 0))
            series_cards.append(f"<li><strong>{st}</strong> ({seps} 集 · {sc})</li>")
        series_html = "\n".join(series_cards)
        auth_note = "<p><strong>鉴权</strong>：此订阅已开启口令保护，请在 LingoCast App 同步页面填写访问口令。</p>" if requires_auth else "<p><strong>公开订阅</strong>：复制下方订阅地址，粘贴到 LingoCast App 的「导入 → 课程订阅源」即可下载学习。</p>"

        page = f"""<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width,initial-scale=1" />
  <title>{title}</title>
  <style>
    body {{ font-family: system-ui, -apple-system, sans-serif; max-width: 720px; margin: 40px auto; padding: 0 20px; line-height: 1.6; color: #1a1c1e; background: #f8f9fc; }}
    .card {{ background: #fff; border-radius: 20px; padding: 24px; box-shadow: 0 4px 18px rgba(0,0,0,0.06); }}
    code {{ background: #eef2f7; padding: 4px 8px; border-radius: 8px; word-break: break-all; display: inline-block; }}
    button {{ background: #1d4e89; color: #fff; border: 0; border-radius: 999px; padding: 10px 18px; font-weight: 600; cursor: pointer; margin-top: 10px; }}
  </style>
</head>
<body>
  <div class="card">
    <h1>{title}</h1>
    <p>{desc}</p>
    <p>共 <strong>{total_series}</strong> 个专辑 · <strong>{total_eps}</strong> 集 · 总时长 <strong>{hours}</strong> 小时（音频 {audio_mb} MB / 视频 {video_mb} MB）</p>
    {auth_note}
    <p><code id="feedUrl"></code></p>
    <button onclick="navigator.clipboard?.writeText(document.getElementById('feedUrl').textContent).then(()=>this.textContent='已复制订阅地址')">复制订阅地址</button>
    <h3>包含专辑</h3>
    <ul>{series_html}</ul>
  </div>
  <script>document.getElementById('feedUrl').textContent = window.location.origin + '/feed.json';</script>
</body>
</html>"""
        raw = page.encode("utf-8")
        self.send_response(HTTPStatus.OK)
        self._send_cors_headers()
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(raw)))
        self.send_header("Cache-Control", "no-cache")
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(raw)

    def _serve_file(self, rel_path: str) -> None:
        norm = posixpath.normpath("/" + rel_path).lstrip("/")
        if not norm or norm.startswith(".."):
            self.send_error(HTTPStatus.FORBIDDEN, "Forbidden")
            return
        packs_root = self.app_server.feed_index.packs_root
        try:
            target = (packs_root / norm).resolve()
        except OSError:
            self.send_error(HTTPStatus.BAD_REQUEST, "Invalid path")
            return
        if not target.is_relative_to(packs_root) or not target.is_file():
            self.send_error(HTTPStatus.NOT_FOUND, "File not found")
            return

        st = target.stat()
        file_size = st.st_size
        etag = f'"{file_size:x}-{int(st.st_mtime):x}"'
        ctype, _ = mimetypes.guess_type(str(target))
        if not ctype:
            ctype = "application/octet-stream"
        cache_ctrl = "no-cache" if target.suffix.lower() == ".json" else "public, max-age=86400"

        range_header = self.headers.get("Range")
        start = 0
        end = file_size - 1
        partial = False

        if range_header:
            m = re.match(r"bytes=(\d*)-(\d*)", range_header.strip())
            if m:
                g1, g2 = m.group(1), m.group(2)
                if g1 == "" and g2 != "":
                    suffix = int(g2)
                    if suffix <= 0:
                        self.send_error(HTTPStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                        return
                    start = max(0, file_size - suffix)
                else:
                    start = int(g1) if g1 else 0
                    end = int(g2) if g2 else file_size - 1
                if file_size == 0 or start >= file_size or end < start:
                    self.send_response(HTTPStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                    self._send_cors_headers()
                    self.send_header("Content-Range", f"bytes */{file_size}")
                    self.send_header("Content-Length", "0")
                    self.end_headers()
                    return
                end = min(end, file_size - 1)
                partial = True

        length = max(0, end - start + 1) if file_size > 0 else 0
        self.send_response(HTTPStatus.PARTIAL_CONTENT if partial else HTTPStatus.OK)
        self._send_cors_headers()
        self.send_header("Content-Type", ctype)
        if partial:
            self.send_header("Content-Range", f"bytes {start}-{end}/{file_size}")
        self.send_header("Content-Length", str(length))
        self.send_header("Cache-Control", cache_ctrl)
        self.send_header("ETag", etag)
        self.end_headers()

        if self.command == "HEAD" or length == 0:
            return

        bufsize = 64 * 1024
        with target.open("rb") as fh:
            fh.seek(start)
            remaining = length
            while remaining > 0:
                chunk = fh.read(min(bufsize, remaining))
                if not chunk:
                    break
                try:
                    self.wfile.write(chunk)
                except (BrokenPipeError, ConnectionResetError):
                    break
                remaining -= len(chunk)

    def _handle_request(self) -> None:
        parsed = urlparse(self.path)
        req_path = unquote(parsed.path)
        query = parse_qs(parsed.query)
        feed, catalog = self.app_server.feed_index.snapshot()
        requires_auth = bool(self.app_server.auth_token)
        feed_out = dict(feed)
        feed_out["requiresAuth"] = requires_auth

        if req_path == "/health":
            self._send_json(
                {
                    "status": "ok",
                    "protocol": PROTOCOL_NAME,
                    "protocolVersion": PROTOCOL_VERSION,
                    "requiresAuth": requires_auth,
                    "totalSeries": feed_out.get("totalSeries", 0),
                    "totalEpisodes": feed_out.get("totalEpisodes", 0),
                    "updatedAt": feed_out.get("updatedAt", ""),
                }
            )
            return

        if req_path in ("/", "/index.html"):
            self._send_landing_page(feed_out)
            return

        if not self._check_auth(query):
            return

        if req_path in ("/api/v1/feed.json", "/feed.json", "/subscribe.json"):
            self._send_json(feed_out)
            return

        if req_path == "/catalog.json":
            self._send_json(catalog)
            return

        self._serve_file(req_path.lstrip("/"))

    def do_OPTIONS(self) -> None:
        self.send_response(HTTPStatus.NO_CONTENT)
        self._send_cors_headers()
        self.send_header("Content-Length", "0")
        self.end_headers()

    def do_HEAD(self) -> None:
        self._handle_request()

    def do_GET(self) -> None:
        self._handle_request()


class LingoCastHTTPServer(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True

    def __init__(self, server_address: tuple[str, int], feed_index: FeedIndex, auth_token: str = "") -> None:
        self.feed_index = feed_index
        self.auth_token = auth_token.strip()
        super().__init__(server_address, SubscriptionHTTPHandler)


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


def main() -> None:
    parser = argparse.ArgumentParser(description="Run standalone LingoCast Subscription Feed v1 HTTP server")
    parser.add_argument(
        "--packs-dir",
        default=os.environ.get("LINGOCAST_PACKS_DIR", "./packs"),
        help="Path to LingoCast packs directory containing series folders / catalog.json",
    )
    parser.add_argument("--host", default=os.environ.get("LINGOCAST_HOST", "0.0.0.0"), help="Bind host (default 0.0.0.0)")
    parser.add_argument("--port", type=int, default=int(os.environ.get("LINGOCAST_PORT", "8765")), help="Listen port (default 8765)")
    parser.add_argument(
        "--title",
        default=os.environ.get("LINGOCAST_TITLE", "LingoCast 英语精听课程订阅"),
        help="Subscription feed display title",
    )
    parser.add_argument(
        "--description",
        default=os.environ.get(
            "LINGOCAST_DESCRIPTION",
            "同步含词级卡拉 OK 字幕、离线精讲笔记与原画音视频的英语学习课程库。",
        ),
        help="Subscription feed description",
    )
    parser.add_argument(
        "--token",
        default=os.environ.get("LINGOCAST_TOKEN", ""),
        help="Optional Bearer/query access token (leave empty for public feed)",
    )
    args = parser.parse_args()

    packs_root = Path(args.packs_dir).expanduser().resolve()
    if not packs_root.is_dir():
        raise SystemExit(f"[error] Packs directory not found: {packs_root}")

    feed_index = FeedIndex(packs_root, title=args.title, description=args.description)
    server = LingoCastHTTPServer((args.host, args.port), feed_index, auth_token=args.token)
    feed, _ = feed_index.snapshot()
    print(
        f"[lingocast-feed] Serving {feed.get('totalSeries', 0)} series / {feed.get('totalEpisodes', 0)} episodes from {packs_root}",
        flush=True,
    )
    for ip in get_lan_ips():
        print(f"  -> LAN Feed URL: http://{ip}:{args.port}/feed.json", flush=True)
    if args.token:
        print("  -> Token authentication: ENABLED", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n[lingocast-feed] Stopped.", flush=True)
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
