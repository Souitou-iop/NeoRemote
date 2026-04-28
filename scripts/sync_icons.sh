#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ICON_FILE="$ROOT_DIR/icon/NeoRemote.icon"
ICON_SVG="$ICON_FILE/Assets/remote_control_signal_icon_no_bg.svg"
ICON_EXPORT="$ROOT_DIR/icon/NeoRemote Exports/NeoRemote-iOS-Default-1024x1024@1x.png"
IOS_ICON_COPY="$ROOT_DIR/iOS/NeoRemote/Resources/NeoRemote.icon"
MAC_RESOURCES="$ROOT_DIR/MacOS/Resources"
ANDROID_RES="$ROOT_DIR/Android/app/src/main/res"
WINDOWS_ICON="$ROOT_DIR/Windows/src/NeoRemote.Win32App/resources/icons/NeoRemote.ico"
ACTOOL="/Applications/Xcode.app/Contents/Developer/usr/bin/actool"

TMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/neoremote-icons.XXXXXX")"
trap 'rm -rf "$TMP_DIR"' EXIT

BASE_PNG="$TMP_DIR/neoremote-icon-1024.png"

if [[ -f "$ICON_EXPORT" ]]; then
  cp "$ICON_EXPORT" "$BASE_PNG"
else
  qlmanage -t -s 1024 -o "$TMP_DIR" "$ICON_SVG" >/dev/null 2>&1
  cp "$TMP_DIR/$(basename "$ICON_SVG").png" "$BASE_PNG"
fi

if [[ -d "$ICON_FILE" ]]; then
  rm -rf "$IOS_ICON_COPY"
  ditto "$ICON_FILE" "$IOS_ICON_COPY"
  xattr -dr com.apple.quarantine "$IOS_ICON_COPY" "$ICON_FILE" 2>/dev/null || true
fi

if [[ -x "$ACTOOL" ]]; then
  MAC_ICON_BUILD="$TMP_DIR/macos-icon"
  mkdir -p "$MAC_ICON_BUILD" "$MAC_RESOURCES"
  "$ACTOOL" "$ICON_FILE" \
    --compile "$MAC_ICON_BUILD" \
    --output-format human-readable-text \
    --notices \
    --warnings \
    --app-icon NeoRemote \
    --minimum-deployment-target 15.0 \
    --platform macosx \
    --target-device mac \
    --output-partial-info-plist "$MAC_ICON_BUILD/assetcatalog-info.plist"
  cp "$MAC_ICON_BUILD/NeoRemote.icns" "$MAC_RESOURCES/AppIcon.icns"
fi

make_png() {
  local size="$1"
  local out="$2"
  mkdir -p "$(dirname "$out")"
  sips -z "$size" "$size" "$BASE_PNG" --out "$out" >/dev/null
}

make_png 48 "$ANDROID_RES/mipmap-mdpi/ic_launcher.png"
make_png 48 "$ANDROID_RES/mipmap-mdpi/ic_launcher_round.png"
make_png 72 "$ANDROID_RES/mipmap-hdpi/ic_launcher.png"
make_png 72 "$ANDROID_RES/mipmap-hdpi/ic_launcher_round.png"
make_png 96 "$ANDROID_RES/mipmap-xhdpi/ic_launcher.png"
make_png 96 "$ANDROID_RES/mipmap-xhdpi/ic_launcher_round.png"
make_png 144 "$ANDROID_RES/mipmap-xxhdpi/ic_launcher.png"
make_png 144 "$ANDROID_RES/mipmap-xxhdpi/ic_launcher_round.png"
make_png 192 "$ANDROID_RES/mipmap-xxxhdpi/ic_launcher.png"
make_png 192 "$ANDROID_RES/mipmap-xxxhdpi/ic_launcher_round.png"

WINDOWS_ICON_DIR="$TMP_DIR/windows-ico"
mkdir -p "$WINDOWS_ICON_DIR" "$(dirname "$WINDOWS_ICON")"
for size in 16 24 32 48 64 128 256; do
  make_png "$size" "$WINDOWS_ICON_DIR/icon-${size}.png"
done

python3 - "$WINDOWS_ICON_DIR" "$WINDOWS_ICON" <<'PY'
import pathlib
import struct
import sys

source = pathlib.Path(sys.argv[1])
target = pathlib.Path(sys.argv[2])
sizes = [16, 24, 32, 48, 64, 128, 256]
entries = []

for size in sizes:
    path = source / f"icon-{size}.png"
    data = path.read_bytes()
    width = height = 0 if size == 256 else size
    entries.append((width, height, size, data))

offset = 6 + 16 * len(entries)
header = struct.pack("<HHH", 0, 1, len(entries))
directory = bytearray()
payload = bytearray()

for width, height, size, data in entries:
    directory.extend(struct.pack("<BBBBHHII", width, height, 0, 0, 1, 32, len(data), offset))
    payload.extend(data)
    offset += len(data)

target.write_bytes(header + directory + payload)
PY

echo "Synced NeoRemote icons for iOS, macOS, Android, and Windows."
