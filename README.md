<div align="center">

<img src="./.github/assets/yomira.svg" alt="Yomira logo" width="80"/>

# Yomira

An Android reader for manga, webtoons and comics, with an optional network library on your own TrueNAS server.

[![CI](https://img.shields.io/github/actions/workflow/status/EnterTG/mihon/build.yml)](https://github.com/EnterTG/mihon/actions/workflows/build.yml)
[![License: Apache-2.0](https://img.shields.io/github/license/EnterTG/mihon)](LICENSE)

</div>

Yomira is an independent fork of [Mihon](https://github.com/mihonapp/mihon). This repository retains its existing GitHub name, `EnterTG/mihon`.

## Features

- Configurable reader for manga, webtoons and comics.
- Library categories, reading history, trackers, themes and backups inherited from Mihon.
- Optional server downloads: the NAS downloads and archives chapter images; the phone retrieves them for reading.
- A rolling image cache containing the current chapter, one previous chapter and two upcoming chapters.

The phone still stores library metadata, covers, extensions and temporary images. Existing local downloads are preserved. See [TrueNAS setup and limitations (Polski)](server/README.md).

## Build and install

Requires Android 8.0 or higher to run. Use JDK 21 and the Android SDK to build:

```sh
git clone https://github.com/EnterTG/mihon.git
cd mihon
./gradlew assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/`. CI APKs are available as `yomira-arm64-v8a-<commit>` artifacts in [Build & Test](https://github.com/EnterTG/mihon/actions/workflows/build.yml) after a successful run.

Yomira installs as a separate application beside Mihon in every build variant: `app.yomira` for release, `app.yomira.dev` for debug, `app.yomira.foss` for FOSS, `app.yomira.debug` for nightly and `app.yomira.benchmark` for benchmarks. It has its own private database, preferences and cache; its default external storage directory is `Yomira`. FileProvider, Shizuku authorities and shortcuts use the variant application ID automatically. Updating Yomira itself requires the same signing key.

### Import an existing library

1. In Mihon or the older Yomira build, create a `.tachibk` backup from **Settings → Data and storage → Backup and restore**.
2. Install the new Yomira APK beside the existing app; do not uninstall the old app to perform this migration.
3. In Yomira, restore the backup. You can restore the library, categories, extension repositories and source settings without restoring app settings, keeping Yomira's own storage location.
4. If you restore app settings too, reselect the desired storage directory in Yomira. Android does not transfer another app's folder permissions. Reauthorize trackers when needed.
5. To reuse the NAS archive, enter the same server address and token and refresh its catalog. The archive format and chapter keys are unchanged.

Private app data is not shared or imported automatically. Local chapter images are not included in the library backup; select an existing shared folder explicitly if you want both apps to access the same local files. Registered tracker OAuth callback URLs are retained for service compatibility; when Android offers both apps for a login callback, choose Yomira.

Telemetry and the automatic updater are disabled by default for all distributions. An explicitly enabled updater uses this fork's repository. The inherited release workflow remains restricted to the upstream repository; publishing signed Yomira releases requires configuring a release workflow and signing credentials for this fork.

Existing NAS deployments keep their `MIHON_DATA` / `MIHON_TOKEN` settings, Compose service name, dataset paths and archive format.

## Development

```sh
python3 -m unittest discover -s server/tests -v
./gradlew spotlessCheck testDebugUnitTest verifySqlDelightMigration
```

Report Yomira issues in [this repository](https://github.com/EnterTG/mihon/issues). General reader documentation is available in the [upstream Mihon guides](https://mihon.app/docs/guides/). [Contribution guide](CONTRIBUTING.md) · [Code of conduct](CODE_OF_CONDUCT.md).

## Credits and license

Built on the work of [Mihon contributors](https://github.com/mihonapp/mihon/graphs/contributors) and the original Tachiyomi project. Upstream documentation, translations and support links remain attributed to Mihon.

Copyright © 2015 Javier Tomás

Copyright © 2024 Mihon Open Source Project

Licensed under the [Apache License, Version 2.0](LICENSE). Existing copyright and license notices are preserved.
