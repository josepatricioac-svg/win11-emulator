#!/usr/bin/env bash
# Builds the prebuilt desktop runtime bundle: noble arm64 rootfs + the browser's
# GTK3/X11/font/CA stack + Wine 9 + Firefox ESR + a warm fontconfig cache + a
# wineboot-initialised prefix template, packed as one .tar.xz.
#
# Runs on a NATIVE arm64 runner (ubuntu-24.04-arm) - chroot, not qemu, so
# apt/dpkg/fc-cache/wineboot all run at native speed. Every input is verified
# before use (fail closed): the base rootfs against the SHA-256 pinned in
# pins.env (= RuntimeManifest.ROOTFS, cross-pinned by RuntimeTest), and the
# Firefox tarball against Mozilla's own SHA512SUMS for the release. The output
# is ONE archive plus its sha256/size, which the workflow publishes to Releases
# and auto-commits into Artifact.kt (the libbox64 vendoring contract).
#
# Required env: BUNDLE_VERSION (v1, ...), FIREFOX_VERSION (153.5.0esr, ...),
#               OUT (writable output dir), WITH_PREFIX_TEMPLATE (true|false).
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=ci/rootfs-bundle/pins.env
source "$HERE/pins.env"

: "${BUNDLE_VERSION:?BUNDLE_VERSION is required (e.g. v1)}"
: "${FIREFOX_VERSION:?FIREFOX_VERSION is required (e.g. 153.5.0esr)}"
: "${OUT:?OUT (output dir) is required}"
WITH_PREFIX_TEMPLATE="${WITH_PREFIX_TEMPLATE:-true}"

if [ "$(uname -m)" != "aarch64" ]; then
    echo "FATAL: this script must run on a native arm64 host (got $(uname -m))." >&2
    echo "The workflow uses runs-on: ubuntu-24.04-arm on purpose - chroot instead of qemu." >&2
    exit 1
fi

WORK="$(mktemp -d)"
ROOT="$WORK/rootfs"
BUNDLE_NAME="win11we-rootfs-bundle-noble-arm64-$BUNDLE_VERSION.tar.xz"
cleanup() {
    for d in dev/pts dev proc sys; do
        [ -d "$ROOT/$d" ] && sudo umount -l "$ROOT/$d" 2>/dev/null || true
    done
    [ -n "${WORK:-}" ] && sudo rm -rf "$WORK" 2>/dev/null || true
}
trap cleanup EXIT

echo "== 1/7  base rootfs (pinned SHA-256, fail closed)"
curl -fL --retry 3 -o "$WORK/base.tar.gz" "$ROOTFS_URL"
echo "$ROOTFS_SHA256  $WORK/base.tar.gz" | sha256sum -c -
mkdir -p "$ROOT"
tar -C "$ROOT" -xzf "$WORK/base.tar.gz"

echo "== 2/7  chroot plumbing (binds + guest DNS)"
printf 'nameserver 1.1.1.1\nnameserver 9.9.9.9\nnameserver 8.8.8.8\n' | sudo tee "$ROOT/etc/resolv.conf" >/dev/null
for d in dev proc sys dev/pts; do
    mkdir -p "$ROOT/$d" 2>/dev/null || true
    sudo mount --bind "/$d" "$ROOT/$d"
done

