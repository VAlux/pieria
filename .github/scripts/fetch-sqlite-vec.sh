#!/usr/bin/env bash
#
# Fetch the sqlite-vec loadable extension for one platform into packaging/native/<platform>/.
#
# Two jobs need it and must not drift apart:
#   * `test` — :daemon:embedVecExtensions stages packaging/native/* onto the test classpath, and the
#     vector tests fail (rather than skip) when vec0 is absent. Without this the whole suite is red.
#   * `native-image` (daemon) — the same staged file is what gets embedded into the shipped binary.
#
# The per-platform binaries are git-ignored (see packaging/native/README.md), so every runner starts
# without them.
set -euo pipefail

platform="${1:?usage: fetch-sqlite-vec.sh <platform>}"
version="${SQLITE_VEC_VERSION:?SQLITE_VEC_VERSION must be set}"

case "$platform" in
  macos-*)   lib="vec0.dylib" ;;
  windows-*) lib="vec0.dll" ;;
  linux-*)   lib="vec0.so" ;;
  *)
    echo "fetch-sqlite-vec: unknown platform '$platform'" >&2
    exit 1
    ;;
esac

dir="packaging/native/${platform}"
url="https://github.com/asg017/sqlite-vec/releases/download/v${version}/sqlite-vec-${version}-loadable-${platform}.tar.gz"

mkdir -p "$dir"
curl -fsSL "$url" | tar -xz -C "$dir" "$lib"

# The release tarball is the only source of this file; a silent miss would degrade the daemon to
# FTS-only instead of failing the job, so assert it landed.
if [ ! -f "$dir/$lib" ]; then
  echo "fetch-sqlite-vec: $url did not yield $lib" >&2
  exit 1
fi
ls -l "$dir"
