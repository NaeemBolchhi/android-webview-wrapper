# Android Webview Wrapper

## Make an app
1. Create `configs/<app>/config.json`:
   ```json
   { "appName": "My App", "homeUrl": "https://example.com", "packageName": "com.example.myapp",
     "versionName": "1.2.0", "iconBackgroundColor": "#FFFFFF" }
   ```
   (`versionName` falls back to the repo variable `VERSION_NAME`, then `1.0`.)
2. Put images in the same folder:
   - `icon.png` – square foreground, ideally 432px+, artwork inside the central 61%. Resized to every density.
   - `icon_background.png` – optional (otherwise `iconBackgroundColor` is used).
   - `icon_monochrome.png` – optional, for themed icons.
   - Or supply finished `mipmap-mdpi/ … mipmap-xxxhdpi/` folders (`ic_launcher_foreground.png` etc.); they are used as-is.
3. Copy `.github/workflows/build-example.yml`, change `name:` and `APP:` to your folder name. Run it from the Actions tab.

Output: `<AppName>-v<versionName>-<run number>.apk`, universal, release-signed.

## Signing (optional repo secrets, shared by all apps)
`KEYSTORE_BASE64` (`base64 -w0 release.jks`), `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.
Without them the bundled `signing/test.jks` is used.