echo "== 3/7  apt: GUI stack + Wine (names pinned by packages.env, distro-resolved versions)"
grep -v '^#' "$HERE/packages.env" | grep -v '^[[:space:]]*$' > "$WORK/packages.txt"
sudo cp "$WORK/packages.txt" "$ROOT/tmp/packages.txt"
sudo chroot "$ROOT" /bin/bash -c '
    set -e
    export DEBIAN_FRONTEND=noninteractive
    apt-get update -qq
    xargs -a /tmp/packages.txt apt-get install -y --no-install-recommends
    dpkg --configure -a
    echo "-- fc-cache: warm the fontconfig cache at BUILD time, not during the first paint"
    fc-cache -f
    apt-get clean
    rm -rf /var/lib/apt/lists/* /tmp/packages.txt
'

echo "== 4/7  Firefox ESR $FIREFOX_VERSION (verified against Mozilla's own SHA512SUMS)"
FF_REL="$FIREFOX_RELEASES_BASE/$FIREFOX_VERSION"
FF_PATH="linux-aarch64/en-US/firefox-$FIREFOX_VERSION.tar.xz"
curl -fL --retry 3 -o "$WORK/firefox.tar.xz" "$FF_REL/$FF_PATH"
curl -fL --retry 3 -o "$WORK/SHA512SUMS" "$FF_REL/SHA512SUMS"
FF_SHA512="$(grep -F " $FF_PATH" "$WORK/SHA512SUMS" | awk '{print $1}' | head -1)"
if [ -z "$FF_SHA512" ]; then
    echo "FATAL: $FF_PATH not listed in Mozilla's SHA512SUMS for $FIREFOX_VERSION" >&2
    exit 1
fi
echo "$FF_SHA512  $WORK/firefox.tar.xz" | sha512sum -c -
FF_SHA256="$(sha256sum "$WORK/firefox.tar.xz" | awk '{print $1}')"
sudo mkdir -p "$ROOT$FIREFOX_INSTALL_DIR"
sudo tar -C "$ROOT$FIREFOX_INSTALL_DIR" --strip-components=1 -xJf "$WORK/firefox.tar.xz"

echo "== 5/7  wineboot prefix template (WITH_PREFIX_TEMPLATE=$WITH_PREFIX_TEMPLATE)"
TEMPLATE_OK=false
if [ "$WITH_PREFIX_TEMPLATE" = "true" ]; then
    sudo mkdir -p "$ROOT/opt/win11we"
    # wineboot --init headless: no DISPLAY needed to build the registry + drive_c.
    # Bounded: a hung wineserver must cost minutes, not the whole job budget. The
    # marker check below - not the exit code - decides whether the template ships.
    # Runs as chroot-root; the archive stores root ownership and the app's TAR
    # reader lands everything as the app uid on-device, which proot's fake root
    # is designed for (same as every proot-distro rootfs).
    sudo chroot "$ROOT" /bin/bash -c '
        export WINEPREFIX='"$PREFIX_TEMPLATE_PATH"' HOME=/root
        timeout 600 wineboot --init
        timeout 180 wineserver -w
    ' || echo "::warning::wineboot did not finish cleanly - the template will be validated next"
    if sudo test -f "$ROOT$PREFIX_TEMPLATE_PATH/system.reg" &&
       sudo test -f "$ROOT$PREFIX_TEMPLATE_PATH/user.reg" &&
       sudo test -d "$ROOT$PREFIX_TEMPLATE_PATH/drive_c"; then
        sudo rm -f "$ROOT$PREFIX_TEMPLATE_PATH/.update-lock"
        TEMPLATE_OK=true
        echo "prefix template validated (system.reg + user.reg + drive_c)"
    else
        echo "::warning::prefix template incomplete - shipping WITHOUT it; on-device wineboot stays the path"
        sudo rm -rf "$ROOT$PREFIX_TEMPLATE_PATH"
    fi
fi

echo "== 6/7  provenance manifest + the bundle's promise, checked on disk"
{
    echo "win11we desktop runtime bundle $BUNDLE_VERSION"
    echo "built: $(date -u +%Y-%m-%dT%H:%M:%SZ) on $(uname -m)"
    echo "base-url: $ROOTFS_URL"
    echo "base-sha256: $ROOTFS_SHA256"
    echo "firefox-version: $FIREFOX_VERSION"
    echo "firefox-url: $FF_REL/$FF_PATH"
    echo "firefox-sha512: $FF_SHA512 (verified against Mozilla's SHA512SUMS at build time)"
    echo "firefox-sha256: $FF_SHA256"
    echo "prefix-template: $TEMPLATE_OK"
    echo "wine-packages:"
    sudo chroot "$ROOT" dpkg-query -W wine wine64 libwine 2>/dev/null | sed 's/^/  /' || echo "  (none?)"
    echo "packages:"
    sudo chroot "$ROOT" dpkg-query -W -f '${Package} ${Version}\n' | sort | sed 's/^/  /'
} | sudo tee "$ROOT$BUNDLE_MANIFEST_PATH" >/dev/null

# Fail closed on the bundle's own promise: these are exactly the paths the app's
# readiness checks (FirefoxSession.isInstalled/guiLibrariesPresent, WinePayload,
# the X self-test clients) look for. A bundle missing any of them must not ship.
sudo test -x "$ROOT$FIREFOX_INSTALL_DIR/firefox"
sudo test -e "$ROOT/usr/lib/aarch64-linux-gnu/libgtk-3.so.0"
sudo test -x "$ROOT/usr/lib/wine/wine64"
sudo test -e "$ROOT/usr/lib/wine/wineserver"
sudo test -x "$ROOT/usr/bin/xdpyinfo"
sudo test -x "$ROOT/usr/bin/xclock"
sudo test -x "$ROOT/usr/bin/gtk-query-settings"
sudo test -f "$ROOT$BUNDLE_MANIFEST_PATH"
echo "bundle promise verified on disk"

echo "== 7/7  pack: tar (root-owned, numeric) + xz -9e -T0"
mkdir -p "$OUT"
sudo tar -C "$ROOT" --numeric-owner --owner=0 --group=0 -cf - . | xz -9e -T0 > "$OUT/$BUNDLE_NAME"
sha256sum "$OUT/$BUNDLE_NAME" | awk '{print $1}' > "$OUT/$BUNDLE_NAME.sha256"
stat -c %s "$OUT/$BUNDLE_NAME" > "$OUT/$BUNDLE_NAME.size"
sudo cp "$ROOT$BUNDLE_MANIFEST_PATH" "$OUT/bundle-manifest.txt"
echo "== DONE: $BUNDLE_NAME"
echo "   size:   $(cat "$OUT/$BUNDLE_NAME.size") bytes"
echo "   sha256: $(cat "$OUT/$BUNDLE_NAME.sha256")"
