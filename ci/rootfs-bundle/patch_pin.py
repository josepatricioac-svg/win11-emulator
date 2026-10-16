#!/usr/bin/env python3
"""Patches the ROOTFS_BUNDLE row of Artifact.kt with the real build pin.

Called by build-rootfs-bundle.yml after a successful build + release upload -
the bundle's analogue of build-native's libbox64 auto-pin: the archive and its
SHA-256 pin land together, or not at all. Fails loudly (non-zero, named) when
any expected pattern is missing, so a manifest refactor can never silently
skip the pin (the app would stay fail-closed on the placeholder, but CI must
say so).

Usage: patch_pin.py <out-dir> <bundle-version> <repo-root>
"""
import pathlib
import re
import sys


def sub1(pattern: str, replacement: str, text: str, what: str) -> str:
    new, count = re.subn(pattern, replacement, text, count=1)
    if count != 1:
        raise SystemExit(f"PIN PATCH FAILED ({what}): pattern not found in the ROOTFS_BUNDLE block:\n{text}")
    return new


def main() -> int:
    out_dir = pathlib.Path(sys.argv[1])
    version = sys.argv[2]
    repo = pathlib.Path(sys.argv[3])

    name = f"win11we-rootfs-bundle-noble-arm64-{version}.tar.xz"
    sha = (out_dir / f"{name}.sha256").read_text().strip()
    size = (out_dir / f"{name}.size").read_text().strip()
    if not re.fullmatch(r"[0-9a-f]{64}", sha):
        raise SystemExit(f"PIN PATCH FAILED: {sha!r} is not a lowercase hex SHA-256")
    if not (size.isdigit() and int(size) > 0):
        raise SystemExit(f"PIN PATCH FAILED: {size!r} is not a positive byte size")

    artifact = repo / "app/src/main/java/dev/win11we/shell/runtime/download/Artifact.kt"
    source = artifact.read_text()

    start = source.index("val ROOTFS_BUNDLE = Artifact(")
    end = source.index("\n    )", start) + len("\n    )")
    block = source[start:end]

    block = sub1(
        r'"rootfs-bundle-[^/"]*/win11we-rootfs-bundle-noble-arm64-[^"]*\.tar\.xz"',
        f'"rootfs-bundle-{version}/{name}"',
        block, "release tag/asset in url",
    )
    block = sub1(
        r'sha256 = (?:"0"\.repeat\(64\)|"[0-9a-fA-F]{64}")[^\n]*',
        f'sha256 = "{sha}",',
        block, "sha256 pin",
    )
    block = sub1(r"sizeBytes = [0-9_]+L?,", f"sizeBytes = {size}L,", block, "sizeBytes")
    block = sub1(r"placeholder = (?:true|false),", "placeholder = false,", block, "placeholder flag")

    artifact.write_text(source[:start] + block + source[end:])
    print(f"pinned {name}: sha256={sha} size={size} placeholder=false")
    print("--- patched block ---")
    print(block)
    return 0


if __name__ == "__main__":
    sys.exit(main())
