# FinTube

FinTube is a self-hosted, multi-user YouTube-to-Jellyfin bridge. Jellyfin sees only local `.strm`, `.nfo`, thumbnail and backend playback URLs; it never receives a YouTube URL or API credential.

## Run

Install `yt-dlp` and `ffmpeg` on the server, then create the first administrator through the setup wizard:

On macOS with Homebrew, run `brew install yt-dlp ffmpeg`. If the app is started from IntelliJ and cannot inherit Homebrew's `PATH`, set the admin settings `yt_dlp_path` and `ffmpeg_path` to absolute paths such as `/opt/homebrew/bin/yt-dlp` and `/opt/homebrew/bin/ffmpeg`.

```bash
./server/mvnw -f server/pom.xml spring-boot:run
```

The application is at `http://localhost:8080`. When no administrator exists, it presents a setup wizard and prints a one-time token to the server console. FinTube also saves the generated token at `data/setup-admin-token` with owner-only permissions. The token is hashed in H2, is never returned by the API, and is deleted after the first admin is created. Configure the YouTube Data API key, public bridge URL, Jellyfin values, quality and cache policy in the admin screen afterward.

For development, run `./server/mvnw -f server/pom.xml spring-boot:run` and `cd client && npm start` in separate terminals. Angular's dev server rebuilds and reloads the browser; Spring Boot DevTools restarts the backend after compiled classes change. In IntelliJ, enable automatic project builds if you want Java changes to trigger the restart without invoking Maven manually.

Persistent state defaults to `./data` and can be relocated with `FINTUBE_DATA_DIR`. It contains a file-backed H2 database (`fintube-h2.mv.db`), `users/<safe-user-slug>/` Jellyfin trees and the global `cache/` tree. Liquibase applies the versioned schema changelogs automatically at startup. Map each individual user directory as a separate Jellyfin library; do not map the parent `users` directory to every Jellyfin account.

`initial_channel_import_count` controls metadata/history import for a newly added channel (default `20`). `newest_videos_to_download` controls optional low-priority media prefetch of each channel's newest videos (default `0`, disabled). Prefetch jobs fill the global shared cache once; they never download separately for each user.

## Architecture

- Accounts use BCrypt hashes and opaque HTTP-only server-side session cookies. Subscription/library queries are always filtered by authenticated user ID; admin endpoints require `ADMIN`.
- Canonical `youtube_channels` and `videos` rows are shared. User subscriptions and libraries are relationships, so media is not duplicated per user.
- `.strm` files contain a stable backend capability URL, never YouTube. The bridge produces a finite HLS VOD manifest and routes every segment through its cache.
- `FragmentManager` uses deterministic fragment paths, atomic writes and single-flight fetches per `(video, format, fragment)`. A seek asks only for the target fragment; expired URLs are refreshed through yt-dlp.
- `stream_quality` selects one shared direct-play source representation. Jellyfin's bitrate menu is not the source-quality selector.

## Jellyfin

Set clients to a Direct Play-friendly/Maximum setting. Configure a Jellyfin library per user path (for example `/data/users/eric`) and restrict that library to its matching Jellyfin account. FinTube never gives Jellyfin a YouTube URL.

The admin settings `jellyfin_url` and `jellyfin_api_key` enable the REST integration. `jellyfin_auto_refresh` coalesces refresh requests after generated `.strm`/`.nfo` files, and `jellyfin_runtime_sync` updates the scanned item's runtime in Jellyfin ticks (`seconds * 10,000,000`) once the item is visible. The admin API exposes `GET /api/admin/jellyfin/status`, `POST /api/admin/jellyfin/validate`, and `POST /api/admin/jellyfin/refresh` (the `/sync` alias is also available). Status responses include connectivity, server/version, scan and runtime-sync counters, but never include the API key.

## YouTube cookies and PO tokens

Both are optional administrator settings. `cookie_file` passes an exported Netscape cookie file to yt-dlp; it is encrypted at rest and never returned by the API. `youtube_po_token` accepts yt-dlp's `CLIENT.CONTEXT+TOKEN` value (for example `mweb.gvs+…`) and `youtube_player_client` selects the matching client.

The Compose deployment enables `youtube_po_token_provider_enabled` by default and installs the BgUtils yt-dlp plugin. Its default `youtube_po_token_provider_args` points to the internal `pot-provider` service. The provider has no host port and must stay that way: it is unauthenticated. Bare-metal deployments can install `bgutil-ytdlp-pot-provider` beside yt-dlp and set its documented provider endpoint, or set `youtube_po_token_provider_enabled=false`; FinTube retries a probe without the provider if it is unavailable. Provider arguments and manual tokens are encrypted and never passed through a shell. See the [yt-dlp PO Token Guide](https://github.com/yt-dlp/yt-dlp/wiki/PO-Token-Guide) and the [BgUtils provider instructions](https://github.com/Brainicism/bgutil-ytdlp-pot-provider).

## Verification

```bash
cd server && ./mvnw test
cd ../client && npm run build
```

## OpenAPI controller contracts

The application API contract is defined in `server/src/main/openapi/fintube.yaml`. Maven generates the Spring interfaces during `generate-sources`; the concrete `*ContractController` classes implement those interfaces, so endpoint paths and HTTP verbs are owned by the contract rather than duplicated in controller methods. The Jellyfin-facing playback contract is separate in `server/src/main/openapi/playback.yaml` and generates `PlaybackApi` for `/play/{video}` and its fragment route.
