#!/usr/bin/env bash
# Android Auto Maker - Linux installer (1-liner + local install).
# Usage:
#   curl -fsSL https://raw.githubusercontent.com/malebuffy/AndroidAutoMaker/master/install.sh | bash
# Env overrides: REPO=malebuffy/AndroidAutoMaker REF=master PREFIX=/opt/android-auto-maker PORT=4173
set -euo pipefail

REPO="${REPO:-malebuffy/AndroidAutoMaker}"
REF="${REF:-master}"
PREFIX="${PREFIX:-/opt/android-auto-maker}"
PORT="${PORT:-4173}"
CMDLINE_TOOLS_URL="${CMDLINE_TOOLS_URL:-https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip}"
BUILD_TOOLS_VERSION="${BUILD_TOOLS_VERSION:-36.0.0}"

log() { printf '[aam] %s\n' "$*"; }
die() { printf '[aam] ERROR: %s\n' "$*" >&2; exit 1; }

need_root_for_prefix() { [ -w "$(dirname "$PREFIX")" ] || [ "$(id -u)" -eq 0 ]; }
SUDO=""
if ! need_root_for_prefix; then
  SUDO="sudo"
  command -v sudo >/dev/null || die "need write access to $PREFIX (run as root or set PREFIX=\$HOME/android-auto-maker)"
fi

detect_pkg() {
  if command -v apt-get >/dev/null; then echo apt
  elif command -v dnf >/dev/null; then echo dnf
  elif command -v pacman >/dev/null; then echo pacman
  elif command -v zypper >/dev/null; then echo zypper
  else echo none; fi
}

install_deps() {
  local mgr; mgr="$(detect_pkg)"
  log "installing system dependencies ($mgr)"
  case "$mgr" in
    apt) $SUDO apt-get update && $SUDO apt-get install -y nodejs npm default-jre-headless unzip curl ca-certificates android-sdk-platform-tools ;;
    dnf) $SUDO dnf install -y nodejs npm java-17-openjdk-headless unzip curl android-tools ;;
    pacman) $SUDO pacman -Sy --noconfirm nodejs npm jre17-openjdk unzip curl android-tools ;;
    zypper) $SUDO zypper install -y nodejs npm java-17-openjdk-headless unzip curl android-tools ;;
    none) log "no supported package manager; need: node>=18, java>=17, unzip, curl, adb (optional)" ;;
  esac
  command -v node >/dev/null || die "node not found after dependency install"
  command -v java >/dev/null || die "java not found after dependency install"
}

fetch_app() {
  local tmp; tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' EXIT
  log "downloading $REPO@$REF"
  curl -fsSL "https://codeload.github.com/${REPO}/tar.gz/refs/heads/${REF}" -o "$tmp/app.tar.gz"
  $SUDO mkdir -p "$PREFIX"
  # Keep private keys/work on reinstall.
  $SUDO tar -xzf "$tmp/app.tar.gz" -C "$tmp"
  local src; src="$(echo "$tmp"/*/)"
  if [ -d "$PREFIX/tools/keys" ]; then cp -a "$PREFIX/tools/keys" "$tmp/keys.bak"; fi
  $SUDO cp -a "$src." "$PREFIX/"
  if [ -d "$tmp/keys.bak" ]; then $SUDO cp -a "$tmp/keys.bak/." "$PREFIX/tools/keys/"; fi
  trap - EXIT; rm -rf "$tmp"
}

fetch_sdk_tools() {
  # zipalign/aapt/apksigner.jar ship with Android SDK build-tools (license: Android SDK,
  # not redistributable in this repo). Download once via Google commandline tools.
  if [ -x "$PREFIX/tools/zipalign" ] && [ -f "$PREFIX/tools/apksigner.jar" ]; then
    log "build-tools already present in $PREFIX/tools"
    return
  fi
  local sdk="$PREFIX/.sdk" tmp; tmp="$(mktemp -d)"
  log "fetching Android build-tools $BUILD_TOOLS_VERSION"
  curl -fsSL "$CMDLINE_TOOLS_URL" -o "$tmp/cmdtools.zip"
  rm -rf "$sdk"; mkdir -p "$sdk/cmdline-tools"
  unzip -q "$tmp/cmdtools.zip" -d "$tmp"
  mv "$tmp/cmdline-tools" "$sdk/cmdline-tools/latest"
  export ANDROID_HOME="$sdk" ANDROID_SDK_ROOT="$sdk"
  yes | "$sdk/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$sdk" --licenses >/dev/null
  "$sdk/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$sdk" "build-tools;$BUILD_TOOLS_VERSION" "platform-tools" >/dev/null
  local bt="$sdk/build-tools/$BUILD_TOOLS_VERSION"
  cp -f "$bt/zipalign" "$PREFIX/tools/zipalign"
  cp -f "$bt/lib/apksigner.jar" "$PREFIX/tools/apksigner.jar"
  chmod +x "$PREFIX/tools/zipalign"
  # zipalign links libc++.so via $ORIGIN/lib64 RUNPATH - ship the SDK copy next to it.
  mkdir -p "$PREFIX/tools/lib64"
  cp -f "$bt/lib64/libc++.so" "$bt/lib64/libc++.so.1" "$PREFIX/tools/lib64/" 2>/dev/null || true
  rm -rf "$tmp"
}

npm_install() {
  log "npm install (production)"
  (cd "$PREFIX" && (npm ci --omit=dev 2>/dev/null || npm install --omit=dev))
}

write_service() {
  local unit_dir="$HOME/.config/systemd/user"
  mkdir -p "$unit_dir"
  sed -e "s|@PREFIX@|$PREFIX|g" -e "s|@PORT@|$PORT|g" \
    "$PREFIX/packaging/android-auto-maker.service" > "$unit_dir/android-auto-maker.service"
  if command -v systemctl >/dev/null; then
    systemctl --user daemon-reload || true
    systemctl --user enable --now android-auto-maker.service || log "systemd start failed; run manually: PORT=$PORT node $PREFIX/server.js"
  fi
  mkdir -p "$HOME/.local/share/applications"
  sed -e "s|@PREFIX@|$PREFIX|g" -e "s|@PORT@|$PORT|g" \
    "$PREFIX/packaging/android-auto-maker.desktop" > "$HOME/.local/share/applications/android-auto-maker.desktop" || true
}

main() {
  install_deps
  fetch_app
  fetch_sdk_tools
  npm_install
  write_service
  log "done. Open http://127.0.0.1:${PORT} (LAN: shared address printed by server.js)"
  log "keys kept in $PREFIX/tools/keys - back it up to keep update-installs working"
}
main "$@"
