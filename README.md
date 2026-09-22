# FinTube

## Your YouTube, at home in Jellyfin

FinTube brings the YouTube channels you love into [Jellyfin](https://jellyfin.org/), so they feel like part of the library you already use. Follow creators, browse rich artwork and metadata, and play videos alongside the rest of your media—without giving up the privacy and control of self-hosting.

Built for homes, families, and shared servers, FinTube gives every person their own private YouTube library. Your subscriptions stay yours, while the server works quietly in the background to keep everything fresh and ready to watch.

## Why FinTube?

- **Make YouTube feel native.** Channels, videos, artwork, and playback live in the familiar Jellyfin experience.
- **Keep it personal.** Each user gets a separate, private library and their own subscriptions.
- **Stay in control.** FinTube is self-hosted: your data, credentials, and viewing setup remain on your server.
- **Always up to date.** New videos appear automatically, so your favourite creators are ready when you are.
- **Stream smarter.** Watch on demand or prepare recent videos ahead of time, without downloading an entire channel.
- **Enjoy more of YouTube.** Bring in regular videos, Shorts, and live-stream recordings.

Jellyfin never sees your YouTube credentials or direct YouTube links. FinTube handles the connection privately and efficiently behind the scenes.

## Requirements

The recommended installation uses Docker Compose and requires:

- A machine that can run [Docker Engine](https://docs.docker.com/engine/install/) and the [Docker Compose plugin](https://docs.docker.com/compose/install/).
- A running [Jellyfin server](https://jellyfin.org/downloads/) that can read FinTube's per-user library folders.
- Internet access to YouTube.
- A free Google account and a **YouTube Data API v3 key for each FinTube user**.
- Enough disk space for the database, generated Jellyfin metadata, and video cache. FinTube reserves 20 GB free by default; use a disk with at least 30 GB free for an initial installation.

The Docker image already contains Java, `yt-dlp`, its PO-token plugin, and `ffmpeg`. They do not need to be installed separately when using Docker.

## Run FinTube

FinTube is published as a [GitHub Container Registry package](https://github.com/users/Epsuchti/packages/container/package/fintube-server). Create an empty directory, add the following `compose.yaml`, and start it:

```yaml
services:
  fintube:
    image: ghcr.io/epsuchti/fintube-server:latest
    ports:
      - "8080:8080"
    environment:
      FINTUBE_DATA_DIR: /data
      # Set this in .env for deterministic encrypted settings across restores.
      FINTUBE_SETTINGS_KEY: ${FINTUBE_SETTINGS_KEY:-}
      # Optional. If absent, the service writes a one-time token to
      # /data/setup-admin-token until the first administrator is created.
      FINTUBE_SETUP_ADMIN_TOKEN: ${FINTUBE_SETUP_ADMIN_TOKEN:-}
      FINTUBE_YOUTUBE_PO_TOKEN_PROVIDER_URL: http://pot-provider:4416
    volumes:
      - fintube-data:/data
    depends_on:
      pot-provider:
        condition: service_started
    restart: unless-stopped

  # Deliberately has no published host port. Only FinTube can reach it over
  # this Compose network; do not expose this unauthenticated service publicly.
  pot-provider:
    image: brainicism/bgutil-ytdlp-pot-provider:2.0.0-node
    restart: unless-stopped

volumes:
  fintube-data:
```

```bash
mkdir fintube && cd fintube
# Save the Compose file above as compose.yaml, then:
touch .env
docker compose pull
docker compose up -d
```

Open `http://localhost:8080`, replacing `localhost` with the server's hostname or IP address when connecting from another device.

> The `latest` tag follows the newest published release. For a reproducible installation, replace `latest` with a specific version tag from the [package page](https://github.com/users/Epsuchti/packages/container/package/fintube-server), such as `0.1.10`. The image includes Java, `yt-dlp`, its PO-token plugin, and `ffmpeg`.

## First-time setup

### 1. Create the administrator

On first start, FinTube generates a one-time setup token. Retrieve it with either command:

```bash
docker compose logs fintube
docker compose exec fintube cat /data/setup-admin-token
```

Paste the token into the setup page, then choose the first administrator's username and password. The token file is deleted after the administrator is created.

### 2. Create a YouTube Data API key

Every FinTube user supplies their own key. This separates Google API quotas between users and lets FinTube resolve channels and refresh video metadata.

1. Sign in to the [Google Cloud Console](https://console.cloud.google.com/).
2. Create a project, or select an existing one.
3. Open the [YouTube Data API v3 page](https://console.cloud.google.com/apis/library/youtube.googleapis.com) and select **Enable**.
4. Open **APIs & Services → Credentials**.
5. Select **Create credentials → API key**.
6. Recommended: edit the key and restrict **API restrictions** to **YouTube Data API v3**. Application restrictions normally need to remain **None**, because requests come from the FinTube server and its public IP may change.
7. In FinTube, open **Account**, paste the key under **YouTube Data API**, and select **Save key**.

This is an API key, not an OAuth client secret and not a YouTube login token. Google documents the process in its [YouTube Data API credentials guide](https://developers.google.com/youtube/registering_an_application).

### 3. Connect Jellyfin

1. Sign in to Jellyfin as an administrator.
2. Open **Dashboard → API Keys**, create a key named `FinTube`, and copy it.
3. In FinTube, open **Global settings → Jellyfin** and enter:
   - **Jellyfin URL:** an address reachable from the FinTube container, such as `http://jellyfin:8096` when both services share a Docker network, or `http://192.168.1.20:8096` for another machine.
   - **Jellyfin API key:** the key created above.
4. Save the settings, open **Jellyfin integration**, and select **Validate connection**. Keep automatic refresh and runtime sync enabled.

FinTube writes one directory below `/data/users` for each FinTube user. Jellyfin must be able to read those directories. If Jellyfin is another Docker service, mount the FinTube data volume read-only, for example:

```yaml
services:
  jellyfin:
    volumes:
      - fintube-data:/media/fintube:ro

volumes:
  fintube-data:
    external: true
    name: fintube_fintube-data
```

The actual Docker volume name depends on the directory or Compose project name. Find it with:

```bash
docker volume ls | grep fintube-data
```

In Jellyfin, create a separate library for each directory under `/media/fintube/users`, then grant that library only to the matching Jellyfin user. Do not expose the parent `users` directory to every account.

#### Optional: remove watched videos and update YouTube history

FinTube can reconcile completed Jellyfin items during the normal subscription sync. In **Global settings**:

1. Set **Jellyfin user (name or ID)** to the Jellyfin account whose played state should be used.
2. Enable **Remove watched videos during sync**. FinTube removes the generated library directory, requests a Jellyfin refresh, and records a per-user tombstone so later syncs do not recreate it.
3. To update YouTube history too, place a persistent Netscape-format `cookies.txt` export for the desired YouTube account somewhere inside the FinTube data volume, set **YouTube watched-state cookie file** to its container path (for example `/data/youtube-history.cookies.txt`), and enable **Mark removed videos watched on YouTube**.

The watched-state cookie is deliberately separate from the optional **Cookie file** used for media fetching and is never passed to a download or playback-source request. YouTube history updates use `yt-dlp --mark-watched` because the official YouTube Data API does not provide a watch-history write operation. They are best-effort: expired cookies or upstream YouTube changes are logged and retried on a later sync without blocking subscription ingestion. Treat the cookie file like a password and use a dedicated YouTube-only browser profile.

### 4. Set the public playback URL

In FinTube, open **Global settings** and set **Public base URL** to the address Jellyfin and its clients use to reach FinTube, for example:

```text
http://192.168.1.10:8080
https://fintube.example.com
```

Do not leave this as `http://localhost:8080` unless Jellyfin runs on the same host without container isolation. Existing `.strm` files contain this address.

### 5. Add subscriptions

Each user can open **Subscriptions** and either:

- Add a channel using its URL, channel ID, or `@handle`; or
- Select **Import from YouTube** and upload a Netscape-format `cookies.txt` export.

For cookie import, use a dedicated YouTube-only browser profile and delete the export afterward. The file can grant access to the signed-in YouTube account. FinTube uses it once to discover subscriptions and then deletes its temporary copy. Channels absent from YouTube's subscription feed may need to be added manually.

## Tokens and credentials

| Credential | Required | Where to get it | Recommendation |
| --- | --- | --- | --- |
| FinTube setup token | Once | FinTube startup log or `/data/setup-admin-token` | Use it immediately; it is deleted after setup. |
| YouTube Data API key | Yes, per user | Google Cloud Console → APIs & Services → Credentials | Restrict it to YouTube Data API v3 and never publish it. |
| Jellyfin API key | Yes for Jellyfin integration | Jellyfin Dashboard → API Keys | Create a dedicated key named `FinTube`. |
| YouTube PO token | No | Automatically supplied by the included BgUtils provider | Leave the manual PO-token field blank and keep the provider enabled. |
| YouTube `cookies.txt` | No | Export from a browser profile signed in only to YouTube | Used once for subscription import, or stored persistently when YouTube watched-state sync is enabled; treat it like a password. |
| `FINTUBE_SETTINGS_KEY` | Recommended for backups | Generate locally, for example with `openssl rand -base64 32` | Set it before first start and store it with your backup secrets. Never change or lose it. |

The setup token, YouTube API key, Jellyfin API key, and PO token are different credentials and cannot replace one another.

## Recommended settings

The defaults are conservative and suitable for most installations:

| Setting | Recommended starting value | Notes |
| --- | --- | --- |
| Stream quality | `720p` | Increase after confirming server bandwidth, client codec support, and cache capacity. |
| Cache retention | `30 days` | Old inactive fragments are removed automatically. |
| Minimum free cache space | `20 GB` | Increase this on a large shared server. |
| Default videos to import | `20` | Imports metadata, not full media downloads. |
| Videos to pre-download | `0` | On-demand streaming avoids unexpected storage use. |
| Subscription sync interval | `60 minutes` | A good balance between freshness and API usage. |
| PO token provider | Enabled | Recommended; leave the manual PO-token field empty. |
| Jellyfin automatic refresh | Enabled | Makes newly generated items appear automatically. |
| Jellyfin runtime sync | Enabled | Corrects displayed durations after Jellyfin scans an item. |
| Remove watched videos during sync | Disabled | Enable after selecting the Jellyfin user whose play state should drive removal. |
| Mark removed videos watched on YouTube | Disabled | Requires the separate watched-state cookie file for the chosen YouTube account. |

Also set Jellyfin clients to a **Direct Play-friendly** or **Maximum** quality. FinTube chooses one shared YouTube source based on **Stream quality**; Jellyfin's bitrate selector does not change that source.

## Secure remote access

For access outside your trusted home network, put FinTube behind an HTTPS reverse proxy. Set a stable encryption key in `.env`:

```dotenv
FINTUBE_SETTINGS_KEY=replace-with-output-from-openssl-rand-base64-32
```

Also add secure cookies to the `fintube` service's `environment` section in `compose.yaml`:

```yaml
FINTUBE_COOKIE_SECURE: "true"
```

Set **Public base URL** to the external HTTPS URL. Do not publish the PO-token provider's port: it is intentionally reachable only by FinTube and has no authentication.

## Updating

From the directory containing `compose.yaml`:

```bash
docker compose pull fintube
docker compose up -d
```

Database migrations run automatically. Check startup and health after an update:

```bash
docker compose logs --tail=100 fintube
docker compose ps
```

## Backup and restore

Back up the `fintube-data` Docker volume and your `.env` file. The volume contains:

- The FinTube database.
- Generated Jellyfin `.strm`, `.nfo`, and thumbnail files.
- The shared media cache.
- `settings.key` when `FINTUBE_SETTINGS_KEY` was not supplied.

Encrypted credentials can only be restored with the same settings key. If you set `FINTUBE_SETTINGS_KEY`, back it up securely. If you did not, include `/data/settings.key` in every backup.

## Troubleshooting

### Jellyfin cannot play a video

- Confirm **Public base URL** is reachable from both Jellyfin and the playback client.
- Confirm Jellyfin can read the matching `/data/users/<user>` directory.
- Keep the PO-token provider enabled and inspect `docker compose logs fintube pot-provider`.
- Confirm the user's YouTube Data API key is configured in **Account**.

### iPad playback causes high CPU usage

The Jellyfin iPadOS app can request video transcoding for a FinTube HLS stream even when the iPad can play the source directly. On a CPU-only Jellyfin server, this can saturate the server and cause buffering.

For the affected Jellyfin user, open **Dashboard → Users → [user] → Media playback** and disable **Allow video playback that requires transcoding**. If Jellyfin still starts an audio transcode, also disable the corresponding audio-transcoding permission. Keep **Allow video playback that requires conversion without re-encoding** enabled so Jellyfin can still remux the stream when necessary. Select the iPad's **Native Video Player**, use **Auto** or the highest playback quality, and start a new session.

Verify the result in Jellyfin's **Dashboard → Activity**. The session should report **Direct Play** or **Remux/Direct Stream**, and the Jellyfin `ffmpeg` processes should no longer consume all CPU cores. Disabling transcoding is a per-user workaround: media that genuinely needs codec conversion, subtitle burn-in, or other video processing may fail instead of playing. Re-enable the permission for users or devices that need those conversions.

### A channel does not appear

- Add its full URL, channel ID, or `@handle` instead of searching by name.
- Check the user's Google API quota and ensure YouTube Data API v3 is enabled for the key's project.
- For cookie imports, add missing channels manually; YouTube does not always return every subscription in the feed.

### Disk use is too high

- Lower the per-channel download counts or leave them at `0`.
- Reduce **Cache retention**.
- Increase **Minimum free cache space** so cleanup starts sooner.

## Manual development installation

The Docker deployment is recommended for end users. Contributors running from source need Java 21, Node.js 24, npm 11, Python 3, `yt-dlp`, and `ffmpeg`. Development and verification commands are:

```bash
./server/deploy/setup-local-ytdlp.sh
./server/mvnw -f server/pom.xml spring-boot:run

cd client
npm install
npm start
```

Run the test suite with `cd server && ./mvnw test` and build the client with `cd client && npm run build`.
