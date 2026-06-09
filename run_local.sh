#!/usr/bin/env bash
# Run the ClojureWasm bookshelf demo locally — machine-independent.
#
# cljw is built FROM SOURCE (no assumption about your machine, no sibling repo):
# the pinned ClojureWasm ref is cloned and built ReleaseSafe with -Dwasm (the
# bookshelf drives SQLite + cover colours through cljw's Wasm FFI). zwasm resolves
# via ClojureWasm's build.zig.zon tag pin. The build is cached under .cache/ (first
# run only). This is the SAME way the Dockerfile obtains cljw — local and fly are
# symmetric.
#
# Secrets / config come from the environment (direnv): copy .envrc.example to
# .envrc, fill in GOOGLE_CLIENT_ID, `direnv allow`. Sign-in is disabled until it
# is set.
#
#   ./run_local.sh                       # build cljw if needed, then serve :8090
#   PORT=9001 ./run_local.sh             # override the port
#   CLJW=/path/to/cljw ./run_local.sh    # use an existing cljw, skip the build
#   CLJW_REF=<sha|branch> ./run_local.sh # pin a different ClojureWasm ref
#
# Needs (one-time cljw build only): git + Zig 0.16 on PATH.
set -euo pipefail
cd "$(dirname "$0")"

CLJW_REF="${CLJW_REF:-cw-from-scratch}"
CACHE_DIR=".cache/cljw"
CLJW="${CLJW:-$CACHE_DIR/zig-out/bin/cljw}"

if [ ! -x "$CLJW" ]; then
  command -v git >/dev/null || { echo "git not found (needed to fetch cljw source)" >&2; exit 1; }
  command -v zig >/dev/null || { echo "zig 0.16 not found (needed to build cljw)" >&2; exit 1; }
  echo "Building cljw from source ($CLJW_REF) — first run only, ~1 min…"
  rm -rf "$CACHE_DIR"
  git clone --branch "$CLJW_REF" --depth 1 \
    https://github.com/clojurewasm/ClojureWasm.git "$CACHE_DIR"
  ( cd "$CACHE_DIR" && zig build -Dwasm -Doptimize=ReleaseSafe )
fi

mkdir -p data                                  # host dir for the SQLite store (db.clj seeds it)
export CLJW_FS_ROOT="${CLJW_FS_ROOT:-$PWD}"     # FS-jail: confine slurp/File to the repo.
PORT="${PORT:-8090}"
[ -n "${GOOGLE_CLIENT_ID:-}" ] || \
  echo "note: GOOGLE_CLIENT_ID unset — sign-in disabled. Set it via .envrc (see .envrc.example)." >&2

echo "ClojureWasm bookshelf → http://localhost:$PORT   (cljw: $CLJW)"
exec "$CLJW" -M:cljw -m bookshelf.server "$PORT"
