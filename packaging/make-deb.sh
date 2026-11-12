#!/usr/bin/env bash
# Build a .deb from the current checkout (no fpm needed).
# Usage: packaging/make-deb.sh [version] [output-dir]
set -euo pipefail
VERSION="${1:-1.1.15}"
OUT="${2:-dist}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT

PKG="$STAGE/android-auto-maker_${VERSION}_all"
mkdir -p "$PKG/DEBIAN" "$PKG/opt/android-auto-maker" "$PKG/usr/bin"

# App payload (exclude git, work, keys, SDK downloads, node_modules).
tar -C "$ROOT" --exclude=.git --exclude=work --exclude=node_modules \
  --exclude=tools/keys --exclude=tools/framework --exclude=.sdk \
  --exclude='*.keystore' --exclude='*.p12' -cf - . | tar -xf - -C "$PKG/opt/android-auto-maker"

cat > "$PKG/DEBIAN/control" <<EOF
Package: android-auto-maker
Version: $VERSION
Section: utils
Priority: optional
Architecture: all
Depends: nodejs (>= 18), default-jre-headless | openjdk-17-jre-headless, unzip, curl
Recommends: android-sdk-platform-tools
Maintainer: Android Auto Maker contributors
Description: Prepare an APK or XAPK for Android Auto (parked game / navigation)
 Local web app that patches an APK/XAPK manifest, rebuilds and re-signs it
 so Android Auto can offer it while parked (or as navigation).
EOF

cat > "$PKG/DEBIAN/postinst" <<'EOF'
#!/bin/sh
set -e
cd /opt/android-auto-maker
if command -v npm >/dev/null 2>&1; then npm install --omit=dev >/dev/null 2>&1 || true; fi
echo "[aam] run /opt/android-auto-maker/install.sh once to fetch Android build-tools (zipalign/apksigner)."
echo "[aam] then: PORT=4173 node /opt/android-auto-maker/server.js"
EOF
chmod 755 "$PKG/DEBIAN/postinst"

cat > "$PKG/usr/bin/android-auto-maker" <<'EOF'
#!/bin/sh
exec /usr/bin/node /opt/android-auto-maker/server.js "$@"
EOF
chmod 755 "$PKG/usr/bin/android-auto-maker"

mkdir -p "$OUT"
dpkg-deb --build "$PKG" "$OUT/android-auto-maker_${VERSION}_all.deb"
echo "wrote $OUT/android-auto-maker_${VERSION}_all.deb"
