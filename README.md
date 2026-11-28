# Swarm 1.3.1 — torrent app for Android

Original app. No ads, no analytics, no accounts. Swarm talks to the BitTorrent network, to the search
sources you switch on, and to GitHub (at most twice a day) to see whether a newer version is out.

The torrent engine is [libtorrent](https://www.libtorrent.org) (BSD 3-Clause) through
[libtorrent4j](https://github.com/aldenml/libtorrent4j) (MIT). The players use AndroidX Media3
(Apache-2.0) with the FFmpeg decoder from
[jellyfin-androidx-media](https://github.com/jellyfin/jellyfin-androidx-media) (GPL-3.0), which is why
Swarm as a whole is released under the GNU General Public License, version 3. See `LICENSE`.

Website, download and release notes: https://torusapps.com/swarm/

## What it does

- **Torrents tab**: All / Downloading / Complete, pause and resume one or all, filter, selection for
  sharing or deleting several. Add by magnet link, info-hash, the address of a `.torrent` file or a QR
  code. Magnet links and torrent files opened in other apps come to Swarm.
- **Torrent page**: progress, speed graph and piece map; Files (untick, download one first), Peers and
  Trackers tabs; per-torrent speed limits, re-check, queue up and down, Play now (downloads in order).
- **Search tab**: keyword search across the enabled sources with seeders, leechers, size and age, live
  tracker scrape, sorting and a category filter. Built-in sources are the Internet Archive and Linux
  Tracker. Add your own as a Torznab address (Jackett, Prowlarr) or the address of a site's results page.
- **DHT tab**: a crawler (BEP 51 sampling plus what the node hears) fetches names and file lists into a
  full-text index kept on the phone. When it runs and how big the index may grow are settings.
- **Video and Music tabs**: built-in players; music carries on in the background.
- **Settings**: theme, Wi-Fi only, speed limits, active torrent limit, connection limit, download folder,
  sort into folders, port, public trackers, encryption, seeding (ratio / stop / keep going), VPN warning
  or block, safety checks, adult filter, IP blocklist, Auto-Shutdown, battery saver, scheduled hours,
  only while charging, crawler mode and index size.
- **Help**: a searchable Help page (top of Settings and in the side menu) explaining every tab and setting, with
  step-by-step guides for adding a website source and a Torznab / Jackett address, and what the DHT is.
- **Side menu**: search sources, RSS feeds with filters and automatic downloading, watchlist for saved
  searches, create a torrent, a browser that picks up torrent links, backup and restore, update check.

Only download and share things you have the right to.

## Build

1. Open the folder in Android Studio (Ladybug or newer) and let Gradle sync, or run `./gradlew assembleDebug`.
2. For a signed release, put your keystore in this folder with a `keystore.properties` beside it:

   ```
   storeFile=your-release.jks
   storePassword=...
   keyAlias=...
   keyPassword=...
   ```

   then run `./gradlew assembleRelease` (output: `app/build/outputs/apk/release/app-release.apk`).
3. Use the same keystore every time. Android only installs an update over the top if the signature matches.

Raise `versionCode` and `versionName` in `app/build.gradle.kts` for each new build.

## Tests

`./gradlew testDebugUnitTest` runs the logic tests. Add `-Pengine` to run the tests that start the real
torrent engine on a desktop (Linux x86-64), and `-Pshots=<folder>` to save pictures of each screen.

## Notes

- Only `arm64-v8a` is built. Add the matching `libtorrent4j-android-*` artifact and ABI for other phones.
- `UPDATE_REPO` in `app/build.gradle.kts` is the GitHub repository whose latest release the app checks.
- `targetSdk` is 34 on purpose: on 35 Android limits a data-sync foreground service to six hours a day.
