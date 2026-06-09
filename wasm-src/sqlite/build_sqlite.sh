#!/usr/bin/env bash
# Build the SQLite datastore module: C SQLite (amalgamation) + our minimal EDN
# runner, compiled to wasm32-wasi with `zig cc` (bundled wasi-libc — no wasi-sdk
# needed). Output: <OUT>/sqlite3.wasm. Downloads the amalgamation on first run.
#
# Note on the design: SQLite's unix file VFS does not work on wasi-libc (xRead ->
# ENOTSUP), but plain C fread/fwrite and an in-memory db do. cljw_sqlite.c loads
# the db file into an in-memory database (sqlite3_deserialize), runs the SQL, and
# serialises back — real SQLite, real file persistence, no file-VFS. See
# cljw_sqlite.c header.
set -euo pipefail
cd "$(dirname "$0")"
OUT="${1:-../../wasm}"
AMALG_VER="3530200"
AMALG_URL="https://www.sqlite.org/2026/sqlite-amalgamation-${AMALG_VER}.zip"

if [ ! -f sqlite3.c ]; then
  echo "downloading SQLite amalgamation ${AMALG_VER} …"
  tmp="$(mktemp -d)"
  curl -fsSL -o "$tmp/a.zip" "$AMALG_URL"
  unzip -o -q "$tmp/a.zip" -d "$tmp"
  cp "$tmp"/sqlite-amalgamation-*/sqlite3.c "$tmp"/sqlite-amalgamation-*/sqlite3.h .
  rm -rf "$tmp"
fi

echo "building sqlite3.wasm (zig cc wasm32-wasi) …"
zig cc --target=wasm32-wasi -O2 \
  -DSQLITE_THREADSAFE=0 -DSQLITE_OMIT_LOAD_EXTENSION -DSQLITE_OMIT_WAL \
  -DSQLITE_TEMP_STORE=3 -DSQLITE_MAX_MMAP_SIZE=0 \
  cljw_sqlite.c sqlite3.c -o "$OUT/sqlite3.wasm"
echo "    -> $OUT/sqlite3.wasm ($(wc -c < "$OUT/sqlite3.wasm") bytes)"
