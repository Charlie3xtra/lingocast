# LingoCast Subscription Feed Protocol v1

LingoCast Subscription Feeds expose an auto-scanned library of bilingual English listening courses over standard HTTP/HTTPS (compatible with LAN servers, reverse proxies, and Cloudflare Tunnel) without hardcoding server URLs in client apps.

## Endpoints

| Method | Path | Auth Required | Purpose |
|---|---|---|---|
| `GET` | `/health` | No | Liveness & library summary (`status`, `totalSeries`, `totalEpisodes`, `requiresAuth`, `updatedAt`) |
| `GET` | `/` | No | Human-friendly landing page with one-click subscription URL copy |
| `GET` | `/api/v1/feed.json` | Optional | Primary Subscription Feed v1 metadata & series list (including per-series `audioTotalBytes` / `videoTotalBytes`) |
| `GET` | `/feed.json`, `/subscribe.json` | Optional | Convenient aliases returning the exact same JSON as `/api/v1/feed.json` |
| `GET` | `/catalog.json` | Optional | Backward-compatible catalog format |
| `GET` / `HEAD` | `/<seriesId>/manifest.json` | Optional | Episode index for a series |
| `GET` / `HEAD` | `/<seriesId>/<episodeId>/{episode.json,audio.m4a,video.mp4,cover.jpg}` | Optional | Episode assets with full HTTP `Range` (`206 Partial Content`) resume support |

## Authentication (Optional)

When launched with `--token <secret>` or `LINGOCAST_TOKEN=<secret>`, all endpoints except `/health` and `/` require either:

- HTTP header: `Authorization: Bearer <secret>`
- Or query parameter: `?token=<secret>`

## JSON Response Format (`/api/v1/feed.json`)

```json
{
  "protocol": "lingocast-feed",
  "protocolVersion": 1,
  "feedId": "lingocast-...",
  "title": "LingoCast 英语精听课程订阅",
  "description": "...",
  "updatedAt": "2026-11-23T12:00:00Z",
  "requiresAuth": false,
  "totalSeries": 2,
  "totalEpisodes": 56,
  "totalDurationMs": 96192000,
  "totalAudioBytes": 638700000,
  "totalVideoBytes": 2765000000,
  "series": [
    {
      "seriesId": "talk-podcast",
      "title": "Talk Podcast",
      "titleZh": "Talk Podcast · 分级英语对话精听",
      "description": "...",
      "accent": "General / International",
      "cefrRange": "A2–B2",
      "coverPath": "talk-podcast/ep_001/cover.jpg",
      "manifestPath": "talk-podcast/manifest.json",
      "episodeCount": 40,
      "totalDurationMs": 39800000,
      "audioTotalBytes": 304000000,
      "videoTotalBytes": 860000000,
      "lcpFile": null
    }
  ]
}
```

## Auto-Refresh on New Courses

The server monitors `packs_dir` and each series' `manifest.json` modification timestamp. Simply copy new course directories (or update `manifest.json`) into `packs_dir`; clients refreshing their subscription immediately see new episodes without restarting the daemon.
