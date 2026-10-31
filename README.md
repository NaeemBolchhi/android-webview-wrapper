# Web wrapper

## Make an app
1. Create `configs/<app>/config.json`:
   ```json
   { "appName": "My App", "homeUrl": "https://example.com", "packageName": "com.example.myapp",
     "versionName": "1.2.0", "iconBackgroundColor": "#FFFFFF" }
   ```
   (`versionName` falls back to the repo variable `VERSION_NAME`, then `1.0`.)
2. Put images in the same folder:
   - `icon.png` – square foreground, ideally 432px+, artwork inside the central 61% (66dp of the 108dp layer). Resized to every density.
   - `icon_background.png` – optional (otherwise `iconBackgroundColor` is used).
   - `icon_monochrome.png` – optional, for themed icons.
   - Or supply finished `mipmap-mdpi/ … mipmap-xxxhdpi/` folders (`ic_launcher_foreground.png` etc.); they are used as-is.
3. Copy `.github/workflows/build-example.yml`, change `name:` and `APP:` to your folder name. Run it from the Actions tab.

Output: `<AppName>-v<versionName>-<run number>.apk`, universal, release-signed.

## Updates
The workflow bakes the repo it runs in (`GITHUB_REPOSITORY`) into the app as its update source.
To publish an update, create a GitHub release in that repo and attach the built APK **with its original file name**.
Tag and release name don't matter and one release may hold APKs of several apps.

On launch (at most every 6 hours) the app reads the latest 30 published, non-pre-release releases and looks at
the asset names. `<AppName>-v<versionName>-<run number>.apk` is an update for an app when `<AppName>` is that app's name
and `<versionName>` is higher than the installed one (same version with a higher run number also counts).
It then offers **Update** / **Not now**. *Update* downloads the APK inside the app, then opens the system package installer
(Android asks once to allow installs from the app). The repo must be public; local builds have no update source and skip the check.

## Signing (optional repo secrets, shared by all apps)
`KEYSTORE_FILE` (`base64 -w0 release.jks`), `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.
Without them the bundled `signing/test.jks` is used.
