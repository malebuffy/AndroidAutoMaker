#!/usr/bin/env bash
# Build an .rpm from the current checkout.
# Usage: packaging/make-rpm.sh [version] [output-dir]; requires rpmbuild.
set -euo pipefail
VERSION="${1:-1.1.15}"
OUT="${2:-dist}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
RPMBUILD="$HOME/rpmbuild"

mkdir -p "$RPMBUILD"/{BUILD,RPMS,SOURCES,SPECS,SRPMS} "$OUT"
TARBALL="$RPMBUILD/SOURCES/android-auto-maker-$VERSION.tar.gz"
tar -C "$ROOT" --exclude=.git --exclude=work --exclude=node_modules \
  --exclude=tools/keys --exclude=tools/framework --exclude=.sdk \
  --exclude=tools/zipalign --exclude=tools/zipalign.exe \
  --exclude=tools/apksigner.jar --exclude=tools/lib64 \
  --exclude='*.keystore' --exclude='*.p12' \
  --transform "s,^,android-auto-maker-$VERSION/," -czf "$TARBALL" .

cat > "$RPMBUILD/SPECS/android-auto-maker.spec" <<EOF
Name:           android-auto-maker
Version:        $VERSION
Release:        1%{?dist}
Summary:        Prepare an APK or XAPK for Android Auto
License:        GPL-3.0-only
BuildArch:      noarch
Requires:       nodejs >= 18, java-17-openjdk-headless, unzip, curl
Recommends:     android-tools

%description
Local web app that patches an APK/XAPK manifest, rebuilds and re-signs it
so Android Auto can offer it while parked (or as navigation).

%install
mkdir -p %{buildroot}/opt/android-auto-maker %{buildroot}/usr/bin
tar -xzf $TARBALL -C %{buildroot}/opt/android-auto-maker --strip-components=1
cat > %{buildroot}/usr/bin/android-auto-maker <<'BIN'
#!/bin/sh
exec /usr/bin/node /opt/android-auto-maker/server.js "\$@"
BIN
chmod 755 %{buildroot}/usr/bin/android-auto-maker

%files
/opt/android-auto-maker
/usr/bin/android-auto-maker

%post
echo "[aam] run /opt/android-auto-maker/install.sh once to fetch Android build-tools (zipalign/apksigner)."
EOF

rpmbuild -ba "$RPMBUILD/SPECS/android-auto-maker.spec"
cp "$RPMBUILD"/RPMS/noarch/android-auto-maker-*.rpm "$OUT/"
echo "wrote $OUT/"
ls "$OUT"/android-auto-maker-*.rpm
