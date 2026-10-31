#!/usr/bin/env python3
"""Reads configs/$APP/ and prepares the project for that single app."""
import json, os, pathlib, re, shutil, sys
from xml.sax.saxutils import escape

root = pathlib.Path(__file__).resolve().parent.parent
res = root / "app/src/main/res"

app = os.environ.get("APP", "").strip()
if not re.fullmatch(r"[A-Za-z0-9._-]+", app):
    sys.exit("APP must be the name of a folder under configs/")
cdir = root / "configs" / app
if not (cdir / "config.json").is_file():
    sys.exit(f"Missing configs/{app}/config.json")

c = json.loads((cdir / "config.json").read_text(encoding="utf-8"))
name, url, pkg = c.get("appName"), c.get("homeUrl"), c.get("packageName")
if not (name and url and pkg):
    sys.exit("config.json needs appName, homeUrl and packageName")
if not re.fullmatch(r"[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+", pkg):
    sys.exit("Invalid packageName: " + pkg)
if not re.match(r"https?://", url):
    sys.exit("homeUrl must start with http:// or https://")

version_name = str(c.get("versionName") or os.environ.get("VERSION_NAME", "").strip() or "1.0")
version_code = int(os.environ.get("BUILD_NUMBER", "").strip() or "1")

# Same sanitising as the APK file name; the in-app update checker matches release assets by this prefix.
safe = lambda s: re.sub(r"[^A-Za-z0-9._-]+", "_", s).strip("_")
apk_prefix = safe(name) or "App"

# The repo this build runs in (set automatically by GitHub Actions) becomes the update source.
# Empty for local builds, which disables the update checker.
update_repo = os.environ.get("GITHUB_REPOSITORY", "").strip()
if update_repo and not re.fullmatch(r"[A-Za-z0-9._-]+/[A-Za-z0-9._-]+", update_repo):
    print(f"::warning::Ignoring unexpected GITHUB_REPOSITORY value: {update_repo}")
    update_repo = ""
if not update_repo:
    print("::warning::GITHUB_REPOSITORY not set, update checker will be disabled")

(root / "app-config.json").write_text(json.dumps(
    {"appName": name, "homeUrl": url, "packageName": pkg,
     "versionName": version_name, "versionCode": version_code,
     "apkPrefix": apk_prefix, "updateRepo": update_repo}))

label = escape(name, {"'": "\\'", '"': '\\"'})
if label[:1] in "@?":
    label = "\\" + label
(res / "values/strings.xml").write_text(
    f'<resources><string name="app_name">{label}</string></resources>\n', encoding="utf-8")

# ---- icons -------------------------------------------------------------
# Ready-made mipmap-*dpi/ folders are copied as they are; icon.png,
# icon_background.png and icon_monochrome.png are resized to every density.
DENSITIES = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}
SOURCES = {"icon.png": "ic_launcher_foreground",
           "icon_background.png": "ic_launcher_background",
           "icon_monochrome.png": "ic_launcher_monochrome"}
names = set()
for d in sorted(cdir.glob("mipmap-*")):
    if d.is_dir():
        shutil.copytree(d, res / d.name, dirs_exist_ok=True)
        names |= {f.stem for f in d.iterdir() if f.suffix in (".png", ".webp")}
for fn, out in SOURCES.items():
    p = cdir / fn
    if not p.is_file() or out in names:
        continue
    from PIL import Image, ImageOps
    im = Image.open(p).convert("RGBA")
    for dn, px in DENSITIES.items():
        t = res / f"mipmap-{dn}"
        t.mkdir(parents=True, exist_ok=True)
        ImageOps.pad(im, (px, px), method=Image.Resampling.LANCZOS, color=(0, 0, 0, 0)).save(t / f"{out}.png")
    names.add(out)

if "ic_launcher_foreground" in names:
    bg = str(c.get("iconBackgroundColor", "#FFFFFF"))
    if not re.fullmatch(r"#[0-9A-Fa-f]{6,8}", bg):
        sys.exit("iconBackgroundColor must look like #RRGGBB")
    (res / "values/ic_colors.xml").write_text(
        f'<resources><color name="ic_launcher_background">{bg}</color></resources>\n')
    bg_ref = "@mipmap/ic_launcher_background" if "ic_launcher_background" in names else "@color/ic_launcher_background"
    mono = '    <monochrome android:drawable="@mipmap/ic_launcher_monochrome" />\n' if "ic_launcher_monochrome" in names else ""
    (res / "mipmap-anydpi/ic_launcher.xml").write_text(
        '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
        f'    <background android:drawable="{bg_ref}" />\n'
        '    <foreground android:drawable="@mipmap/ic_launcher_foreground" />\n'
        f'{mono}</adaptive-icon>\n')
else:
    print(f"::warning::No icon.png in configs/{app}, using the default icon")

apk = f"{apk_prefix}-v{safe(version_name)}-{version_code}.apk"
if os.environ.get("GITHUB_OUTPUT"):
    with open(os.environ["GITHUB_OUTPUT"], "a") as f:
        f.write(f"apk_name={apk}\n")
print(f"Configured {name} ({pkg}) {version_name} build {version_code} -> {apk}; updates from {update_repo or '(disabled)'}")
