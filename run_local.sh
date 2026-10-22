#!/usr/bin/env bash
# Run the ClojureWasm bookshelf demo locally.
#
# cljw comes from the pinned RELEASE tarball, the same artifact the Dockerfile
# installs (local and container are symmetric). It is cached under .cache/ and
# checked against the release's .sha256 sidecar. The Dockerfile pins the
# linux-x86_64 sha256 itself; keep CLJW_VERSION in step with it (the pins check,
# scripts/check_version_pins.sh, fails when they disagree).
#
# Config comes from the environment (direnv): copy .envrc.example to .envrc,
# fill in GOOGLE_CLIENT_ID, `direnv allow`. Without it the app is browse-only.
#
#   ./run_local.sh                       # fetch cljw if needed, then serve :8090
#   PORT=9001 ./run_local.sh             # override the port
#   CLJW=/path/to/cljw ./run_local.sh    # use an existing cljw, skip the fetch
#
# Needs (one-time fetch only): curl, tar, sha256sum or shasum.
set -euo pipefail
cd "$(dirname "$0")"

CLJW_VERSION="${CLJW_VERSION:-v1.15.1}"
CLJW_REPO="${CLJW_REPO:-BuddhiLW/ClojureWasm}"
CACHE_DIR=".cache/cljw-${CLJW_VERSION}"
CLJW="${CLJW:-$CACHE_DIR/cljw}"

if [ ! -x "$CLJW" ]; then
  case "$(uname -s)-$(uname -m)" in
    Linux-x86_64)  asset=cljw-linux-x86_64.tar.gz ;;
    Darwin-arm64)  asset=cljw-macos-aarch64.tar.gz ;;
    *) echo "no cljw release asset for $(uname -s)-$(uname -m); set CLJW=/path/to/cljw" >&2; exit 1 ;;
  esac
  url="https://github.com/${CLJW_REPO}/releases/download/${CLJW_VERSION}/${asset}"
  echo "Fetching cljw ${CLJW_VERSION} (${asset}), first run only"
  rm -rf "$CACHE_DIR" && mkdir -p "$CACHE_DIR"
  curl -fsSL -o "$CACHE_DIR/$asset" "$url"
  want="$(curl -fsSL "$url.sha256" | awk '{print $1}')"
  if command -v sha256sum >/dev/null; then got="$(sha256sum "$CACHE_DIR/$asset" | awk '{print $1}')"
  else got="$(shasum -a 256 "$CACHE_DIR/$asset" | awk '{print $1}')"; fi
  [ -n "$want" ] && [ "$want" = "$got" ] || { echo "sha256 mismatch for $asset: want $want got $got" >&2; exit 1; }
  tar -xzf "$CACHE_DIR/$asset" -C "$CACHE_DIR" cljw
fi

mkdir -p data                                  # host dir for the SQLite store (db.clj seeds it)
export CLJW_FS_ROOT="${CLJW_FS_ROOT:-$PWD}"     # FS jail: confine slurp/File to the repo.
PORT="${PORT:-8090}"
[ -n "${GOOGLE_CLIENT_ID:-}" ] || \
  echo "note: GOOGLE_CLIENT_ID unset, browse-only (sign-in disabled). See .envrc.example." >&2

echo "ClojureWasm bookshelf: http://localhost:$PORT   (cljw: $CLJW)"
exec "$CLJW" -M:cljw -m bookshelf.server "$PORT"
