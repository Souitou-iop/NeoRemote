#!/usr/bin/env bash
set -euo pipefail

MODE="${1:-run}"
APP_NAME="NeoRemoteMac"
BUNDLE_ID="com.neoremote.mac"
MIN_SYSTEM_VERSION="15.0"

PACKAGE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DIST_DIR="$PACKAGE_DIR/dist"
CACHE_DIR="$PACKAGE_DIR/.build/cache"
CLANG_CACHE_DIR="$CACHE_DIR/clang-module-cache"
APP_BUNDLE="$DIST_DIR/$APP_NAME.app"
APP_CONTENTS="$APP_BUNDLE/Contents"
APP_MACOS="$APP_CONTENTS/MacOS"
APP_RESOURCES="$APP_CONTENTS/Resources"
APP_BINARY="$APP_MACOS/$APP_NAME"
INFO_PLIST="$APP_CONTENTS/Info.plist"
DEFAULT_CODESIGN_IDENTITY="${NEOREMOTE_CODESIGN_IDENTITY:-}"
ICON_COMPOSER_FILE="$PACKAGE_DIR/../resources/icons/NeoRemote.icon"
ACTOOL="/Applications/Xcode.app/Contents/Developer/usr/bin/actool"
ICON_FILE_NAME="AppIcon"
ICON_NAME="AppIcon"

pkill -x "$APP_NAME" >/dev/null 2>&1 || true

mkdir -p "$CLANG_CACHE_DIR"
mkdir -p "$APP_RESOURCES"
export CLANG_MODULE_CACHE_PATH="$CLANG_CACHE_DIR"
export XDG_CACHE_HOME="$CACHE_DIR/xdg"

swift build --disable-sandbox --package-path "$PACKAGE_DIR"
BUILD_BINARY="$(swift build --disable-sandbox --package-path "$PACKAGE_DIR" --show-bin-path)/$APP_NAME"

rm -rf "$APP_BUNDLE"
mkdir -p "$APP_MACOS" "$APP_RESOURCES"
cp "$BUILD_BINARY" "$APP_BINARY"
chmod +x "$APP_BINARY"

if [[ -d "$ICON_COMPOSER_FILE" && -x "$ACTOOL" ]]; then
  ICON_BUILD_DIR="$CACHE_DIR/icon-assets"
  rm -rf "$ICON_BUILD_DIR"
  mkdir -p "$ICON_BUILD_DIR"

  "$ACTOOL" "$ICON_COMPOSER_FILE" \
    --compile "$ICON_BUILD_DIR" \
    --output-format human-readable-text \
    --notices \
    --warnings \
    --app-icon NeoRemote \
    --minimum-deployment-target "$MIN_SYSTEM_VERSION" \
    --platform macosx \
    --target-device mac \
    --output-partial-info-plist "$ICON_BUILD_DIR/assetcatalog-info.plist"

  cp "$ICON_BUILD_DIR/Assets.car" "$APP_RESOURCES/Assets.car"
  cp "$ICON_BUILD_DIR/NeoRemote.icns" "$APP_RESOURCES/NeoRemote.icns"
  ICON_FILE_NAME="NeoRemote"
  ICON_NAME="NeoRemote"
else
  cp "$PACKAGE_DIR/Resources/AppIcon.icns" "$APP_RESOURCES/AppIcon.icns"
fi

cat >"$INFO_PLIST" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>CFBundleExecutable</key>
  <string>$APP_NAME</string>
  <key>CFBundleIdentifier</key>
  <string>$BUNDLE_ID</string>
  <key>CFBundleName</key>
  <string>$APP_NAME</string>
  <key>CFBundleDisplayName</key>
  <string>NeoRemote</string>
  <key>CFBundleIconFile</key>
  <string>$ICON_FILE_NAME</string>
  <key>CFBundleIconName</key>
  <string>$ICON_NAME</string>
  <key>CFBundleShortVersionString</key>
  <string>1.0</string>
  <key>CFBundleVersion</key>
  <string>1</string>
  <key>CFBundleInfoDictionaryVersion</key>
  <string>6.0</string>
  <key>CFBundleDevelopmentRegion</key>
  <string>zh_CN</string>
  <key>CFBundlePackageType</key>
  <string>APPL</string>
  <key>LSMinimumSystemVersion</key>
  <string>$MIN_SYSTEM_VERSION</string>
  <key>NSPrincipalClass</key>
  <string>NSApplication</string>
</dict>
</plist>
PLIST

printf "APPL????" >"$APP_CONTENTS/PkgInfo"

resolve_codesign_identity() {
  if [[ -n "$DEFAULT_CODESIGN_IDENTITY" ]]; then
    echo "$DEFAULT_CODESIGN_IDENTITY"
    return
  fi

  local identity
  identity="$(security find-identity -v -p codesigning 2>/dev/null \
    | awk -F '"' '/Apple Development|Developer ID Application/ { print $2; exit }')"

  if [[ -n "$identity" ]]; then
    echo "$identity"
  fi
}

sign_app_bundle() {
  local identity
  identity="$(resolve_codesign_identity)"

  if [[ -z "$identity" ]]; then
    echo "warning: no stable code signing identity found; using ad-hoc signing." >&2
    echo "warning: macOS Accessibility permission may need to be granted again after rebuilds." >&2
    codesign --force --timestamp=none --sign - "$APP_BINARY"
    codesign --force --timestamp=none --sign - "$APP_BUNDLE"
    return
  fi

  echo "signing $APP_NAME.app with: $identity"
  codesign --force --timestamp=none --sign "$identity" "$APP_BINARY"
  codesign --force --timestamp=none --sign "$identity" "$APP_BUNDLE"
}

sign_app_bundle

open_app() {
  /usr/bin/open -n "$APP_BUNDLE"
}

case "$MODE" in
  run)
    open_app
    ;;
  --debug|debug)
    lldb -- "$APP_BINARY"
    ;;
  --logs|logs)
    open_app
    /usr/bin/log stream --info --style compact --predicate "process == \"$APP_NAME\""
    ;;
  --telemetry|telemetry)
    open_app
    /usr/bin/log stream --info --style compact --predicate "subsystem == \"$BUNDLE_ID\""
    ;;
  --verify|verify)
    open_app
    sleep 1
    pgrep -x "$APP_NAME" >/dev/null
    ;;
  *)
    echo "usage: $0 [run|--debug|--logs|--telemetry|--verify]" >&2
    exit 2
    ;;
esac
