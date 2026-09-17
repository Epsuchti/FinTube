# FinTube

FinTube is a self-hosted, multi-user YouTube-to-Jellyfin bridge. Jellyfin sees only local `.strm`, `.nfo`, thumbnail and backend playback URLs; it never receives a YouTube URL or API credential.

## Run

Install `yt-dlp` on the server, then bootstrap the first admin only once:

```bash
ADMIN_USERNAME=admin ADMIN_PASSWORD='change-this-to-a-long-secret' ./server/mvnw -f server/pom.xml spring-boot:run
```

The application is at `http://localhost:8080`. Configure the YouTube Data API key, public bridge URL, Jellyfin values, quality and cache policy in the admin screen. Bootstrap values are ignored after a user exists.

Persistent state defaults to `./data` and can be relocated with `FINTUBE_DATA_DIR`. It contains a SQLite database, `users/<safe-user-slug>/` Jellyfin trees and the global `cache/` tree. Map each individual user directory as a separate Jellyfin library; do not map the parent `users` directory to every Jellyfin account.

## Architecture

- Accounts use BCrypt hashes and opaque HTTP-only server-side session cookies. Subscription/library queries are always filtered by authenticated user ID; admin endpoints require `ADMIN`.
- Canonical `youtube_channels` and `videos` rows are shared. User subscriptions and libraries are relationships, so media is not duplicated per user.
- `.strm` files contain a stable backend capability URL, never YouTube. The bridge produces a finite HLS VOD manifest and routes every segment through its cache.
- `FragmentManager` uses deterministic fragment paths, atomic writes and single-flight fetches per `(video, format, fragment)`. A seek asks only for the target fragment; expired URLs are refreshed through yt-dlp.
- `stream_quality` selects one shared direct-play source representation. Jellyfin's bitrate menu is not the source-quality selector.

## Jellyfin

Set clients to a Direct Play-friendly/Maximum setting. Configure a Jellyfin library per user path (for example `/data/users/eric`) and restrict that library to its matching Jellyfin account. FinTube never gives Jellyfin a YouTube URL.

The admin settings `jellyfin_url` and `jellyfin_api_key` enable the REST integration. `jellyfin_auto_refresh` coalesces refresh requests after generated `.strm`/`.nfo` files, and `jellyfin_runtime_sync` updates the scanned item's runtime in Jellyfin ticks (`seconds * 10,000,000`) once the item is visible. The admin API exposes `GET /api/admin/jellyfin/status`, `POST /api/admin/jellyfin/validate`, and `POST /api/admin/jellyfin/refresh` (the `/sync` alias is also available). Status responses include connectivity, server/version, scan and runtime-sync counters, but never include the API key.

## Verification

```bash
cd server && ./mvnw test
cd ../client && npm run build
```
